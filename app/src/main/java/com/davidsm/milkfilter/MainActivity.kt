package com.davidsm.milkfilter

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    enum class AppState { EMPTY, PREVIEW, EDITING, POST_EDIT }

    enum class Tool(val labelKey: String) {
        FILTER("toolFilter"), PALETTE("toolPalette"),
        BRIGHTNESS("brightness"), CONTRAST("contrast"),
        SIZE("pixelSize"), GRAIN("grain"), COLORS("paletteColors"),
        POINTILLISM("pointillism"), COMPRESSION("compression")
    }

    private var appState     = AppState.EMPTY
    private var activeTool: Tool? = null
    private var activeFilter = "dither"
    private var currentLang  = "en"
    private var sourceBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private val toolChips = mutableMapOf<Tool, TextView>()

    private val ditherState = FilterProcessor.DitherState()
    private val milkState   = FilterProcessor.MilkState()

    private lateinit var resultImage:      ImageView
    private lateinit var emptyPlaceholder: FrameLayout
    private lateinit var titleText:        TextView
    private lateinit var resetBtn:         Button
    private lateinit var doneBtn:          Button
    private lateinit var bottomBar:        FrameLayout
    private lateinit var editBtn:          Button
    private lateinit var editPanel:        View
    private lateinit var controlZone:      FrameLayout
    private lateinit var toolStrip:        LinearLayout
    private lateinit var postEditBar:      LinearLayout
    private lateinit var shareBtn:         Button
    private lateinit var downloadBtn:      Button
    private lateinit var editAgainBtn:     Button
    private lateinit var progressBar:      ProgressBar

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadImageFrom(it) }
    }
    private val savePicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> uri?.let { saveBitmapToUri(it) } }

    private val strings = mapOf(
        "en" to mapOf(
            "brightness"     to "BRIGHT",       "contrast"      to "CONTRAST",
            "pixelSize"      to "PIXEL SIZE",   "grain"         to "GRAIN",
            "paletteColors"  to "COLORS",       "pointillism"   to "POINTILLISM",
            "compression"    to "COMPRESSION",  "level"         to "LEVEL",
            "on"             to "ON",           "off"           to "OFF",
            "reset"          to "RESET",
            "toolFilter"     to "FILTER",       "toolPalette"   to "PALETTE",
            "placeholderTap" to "Tap to add\nan image",
            "btnEdit"        to "EDIT",         "btnDone"       to "DONE",
            "btnShare"       to "SHARE",        "btnDownload"   to "DOWNLOAD",
            "saveFailed"     to "Could not save."
        ),
        "es" to mapOf(
            "brightness"     to "BRILLO",       "contrast"      to "CONTRASTE",
            "pixelSize"      to "ESC. PIXEL",   "grain"         to "GRANO",
            "paletteColors"  to "COLORES",      "pointillism"   to "PUNTILLISMO",
            "compression"    to "COMPRESION",   "level"         to "NIVEL",
            "on"             to "ON",           "off"           to "OFF",
            "reset"          to "RESET",
            "toolFilter"     to "FILTRO",       "toolPalette"   to "PALETA",
            "placeholderTap" to "Toca para añadir\nuna imagen",
            "btnEdit"        to "EDITAR",       "btnDone"       to "LISTO",
            "btnShare"       to "COMPARTIR",    "btnDownload"   to "DESCARGAR",
            "saveFailed"     to "No se pudo guardar."
        )
    )
    private fun t(key: String) = strings[currentLang]?.get(key) ?: strings["en"]?.get(key) ?: key

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentLang = if (Locale.getDefault().language == "es") "es" else "en"
        setContentView(R.layout.activity_main)
        bindViews()
        applyState(AppState.EMPTY)
    }

    private fun bindViews() {
        resultImage      = findViewById(R.id.resultImage)
        emptyPlaceholder = findViewById(R.id.emptyPlaceholder)
        titleText        = findViewById(R.id.titleText)
        resetBtn         = findViewById(R.id.resetBtn)
        doneBtn          = findViewById(R.id.doneBtn)
        bottomBar        = findViewById(R.id.bottomBar)
        editBtn          = findViewById(R.id.editBtn)
        editPanel        = findViewById(R.id.editPanel)
        controlZone      = editPanel.findViewById(R.id.controlZone)
        toolStrip        = editPanel.findViewById(R.id.toolStrip)
        postEditBar      = findViewById(R.id.postEditBar)
        shareBtn         = findViewById(R.id.shareBtn)
        downloadBtn      = findViewById(R.id.downloadBtn)
        editAgainBtn     = findViewById(R.id.editAgainBtn)
        progressBar      = findViewById(R.id.progressBar)

        findViewById<TextView>(R.id.placeholderText).text = t("placeholderTap")
        resetBtn.text     = t("reset")
        doneBtn.text      = t("btnDone")
        editBtn.text      = t("btnEdit")
        editAgainBtn.text = t("btnEdit")
        shareBtn.text     = t("btnShare")
        downloadBtn.text  = t("btnDownload")

        emptyPlaceholder.setOnClickListener { pickImage.launch("image/*") }
        resultImage.setOnClickListener      { if (appState != AppState.EDITING) pickImage.launch("image/*") }
        editBtn.setOnClickListener          { enterEditMode() }
        editAgainBtn.setOnClickListener     { enterEditMode() }
        resetBtn.setOnClickListener         { resetCurrentFilter() }
        doneBtn.setOnClickListener          { applyState(AppState.POST_EDIT) }
        shareBtn.setOnClickListener         { shareImage() }
        downloadBtn.setOnClickListener      { openSavePicker() }
    }

    private fun applyState(newState: AppState) {
        appState = newState
        emptyPlaceholder.visibility = if (newState == AppState.EMPTY)     View.VISIBLE else View.GONE
        titleText.visibility        = if (newState == AppState.EDITING)   View.GONE    else View.VISIBLE
        resetBtn.visibility         = if (newState == AppState.EDITING)   View.VISIBLE else View.GONE
        doneBtn.visibility          = if (newState == AppState.EDITING)   View.VISIBLE else View.GONE
        bottomBar.visibility        = if (newState == AppState.EMPTY)     View.GONE    else View.VISIBLE
        editBtn.visibility          = if (newState == AppState.PREVIEW)   View.VISIBLE else View.GONE
        editPanel.visibility        = if (newState == AppState.EDITING)   View.VISIBLE else View.GONE
        postEditBar.visibility      = if (newState == AppState.POST_EDIT) View.VISIBLE else View.GONE
        if (newState == AppState.EDITING) refreshToolStrip()
    }

    private fun enterEditMode() { activeTool = null; applyState(AppState.EDITING) }

    private fun resetCurrentFilter() {
        if (activeFilter == "dither") {
            val d = FilterProcessor.DitherState()
            ditherState.paletteIdx = d.paletteIdx; ditherState.brightnessIdx = d.brightnessIdx
            ditherState.contrastIdx = d.contrastIdx; ditherState.ditherStrengthIdx = d.ditherStrengthIdx
            ditherState.pixelScaleIdx = d.pixelScaleIdx; ditherState.paletteColorsIdx = d.paletteColorsIdx
        } else {
            val m = FilterProcessor.MilkState()
            milkState.paletteIdx = m.paletteIdx; milkState.brightnessIdx = m.brightnessIdx
            milkState.contrastIdx = m.contrastIdx; milkState.pointillism = m.pointillism
            milkState.compression = m.compression; milkState.compressionLevelIdx = m.compressionLevelIdx
        }
        val tool = activeTool; activeTool = null
        controlZone.removeAllViews(); controlZone.visibility = View.GONE
        refreshToolStrip()
        if (tool != null) { activeTool = tool; showControlFor(tool); updateChipStyles() }
        if (sourceBitmap != null) triggerProcess()
    }

    private fun refreshToolStrip() {
        toolStrip.removeAllViews(); toolChips.clear()
        controlZone.removeAllViews(); controlZone.visibility = View.GONE
        val tools = if (activeFilter == "dither")
            listOf(Tool.FILTER, Tool.PALETTE, Tool.BRIGHTNESS, Tool.CONTRAST, Tool.SIZE, Tool.GRAIN, Tool.COLORS)
        else
            listOf(Tool.FILTER, Tool.PALETTE, Tool.BRIGHTNESS, Tool.CONTRAST, Tool.POINTILLISM, Tool.COMPRESSION)
        tools.forEach { tool ->
            val chip = layoutInflater.inflate(R.layout.chip_tool, toolStrip, false) as TextView
            chip.text = t(tool.labelKey); chip.setOnClickListener { onToolSelected(tool) }
            toolStrip.addView(chip); toolChips[tool] = chip
        }
    }

    private fun onToolSelected(tool: Tool) {
        if (activeTool == tool) {
            activeTool = null
            controlZone.animate().alpha(0f).translationY(-dpToPx(12).toFloat()).setDuration(150)
                .withEndAction { controlZone.removeAllViews(); controlZone.visibility = View.GONE }.start()
            updateChipStyles(); return
        }
        activeTool = tool; showControlFor(tool); updateChipStyles()
    }

    private fun updateChipStyles() {
        toolChips.forEach { (tool, chip) ->
            if (tool == activeTool) {
                chip.setTextColor(getColor(R.color.mwhite)); chip.setBackgroundResource(R.drawable.bg_chip_active)
            } else {
                chip.setTextColor(getColor(R.color.mgray)); chip.setBackgroundResource(R.drawable.bg_chip_inactive)
            }
        }
    }

    private fun showControlFor(tool: Tool) {
        controlZone.removeAllViews()
        when (tool) {
            Tool.FILTER      -> showFilterControl()
            Tool.PALETTE     -> showPaletteControl()
            Tool.BRIGHTNESS, Tool.CONTRAST, Tool.SIZE, Tool.GRAIN, Tool.COLORS -> showSliderControl(tool)
            Tool.POINTILLISM -> showToggleControl(tool)
            Tool.COMPRESSION -> showCompressionControl()
        }
        controlZone.visibility = View.VISIBLE
        controlZone.alpha = 0f; controlZone.translationY = -dpToPx(16).toFloat()
        controlZone.animate().alpha(1f).translationY(0f)
            .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun showFilterControl() {
        val view = layoutInflater.inflate(R.layout.control_filter, controlZone, false)
        val db = view.findViewById<TextView>(R.id.filterBtnDither)
        val mb = view.findViewById<TextView>(R.id.filterBtnMilk)
        fun update() {
            if (activeFilter == "dither") {
                db.setTextColor(getColor(R.color.mwhite)); db.setBackgroundResource(R.drawable.bg_chip_active)
                mb.setTextColor(getColor(R.color.mgray));  mb.setBackgroundResource(R.drawable.bg_chip_inactive)
            } else {
                mb.setTextColor(getColor(R.color.mwhite)); mb.setBackgroundResource(R.drawable.bg_chip_active)
                db.setTextColor(getColor(R.color.mgray));  db.setBackgroundResource(R.drawable.bg_chip_inactive)
            }
        }
        update()
        db.setOnClickListener { if (activeFilter != "dither") { activeFilter = "dither"; refreshToolStrip(); activeTool = Tool.FILTER; showControlFor(Tool.FILTER); updateChipStyles(); if (sourceBitmap != null) triggerProcess() } }
        mb.setOnClickListener { if (activeFilter != "milk")   { activeFilter = "milk";   refreshToolStrip(); activeTool = Tool.FILTER; showControlFor(Tool.FILTER); updateChipStyles(); if (sourceBitmap != null) triggerProcess() } }
        controlZone.addView(view)
    }

    private fun showPaletteControl() {
        val view      = layoutInflater.inflate(R.layout.control_palette, controlZone, false)
        val nameView  = view.findViewById<TextView>(R.id.paletteName)
        val swatchRow = view.findViewById<LinearLayout>(R.id.paletteSwatchRow)
        val prevBtn   = view.findViewById<ImageButton>(R.id.palettePrev)
        val nextBtn   = view.findViewById<ImageButton>(R.id.paletteNext)
        fun keys()     = if (activeFilter == "dither") FilterProcessor.DITHER_PALETTE_KEYS   else FilterProcessor.MILK_PALETTE_KEYS
        fun labels()   = if (activeFilter == "dither") FilterProcessor.DITHER_PALETTE_LABELS else FilterProcessor.MILK_PALETTE_LABELS
        fun palettes() = if (activeFilter == "dither") FilterProcessor.DITHER_PALETTES       else FilterProcessor.MILK_PALETTES
        fun idx()      = if (activeFilter == "dither") ditherState.paletteIdx                else milkState.paletteIdx
        fun syncColors() {
            if (activeFilter != "dither") return
            val sz = palettes()[keys()[ditherState.paletteIdx]]!!.size
            val i  = FilterProcessor.DITHER_LEVELS["paletteColors"]!!.indexOfFirst { it.toInt() == sz }
            if (i != -1) ditherState.paletteColorsIdx = i
        }
        fun render() {
            val key = keys()[idx()]; nameView.text = labels()[key]; swatchRow.removeAllViews()
            palettes()[key]!!.forEach { c ->
                swatchRow.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(24)).also { it.marginEnd = dpToPx(4) }
                    setBackgroundColor(android.graphics.Color.rgb(c[0], c[1], c[2]))
                })
            }
        }
        render()
        prevBtn.setOnClickListener {
            val k = keys()
            if (activeFilter == "dither") ditherState.paletteIdx = wrapIdx(ditherState.paletteIdx - 1, k.size)
            else milkState.paletteIdx = wrapIdx(milkState.paletteIdx - 1, k.size)
            syncColors(); render(); if (sourceBitmap != null) triggerProcess()
        }
        nextBtn.setOnClickListener {
            val k = keys()
            if (activeFilter == "dither") ditherState.paletteIdx = wrapIdx(ditherState.paletteIdx + 1, k.size)
            else milkState.paletteIdx = wrapIdx(milkState.paletteIdx + 1, k.size)
            syncColors(); render(); if (sourceBitmap != null) triggerProcess()
        }
        controlZone.addView(view)
    }

    private fun configureSeekBar(label: TextView, slider: SeekBar, valueText: TextView,
        labelText: String, levels: FloatArray, initIdx: Int,
        format: (Float) -> String, onChanged: (Int) -> Unit) {
        label.text = labelText; slider.max = levels.size - 1; slider.progress = initIdx
        valueText.text = format(levels[initIdx])
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                onChanged(p); valueText.text = format(levels[p]); if (sourceBitmap != null) triggerProcess()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun showSliderControl(tool: Tool) {
        val view      = layoutInflater.inflate(R.layout.control_slider, controlZone, false)
        val label     = view.findViewById<TextView>(R.id.sliderLabel)
        val slider    = view.findViewById<SeekBar>(R.id.slider)
        val valueText = view.findViewById<TextView>(R.id.sliderValue)
        when (tool) {
            Tool.BRIGHTNESS -> {
                val lv = if (activeFilter == "dither") FilterProcessor.DITHER_LEVELS["brightness"]!! else FilterProcessor.MILK_LEVELS["brightness"]!!
                val idx = if (activeFilter == "dither") ditherState.brightnessIdx else milkState.brightnessIdx
                configureSeekBar(label, slider, valueText, t("brightness"), lv, idx, { "%.1f".format(it) }) { if (activeFilter == "dither") ditherState.brightnessIdx = it else milkState.brightnessIdx = it }
            }
            Tool.CONTRAST -> {
                val lv = if (activeFilter == "dither") FilterProcessor.DITHER_LEVELS["contrast"]!! else FilterProcessor.MILK_LEVELS["contrast"]!!
                val idx = if (activeFilter == "dither") ditherState.contrastIdx else milkState.contrastIdx
                configureSeekBar(label, slider, valueText, t("contrast"), lv, idx, { "%.1f".format(it) }) { if (activeFilter == "dither") ditherState.contrastIdx = it else milkState.contrastIdx = it }
            }
            Tool.SIZE    -> configureSeekBar(label, slider, valueText, t("pixelSize"), FilterProcessor.DITHER_LEVELS["pixelScale"]!!, ditherState.pixelScaleIdx, { "x${it.toInt()}" }) { ditherState.pixelScaleIdx = it }
            Tool.GRAIN   -> configureSeekBar(label, slider, valueText, t("grain"), FilterProcessor.DITHER_LEVELS["ditherStrength"]!!, ditherState.ditherStrengthIdx, { "%.2f".format(it) }) { ditherState.ditherStrengthIdx = it }
            Tool.COLORS  -> configureSeekBar(label, slider, valueText, t("paletteColors"), FilterProcessor.DITHER_LEVELS["paletteColors"]!!, ditherState.paletteColorsIdx, { it.toInt().toString() }) { ditherState.paletteColorsIdx = it }
            else -> {}
        }
        controlZone.addView(view)
    }

    private fun showToggleControl(tool: Tool) {
        val view  = layoutInflater.inflate(R.layout.control_toggle, controlZone, false)
        val label = view.findViewById<TextView>(R.id.toggleLabel)
        val btn   = view.findViewById<Button>(R.id.toggleBtn)
        label.text = t(tool.labelKey)
        fun isOn() = tool == Tool.POINTILLISM && milkState.pointillism
        fun update() { btn.text = if (isOn()) t("on") else t("off") }
        update()
        btn.setOnClickListener { if (tool == Tool.POINTILLISM) milkState.pointillism = !milkState.pointillism; update(); if (sourceBitmap != null) triggerProcess() }
        controlZone.addView(view)
    }

    private fun showCompressionControl() {
        val view        = layoutInflater.inflate(R.layout.control_compression, controlZone, false)
        val label       = view.findViewById<TextView>(R.id.compressionLabel)
        val btn         = view.findViewById<Button>(R.id.compressionBtn)
        val levelRow    = view.findViewById<View>(R.id.compressionLevelRow)
        val levelLabel  = view.findViewById<TextView>(R.id.compressionLevelLabel)
        val levelSlider = view.findViewById<SeekBar>(R.id.compressionLevelSlider)
        val levelValue  = view.findViewById<TextView>(R.id.compressionLevelValue)
        label.text = t("compression"); levelLabel.text = t("level")
        val lv = FilterProcessor.MILK_COMPRESSION_LEVELS.map { it.toFloat() }.toFloatArray()
        fun updateLevel() {
            levelSlider.max = lv.size - 1; levelSlider.progress = milkState.compressionLevelIdx
            levelValue.text = "${lv[milkState.compressionLevelIdx].toInt()}%"
            levelSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { milkState.compressionLevelIdx = p; levelValue.text = "${lv[p].toInt()}%"; if (sourceBitmap != null) triggerProcess() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        fun update() {
            btn.text = if (milkState.compression) t("on") else t("off")
            levelRow.visibility = if (milkState.compression) View.VISIBLE else View.GONE
            if (milkState.compression) updateLevel()
        }
        update()
        btn.setOnClickListener { milkState.compression = !milkState.compression; update(); if (sourceBitmap != null) triggerProcess() }
        controlZone.addView(view)
    }

    private fun loadImageFrom(uri: Uri) {
        lifecycleScope.launch {
            showProgress(true)
            val bmp = withContext(Dispatchers.IO) { decodeSampled(uri, 1500) }
            if (bmp != null) { sourceBitmap?.recycle(); sourceBitmap = bmp; triggerProcess() }
            else showProgress(false)
        }
    }

    private fun decodeSampled(uri: Uri, maxDim: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxDim)
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = scale }) }
    } catch (e: Exception) { null }

    private fun triggerProcess() {
        val src = sourceBitmap ?: return
        lifecycleScope.launch {
            showProgress(true)
            val result = withContext(Dispatchers.Default) {
                if (activeFilter == "dither") FilterProcessor.processDither(src, ditherState)
                else FilterProcessor.processMilk(src, milkState)
            }
            resultBitmap?.recycle(); resultBitmap = result
            resultImage.setImageBitmap(result); showProgress(false)
            if (appState == AppState.EMPTY) applyState(AppState.PREVIEW)
        }
    }

    private fun openSavePicker() {
        savePicker.launch("${activeFilter}-filter-${System.currentTimeMillis()}.png")
    }

    private fun saveBitmapToUri(uri: Uri) {
        val bmp = resultBitmap ?: return
        lifecycleScope.launch {
            showProgress(true)
            val ok = withContext(Dispatchers.IO) {
                try { contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; true }
                catch (e: Exception) { false }
            }
            showProgress(false)
            Snackbar.make(findViewById(R.id.main), if (ok) "✓ Saved" else t("saveFailed"), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareImage() {
        val bmp = resultBitmap ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { bitmapToTempUri(bmp) } ?: return@launch
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, null))
        }
    }

    private fun bitmapToTempUri(bmp: Bitmap): Uri? = try {
        val file = File(File(cacheDir, "shared").also { it.mkdirs() }, "mf_share.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
    } catch (e: Exception) { null }

    private fun showProgress(show: Boolean) { progressBar.visibility = if (show) View.VISIBLE else View.GONE }
    private fun wrapIdx(v: Int, size: Int) = ((v % size) + size) % size
    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}

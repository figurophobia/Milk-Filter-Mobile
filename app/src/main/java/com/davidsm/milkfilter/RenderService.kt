package com.davidsm.milkfilter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that runs [VideoFilterRenderer] so the render survives the app being
 * backgrounded, and shows a progress notification. Communicates back through [RenderBus].
 */
class RenderService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var renderJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            renderJob?.cancel()
            RenderBus.state.value = RenderBus.State.Idle
            stopSelfCompat()
            return START_NOT_STICKY
        }
        if (intent == null) { stopSelfCompat(); return START_NOT_STICKY }

        createChannel()
        startForeground(NOTIF_ID, buildNotification(0, ongoing = true))
        // Keep the CPU (and, on most vendors, the GPU/codec power domains gated off it) awake
        // for the whole render: a screen lock mid-render must not let the system suspend the
        // hardware decode/encode session, or frames come back black or with a stale rotation.
        acquireWakeLock()

        val uri = intent.getStringExtra(EX_URI)?.let { Uri.parse(it) }
        val outPath = intent.getStringExtra(EX_OUT)
        if (uri == null || outPath == null) { finishFailed(); return START_NOT_STICKY }

        val info = VideoInfo(
            intent.getIntExtra(EX_W, 0), intent.getIntExtra(EX_H, 0),
            intent.getLongExtra(EX_DUR, 0L), intent.getIntExtra(EX_ROT, 0),
            intent.getIntExtra(EX_FPS, 30), intent.getBooleanExtra(EX_AUDIO, false)
        )
        val filter = intent.getStringExtra(EX_FILTER) ?: "dither"
        val dither = FilterProcessor.DitherState(
            intent.getIntExtra(EX_D_PAL, 1), intent.getIntExtra(EX_D_BRI, 3),
            intent.getIntExtra(EX_D_CON, 3), intent.getIntExtra(EX_D_STR, 3),
            intent.getIntExtra(EX_D_PIX, 3), intent.getIntExtra(EX_D_COL, 1)
        )
        val milk = FilterProcessor.MilkState(
            intent.getIntExtra(EX_M_PAL, 0), intent.getIntExtra(EX_M_BRI, 3),
            intent.getIntExtra(EX_M_CON, 3), intent.getBooleanExtra(EX_M_PT, false),
            intent.getBooleanExtra(EX_M_CMP, false), intent.getIntExtra(EX_M_CLV, 4)
        )

        RenderBus.state.value = RenderBus.State.Running(0)
        renderJob = scope.launch {
            val (ok: Boolean, failureReason: String?) = try {
                val success = VideoFilterRenderer(applicationContext, contentResolver).render(
                    uri, info, filter, dither, milk, File(outPath)
                ) { pct ->
                    RenderBus.state.value = RenderBus.State.Running(pct)
                    NotificationManagerCompat.from(this@RenderService)
                        .notify(NOTIF_ID, buildNotification(pct, ongoing = true))
                }
                success to null
            } catch (e: kotlinx.coroutines.CancellationException) {
                false to null
            } catch (e: Exception) {
                android.util.Log.e("RenderService", "render failed", e)
                false to e.message
            }

            when {
                !isActive -> { RenderBus.state.value = RenderBus.State.Idle }
                ok -> {
                    RenderBus.state.value = RenderBus.State.Done(outPath)
                    showResultNotification(getString(R.string.render_done_title))
                }
                else -> {
                    RenderBus.state.value = RenderBus.State.Failed(failureReason)
                    showResultNotification(getString(R.string.render_failed_title))
                }
            }
            stopSelfCompat()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MilkFilter:render").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // safety timeout; released explicitly once the render ends
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun stopSelfCompat() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun finishFailed() {
        RenderBus.state.value = RenderBus.State.Failed()
        stopSelfCompat()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.render_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun contentPendingIntent(): PendingIntent {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, open, flags)
    }

    private fun buildNotification(progress: Int, ongoing: Boolean): android.app.Notification {
        val cancelIntent = Intent(this, RenderService::class.java).apply { action = ACTION_CANCEL }
        val cancelFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val cancelPending = PendingIntent.getService(this, 1, cancelIntent, cancelFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.render_notif_title))
            .setContentText(getString(R.string.render_notif_progress, progress))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.render_cancel), cancelPending)
            .build()
    }

    private fun showResultNotification(title: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent())
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_DONE_ID, notif)
    }

    companion object {
        const val CHANNEL_ID = "render"
        const val NOTIF_ID = 42
        const val NOTIF_DONE_ID = 43
        const val ACTION_CANCEL = "com.davidsm.milkfilter.action.CANCEL_RENDER"

        const val EX_URI = "uri"
        const val EX_OUT = "out"
        const val EX_W = "w"; const val EX_H = "h"; const val EX_DUR = "dur"
        const val EX_ROT = "rot"; const val EX_FPS = "fps"; const val EX_AUDIO = "audio"
        const val EX_FILTER = "filter"
        const val EX_D_PAL = "dpal"; const val EX_D_BRI = "dbri"; const val EX_D_CON = "dcon"
        const val EX_D_STR = "dstr"; const val EX_D_PIX = "dpix"; const val EX_D_COL = "dcol"
        const val EX_M_PAL = "mpal"; const val EX_M_BRI = "mbri"; const val EX_M_CON = "mcon"
        const val EX_M_PT = "mpt"; const val EX_M_CMP = "mcmp"; const val EX_M_CLV = "mclv"

        fun intentFor(
            ctx: android.content.Context, uri: Uri, info: VideoInfo, filter: String,
            dither: FilterProcessor.DitherState, milk: FilterProcessor.MilkState, outPath: String
        ): Intent = Intent(ctx, RenderService::class.java).apply {
            putExtra(EX_URI, uri.toString()); putExtra(EX_OUT, outPath)
            putExtra(EX_W, info.width); putExtra(EX_H, info.height); putExtra(EX_DUR, info.durationMs)
            putExtra(EX_ROT, info.rotationDeg); putExtra(EX_FPS, info.fps); putExtra(EX_AUDIO, info.hasAudio)
            putExtra(EX_FILTER, filter)
            putExtra(EX_D_PAL, dither.paletteIdx); putExtra(EX_D_BRI, dither.brightnessIdx)
            putExtra(EX_D_CON, dither.contrastIdx); putExtra(EX_D_STR, dither.ditherStrengthIdx)
            putExtra(EX_D_PIX, dither.pixelScaleIdx); putExtra(EX_D_COL, dither.paletteColorsIdx)
            putExtra(EX_M_PAL, milk.paletteIdx); putExtra(EX_M_BRI, milk.brightnessIdx)
            putExtra(EX_M_CON, milk.contrastIdx); putExtra(EX_M_PT, milk.pointillism)
            putExtra(EX_M_CMP, milk.compression); putExtra(EX_M_CLV, milk.compressionLevelIdx)
        }
    }
}

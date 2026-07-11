package com.davidsm.milkfilter

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel : ViewModel() {

    var activeFilter: String = "dither"
    val ditherState = FilterProcessor.DitherState()
    val milkState = FilterProcessor.MilkState()

    private var sourceBitmap: Bitmap? = null

    private val _resultBitmap = MutableLiveData<Bitmap?>(null)
    val resultBitmap: LiveData<Bitmap?> = _resultBitmap

    private val _processing = MutableLiveData(false)
    val processing: LiveData<Boolean> = _processing

    private var processJob: Job? = null

    fun setImageSource(bitmap: Bitmap) {
        val old = sourceBitmap
        sourceBitmap = bitmap
        // Recycle the old source only AFTER the in-flight job has fully finished,
        // otherwise a still-running (cooperatively-cancelled) job may read it
        // while we recycle it, crashing with "recycled bitmap" (bug 1).
        viewModelScope.launch {
            processJob?.cancelAndJoin()
            old?.recycle()
            reprocessImage()
        }
    }

    /** Cancels any in-flight processing (conflation: only the latest matters). */
    fun reprocessImage() {
        val src = sourceBitmap ?: return
        processJob?.cancel()
        processJob = viewModelScope.launch {
            _processing.value = true
            val result = withContext(Dispatchers.Default) {
                if (activeFilter == "dither") FilterProcessor.processDither(src, ditherState)
                else FilterProcessor.processMilk(src, milkState)
            }
            // If a newer job superseded us, drop this result.
            _resultBitmap.value?.recycle()
            _resultBitmap.value = result
            _processing.value = false
        }
    }

    fun clearImageResult() {
        _resultBitmap.value = null
    }

    override fun onCleared() {
        processJob?.cancel()
        sourceBitmap?.recycle()
        _resultBitmap.value?.recycle()
        super.onCleared()
    }
}

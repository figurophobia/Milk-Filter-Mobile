package com.davidsm.milkfilter

import android.graphics.Bitmap
import android.net.Uri

sealed class MediaJob {
    data class Image(val bitmap: Bitmap) : MediaJob()
    data class Video(val uri: Uri, val info: VideoInfo) : MediaJob()
}

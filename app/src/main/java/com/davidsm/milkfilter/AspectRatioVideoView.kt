package com.davidsm.milkfilter

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

/**
 * A VideoView that letterboxes the video inside its bounds instead of stretching it.
 * Call [setVideoAspect] with the real video dimensions (from MediaPlayer.videoWidth/Height)
 * once the player is prepared.
 */
class AspectRatioVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : VideoView(context, attrs, defStyleAttr) {

    private var videoW = 0
    private var videoH = 0

    fun setVideoAspect(width: Int, height: Int) {
        if (width > 0 && height > 0 && (width != videoW || height != videoH)) {
            videoW = width
            videoH = height
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = getDefaultSize(videoW, widthMeasureSpec)
        var height = getDefaultSize(videoH, heightMeasureSpec)
        if (videoW > 0 && videoH > 0) {
            val boxW = MeasureSpec.getSize(widthMeasureSpec)
            val boxH = MeasureSpec.getSize(heightMeasureSpec)
            val videoRatio = videoW.toFloat() / videoH
            val boxRatio = boxW.toFloat() / boxH
            if (videoRatio > boxRatio) {
                // Video is wider than the box: full width, reduced height.
                width = boxW
                height = (boxW / videoRatio).toInt()
            } else {
                // Video is taller: full height, reduced width.
                height = boxH
                width = (boxH * videoRatio).toInt()
            }
        }
        setMeasuredDimension(width, height)
    }
}

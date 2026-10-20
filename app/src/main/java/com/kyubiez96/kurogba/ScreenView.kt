package com.kyubiez96.kurogba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Renders the core's RGB565 framebuffer. The render thread calls
 * [pollFrame]; drawing uses integer scaling when the view is large enough
 * (crisp pixels) and falls back to a fractional fit on small screens.
 */
class ScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val GBA_W = 240
        const val GBA_H = 160
    }

    private val bitmap: Bitmap = Bitmap.createBitmap(GBA_W, GBA_H, Bitmap.Config.RGB_565)
    private val frameBuffer: ByteBuffer = ByteBuffer.allocateDirect(GBA_W * GBA_H * 2)
    private val info = IntArray(3)
    private var lastSerial = -1
    private val paint = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()

    /** Pulls the newest frame (if any) from native. Returns true when a new frame was drawn. */
    fun pollFrame(): Boolean {
        NativeBridge.nativeGetFrameInfo(info)
        if (info[0] == lastSerial) return false
        val serial = NativeBridge.nativeCopyFrame(frameBuffer)
        if (serial == 0 || serial == lastSerial) return false
        lastSerial = serial
        frameBuffer.position(0)
        bitmap.copyPixelsFromBuffer(frameBuffer)
        postInvalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width
        val vh = height
        if (vw == 0 || vh == 0) return
        val intScale = min(vw / GBA_W, vh / GBA_H)
        val dw: Int
        val dh: Int
        if (intScale >= 1) {
            dw = GBA_W * intScale
            dh = GBA_H * intScale
        } else {
            val fs = min(vw / GBA_W.toFloat(), vh / GBA_H.toFloat())
            dw = (GBA_W * fs).toInt()
            dh = (GBA_H * fs).toInt()
        }
        val left = (vw - dw) / 2
        val top = (vh - dh) / 2
        dst.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }
}

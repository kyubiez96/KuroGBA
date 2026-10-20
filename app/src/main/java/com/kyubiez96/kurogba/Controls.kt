package com.kyubiez96.kurogba

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Shared look for the touch controls; opacity is user-adjustable (0–255). */
object ControlsStyle {
    var opacity: Int = 150
    const val PREF_KEY = "controls_opacity"

    /**
     * Master input gate: GameActivity flips this off while any in-game
     * dialog (menu, slot picker, cheats…) is open, so touches meant for the
     * dialog never leak into the game. Views also force-release themselves
     * when they see the gate closed.
     */
    @Volatile var inputEnabled: Boolean = true
}

private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
private val padStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2f
}
private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    isFakeBoldText = true
}

private fun padColors(pressed: Boolean): Pair<Int, Int> {
    val alpha = ControlsStyle.opacity
    val fill = if (pressed) 0x7C6CFF else 0x2A2A35
    val rgb = (alpha shl 24) or (fill and 0x00FFFFFF)
    val stroke = ((alpha / 2 + 40) shl 24) or 0x00FFFFFF
    return rgb to stroke
}

/**
 * 8-way D-pad drawn as a plus. Slide-friendly: the active direction set is
 * recomputed from every active pointer position on each event, so sliding a
 * thumb across the pad (including diagonals) just works.
 */
class DPadView(context: Context, private val onMask: (Int) -> Unit) : View(context) {

    private val pointers = HashMap<Int, Pair<Float, Float>>()
    private var currentMask = 0
    private val rectV = RectF()
    private val rectH = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val arm = w / 3f
        rectV.set(arm, 0f, arm * 2, h)
        rectH.set(0f, arm, w, arm * 2)
        val (fill, stroke) = padColors(currentMask != 0)
        padPaint.color = fill
        padStroke.color = stroke
        canvas.drawRoundRect(rectV, 14f, 14f, padPaint)
        canvas.drawRoundRect(rectH, 14f, 14f, padPaint)
        canvas.drawRoundRect(rectV, 14f, 14f, padStroke)
        canvas.drawRoundRect(rectH, 14f, 14f, padStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ControlsStyle.inputEnabled) {
            if (pointers.isNotEmpty() || currentMask != 0) {
                pointers.clear()
                recompute()
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pointers[event.getPointerId(i)] = event.getX(i) to event.getY(i)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    pointers[event.getPointerId(i)] = event.getX(i) to event.getY(i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                pointers.remove(event.getPointerId(event.actionIndex))
            }
        }
        recompute()
        return true
    }

    private fun recompute() {
        var mask = 0
        val cx = width / 2f
        val cy = height / 2f
        val dead = width * 0.09f
        for ((x, y) in pointers.values) {
            val dx = x - cx
            val dy = y - cy
            if (abs(dx) > dead) mask = mask or (1 shl (if (dx > 0) NativeBridge.Pad.RIGHT else NativeBridge.Pad.LEFT))
            if (abs(dy) > dead) mask = mask or (1 shl (if (dy > 0) NativeBridge.Pad.DOWN else NativeBridge.Pad.UP))
        }
        if (mask != currentMask) {
            if (mask != 0 && currentMask == 0) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            currentMask = mask
            onMask(mask)
            invalidate()
        }
    }
}

/** Round action button (A / B / L / R). */
class ActionButtonView(
    context: Context,
    private val label: String,
    private val bit: Int,
    private val onChange: (Int, Boolean) -> Unit
) : View(context) {

    private val pointers = HashSet<Int>()
    private var pressed = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf(width, height) / 2f - 3f
        val (fill, stroke) = padColors(pressed)
        padPaint.color = fill
        padStroke.color = stroke
        canvas.drawCircle(width / 2f, height / 2f, radius, padPaint)
        canvas.drawCircle(width / 2f, height / 2f, radius, padStroke)
        labelPaint.color = (ControlsStyle.opacity shl 24) or 0x00FFFFFF
        labelPaint.textSize = radius * 0.85f
        val baseline = height / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ControlsStyle.inputEnabled) {
            forceRelease()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                pointers.add(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                pointers.remove(event.getPointerId(event.actionIndex))
        }
        val now = pointers.isNotEmpty()
        if (now != pressed) {
            pressed = now
            if (now) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onChange(bit, now)
            invalidate()
        }
        return true
    }

    private fun forceRelease() {
        if (pointers.isNotEmpty() || pressed) {
            pointers.clear()
            if (pressed) {
                pressed = false
                onChange(bit, false)
            }
            invalidate()
        }
    }
}

/** Small pill button (Start / Select). */
class PillView(
    context: Context,
    private val label: String,
    private val bit: Int,
    private val onChange: (Int, Boolean) -> Unit
) : View(context) {

    private val pointers = HashSet<Int>()
    private var pressed = false
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(2f, 2f, width - 2f, height - 2f)
        val (fill, stroke) = padColors(pressed)
        padPaint.color = fill
        padStroke.color = stroke
        canvas.drawRoundRect(rect, height / 2f, height / 2f, padPaint)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, padStroke)
        labelPaint.color = (ControlsStyle.opacity shl 24) or 0x00FFFFFF
        labelPaint.textSize = height * 0.42f
        val baseline = height / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ControlsStyle.inputEnabled) {
            forceRelease()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                pointers.add(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                pointers.remove(event.getPointerId(event.actionIndex))
        }
        val now = pointers.isNotEmpty()
        if (now != pressed) {
            pressed = now
            if (now) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onChange(bit, now)
            invalidate()
        }
        return true
    }

    private fun forceRelease() {
        if (pointers.isNotEmpty() || pressed) {
            pointers.clear()
            if (pressed) {
                pressed = false
                onChange(bit, false)
            }
            invalidate()
        }
    }
}

/**
 * Wide shoulder button (L / R) shaped like the slab on top of a real GBA:
 * a broad, low rounded bar meant to hug the top edge of the device (or the
 * top corners of the portrait control deck). Same multi-pointer behaviour
 * as the round action buttons.
 */
class ShoulderView(
    context: Context,
    private val label: String,
    private val bit: Int,
    private val onChange: (Int, Boolean) -> Unit
) : View(context) {

    private val pointers = HashSet<Int>()
    private var pressed = false
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(2f, 2f, width - 2f, height - 2f)
        val (fill, stroke) = padColors(pressed)
        padPaint.color = fill
        padStroke.color = stroke
        val radius = height * 0.38f
        canvas.drawRoundRect(rect, radius, radius, padPaint)
        canvas.drawRoundRect(rect, radius, radius, padStroke)
        labelPaint.color = (ControlsStyle.opacity shl 24) or 0x00FFFFFF
        labelPaint.textSize = height * 0.44f
        val baseline = height / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ControlsStyle.inputEnabled) {
            forceRelease()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                pointers.add(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                pointers.remove(event.getPointerId(event.actionIndex))
        }
        val now = pointers.isNotEmpty()
        if (now != pressed) {
            pressed = now
            if (now) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onChange(bit, now)
            invalidate()
        }
        return true
    }

    private fun forceRelease() {
        if (pointers.isNotEmpty() || pressed) {
            pointers.clear()
            if (pressed) {
                pressed = false
                onChange(bit, false)
            }
            invalidate()
        }
    }
}

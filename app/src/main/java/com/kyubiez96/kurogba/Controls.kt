package com.kyubiez96.kurogba

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

    /**
     * True while the controls sit on the console deck / body panels
     * (Pizza Boy-style skeuomorphic body): buttons get light-gray solid
     * surfaces with dark labels, like the reference handheld. False for
     * translucent overlays drawn on top of the game (My Boy-style).
     */
    @Volatile var lightSurfaces: Boolean = false
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

private fun padColors(pressed: Boolean, light: Boolean = ControlsStyle.lightSurfaces): Pair<Int, Int> {
    val alpha = ControlsStyle.opacity
    if (light) {
        // Deck surfaces stay fairly solid, like the plastic of the reference.
        val a = alpha.coerceAtLeast(215)
        val fill = if (pressed) 0x7C6CFF else 0xD9D9E6
        val rgb = (a shl 24) or (fill and 0x00FFFFFF)
        val stroke = (a shl 24) or 0x11111C
        return rgb to stroke
    }
    val fill = if (pressed) 0x7C6CFF else 0x2A2A35
    val rgb = (alpha shl 24) or (fill and 0x00FFFFFF)
    val stroke = ((alpha / 2 + 40) shl 24) or 0x00FFFFFF
    return rgb to stroke
}

private fun controlLabelColor(light: Boolean = ControlsStyle.lightSurfaces): Int =
    if (light) (0xFF shl 24) or 0x23232E
    else (ControlsStyle.opacity shl 24) or 0x00FFFFFF

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
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val triPath = Path()

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
        // Reference-pad details: round center pivot + triangle marker on
        // each arm end (drawn in the label color, subtle alpha).
        val cx = w / 2f
        val cy = h / 2f
        markerPaint.color = controlLabelColor()
        markerPaint.alpha = (markerPaint.alpha * 0.55f).toInt()
        canvas.drawCircle(cx, cy, arm * 0.30f, markerPaint)
        val ts = arm * 0.17f
        drawTriangle(canvas, cx, arm * 0.30f, ts, 0) // up
        drawTriangle(canvas, cx, h - arm * 0.30f, ts, 2) // down
        drawTriangle(canvas, arm * 0.30f, cy, ts, 3) // left
        drawTriangle(canvas, w - arm * 0.30f, cy, ts, 1) // right
    }

    private fun drawTriangle(canvas: Canvas, cx: Float, cy: Float, size: Float, dir: Int) {
        triPath.reset()
        when (dir) {
            0 -> { // up
                triPath.moveTo(cx, cy - size)
                triPath.lineTo(cx - size, cy + size)
                triPath.lineTo(cx + size, cy + size)
            }
            1 -> { // right
                triPath.moveTo(cx + size, cy)
                triPath.lineTo(cx - size, cy - size)
                triPath.lineTo(cx - size, cy + size)
            }
            2 -> { // down
                triPath.moveTo(cx, cy + size)
                triPath.lineTo(cx - size, cy - size)
                triPath.lineTo(cx + size, cy - size)
            }
            else -> { // left
                triPath.moveTo(cx - size, cy)
                triPath.lineTo(cx + size, cy - size)
                triPath.lineTo(cx + size, cy + size)
            }
        }
        triPath.close()
        canvas.drawPath(triPath, markerPaint)
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
        labelPaint.color = controlLabelColor()
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
    private val onChange: (Int, Boolean) -> Unit,
    private val darkOverlay: Boolean = false
) : View(context) {

    private val pointers = HashSet<Int>()
    private var pressed = false
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(2f, 2f, width - 2f, height - 2f)
        val light = !darkOverlay && ControlsStyle.lightSurfaces
        val (fill, stroke) = padColors(pressed, light)
        padPaint.color = fill
        padStroke.color = stroke
        canvas.drawRoundRect(rect, height / 2f, height / 2f, padPaint)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, padStroke)
        labelPaint.color = controlLabelColor(light)
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
        labelPaint.color = controlLabelColor()
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

/**
 * Small momentary utility button (SAVE / LOAD / SPEED) with a dynamic
 * label. Fires on tap-down for a snappy feel, gives haptic feedback, and
 * honours the same dialog input-lock as every other control.
 */
class UtilityButtonView(
    context: Context,
    label: String,
    private val onTap: () -> Unit
) : View(context) {

    var label: String = label
        set(value) {
            field = value
            invalidate()
        }

    private var pressed = false
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(2f, 2f, width - 2f, height - 2f)
        val (fill, stroke) = padColors(pressed)
        padPaint.color = fill
        padStroke.color = stroke
        val radius = height * 0.30f
        canvas.drawRoundRect(rect, radius, radius, padPaint)
        canvas.drawRoundRect(rect, radius, radius, padStroke)
        labelPaint.color = controlLabelColor()
        labelPaint.textSize = minOf(height * 0.40f, width * 0.24f)
        val baseline = height / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ControlsStyle.inputEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
            }
        }
        return true
    }
}

/** Decorative speaker grille: diagonal slits, bottom-right of the deck. */
class SpeakerGrilleView(context: Context) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        linePaint.color = (150 shl 24) or 0x000000
        linePaint.strokeWidth = h * 0.045f
        // Five parallel slits sloping up to the right, like the reference.
        for (i in 0 until 5) {
            val sx = w * 0.06f + i * w * 0.17f
            canvas.drawLine(sx, h * 0.94f, sx + w * 0.30f, h * 0.28f, linePaint)
        }
    }
}

package com.zcc.minesweeper

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Temporary diagnostic readout: shows the last key events and the raw axes of
 * the last motion event, so it is possible to see exactly what a particular
 * remote or gamepad sends.
 *
 * Toggled with KEYCODE_INFO; [MainActivity] decides whether it starts visible.
 */
class DebugInputOverlay(context: Context) : LinearLayout(context) {

    private val body: TextView
    private val entries = ArrayList<String>()
    private var lastMotionLog = 0L

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(0xCC000000.toInt())

        val title = TextView(context).apply {
            text = "输入调试（INFO 开关）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#FFD600"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        body = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun logKey(event: KeyEvent) {
        add("${actionName(event.action)} ${keyName(event.keyCode)} (${event.keyCode})")
        add("rep=${event.repeatCount} src=0x${event.source.toString(16)}")
        add("dev ${shortDeviceName(event.device)}")
    }

    /** Motion events arrive at ~60 Hz, so the readout is throttled. */
    fun logMotion(event: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMotionLog < MOTION_LOG_INTERVAL_MS) return
        lastMotionLog = now

        val (dx, dy) = GamepadInput.direction(event)
        add("MOVE src=0x${event.source.toString(16)}")
        add("dev ${shortDeviceName(event.device)}")
        add(
            "hat=" + num(event.getAxisValue(MotionEvent.AXIS_HAT_X)) +
                "," + num(event.getAxisValue(MotionEvent.AXIS_HAT_Y)) +
                " xy=" + num(event.getAxisValue(MotionEvent.AXIS_X)) +
                "," + num(event.getAxisValue(MotionEvent.AXIS_Y))
        )
        add(
            "z=" + num(event.getAxisValue(MotionEvent.AXIS_Z)) +
                " rz=" + num(event.getAxisValue(MotionEvent.AXIS_RZ)) +
                " lt=" + num(event.getAxisValue(MotionEvent.AXIS_LTRIGGER)) +
                " rt=" + num(event.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        )
        add("dir=($dx,$dy)")
    }

    /** Mouse hover and clicks, so left/right button mapping can be verified. */
    fun logMouse(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
            add("MOUSE ${buttonName(event.actionButton)}")
            add("at ${event.x.toInt()},${event.y.toInt()}")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastMotionLog < MOTION_LOG_INTERVAL_MS) return
        lastMotionLog = now
        add("MOUSE move ${event.x.toInt()},${event.y.toInt()}")
    }

    private fun add(line: String) {
        entries.add(0, line)
        while (entries.size > MAX_LINES) entries.removeAt(entries.size - 1)
        body.text = entries.joinToString("\n")
    }

    private fun shortDeviceName(device: InputDevice?): String {
        val name = device?.name ?: "?"
        return if (name.length <= MAX_DEVICE_NAME) name else name.substring(0, MAX_DEVICE_NAME) + "…"
    }

    private fun num(value: Float): String =
        if (value == 0f) "0" else String.format(Locale.US, "%.2f", value)

    private fun keyName(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

    private fun buttonName(button: Int): String = when (button) {
        MotionEvent.BUTTON_PRIMARY -> "LEFT"
        MotionEvent.BUTTON_SECONDARY -> "RIGHT"
        MotionEvent.BUTTON_TERTIARY -> "MIDDLE"
        else -> "0x" + button.toString(16)
    }

    private fun actionName(action: Int): String = when (action) {
        KeyEvent.ACTION_DOWN -> "DN"
        KeyEvent.ACTION_UP -> "UP"
        KeyEvent.ACTION_MULTIPLE -> "MULTI"
        else -> action.toString()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_LINES = 8
        const val MAX_DEVICE_NAME = 18
        const val MOTION_LOG_INTERVAL_MS = 120L
    }
}

package com.zcc.minesweeper

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Shared interpretation of gamepad input.
 *
 * Xbox/PS style pads report the d-pad as hat axes and their stick as X/Y axes,
 * so directional input arrives as motion events instead of DPAD_* key events.
 * Keyboard-style pads (and most cheap "TV game" controllers) send real DPAD_*
 * keys instead and never need any of this.
 */
internal object GamepadInput {

    /**
     * Some pads report the vertical axis with the opposite sign. Flip this to
     * -1f if up/down comes out reversed on a particular controller.
     */
    private const val VERTICAL_SIGN = 1f

    /** Deflection needed to count as a press; doubles as the stick dead zone. */
    private const val PRESS_THRESHOLD = 0.5f

    /** No direction held. */
    val NEUTRAL = 0 to 0

    fun isGamepadSource(source: Int): Boolean =
        source and InputDevice.SOURCE_CLASS_MASK == InputDevice.SOURCE_CLASS_JOYSTICK

    /**
     * Direction currently held, as (dx, dy) with -1/0/1 per axis. The d-pad wins
     * over the stick, and only one axis is reported so the cursor never jumps
     * diagonally.
     */
    fun direction(event: MotionEvent): Pair<Int, Int> {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y) * VERTICAL_SIGN
        if (abs(hatX) > PRESS_THRESHOLD || abs(hatY) > PRESS_THRESHOLD) {
            return axisDirection(hatX, hatY)
        }
        return axisDirection(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y) * VERTICAL_SIGN
        )
    }

    private fun axisDirection(x: Float, y: Float): Pair<Int, Int> = when {
        abs(x) >= abs(y) && x > PRESS_THRESHOLD -> 1 to 0
        abs(x) >= abs(y) && x < -PRESS_THRESHOLD -> -1 to 0
        abs(x) < abs(y) && y > PRESS_THRESHOLD -> 0 to 1
        abs(x) < abs(y) && y < -PRESS_THRESHOLD -> 0 to -1
        else -> NEUTRAL
    }
}

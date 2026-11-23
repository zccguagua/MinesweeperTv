package com.zcc.minesweeper

import android.os.Build
import android.view.MotionEvent

/**
 * `MotionEvent.getActionButton()` is API 23. Before that the framework only
 * exposes `getButtonState()`, which for `ACTION_BUTTON_PRESS` already reports the
 * button that was just pressed, so it is a usable stand-in on API 19.
 */
internal fun MotionEvent.actionButtonCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) actionButton else buttonState

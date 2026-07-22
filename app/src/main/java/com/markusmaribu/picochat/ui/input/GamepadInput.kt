package com.markusmaribu.picochat.ui.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Shared gamepad / controller helpers (activity-level dispatch). */
object GamepadInput {

    private const val AXIS_DEADZONE = 0.5f

    /** Maps d-pad center, Enter, etc. onto face-button semantics. */
    fun KeyEvent.normalizedKeyCode(): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BACK -> KeyEvent.KEYCODE_BUTTON_B
        else -> keyCode
    }

    fun KeyEvent.isFirstPress(): Boolean =
        action == KeyEvent.ACTION_DOWN && repeatCount == 0

    fun MotionEvent.isJoystickMove(): Boolean =
        action == MotionEvent.ACTION_MOVE &&
            (source and InputDevice.SOURCE_CLASS_JOYSTICK) != 0

    /**
     * Edge-triggered stick + hat reads. Picks the stronger axis value when
     * both report deflection so one flick doesn't register twice.
     */
    class JoystickEdgeTracker {
        private var heldY = false
        private var heldX = false

        fun onMove(event: MotionEvent, onDirection: (dx: Int, dy: Int) -> Unit) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val stickX = event.getAxisValue(MotionEvent.AXIS_X)
            val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

            val y = pickAxis(hatY, stickY)
            val x = pickAxis(hatX, stickX)

            if (kotlin.math.abs(y) > AXIS_DEADZONE) {
                if (!heldY) {
                    heldY = true
                    onDirection(0, if (y > 0) 1 else -1)
                }
            } else {
                heldY = false
            }

            if (kotlin.math.abs(x) > AXIS_DEADZONE) {
                if (!heldX) {
                    heldX = true
                    onDirection(if (x > 0) 1 else -1, 0)
                }
            } else {
                heldX = false
            }
        }

        private fun pickAxis(hat: Float, stick: Float): Float = when {
            kotlin.math.abs(hat) > AXIS_DEADZONE &&
                kotlin.math.abs(hat) >= kotlin.math.abs(stick) -> hat
            kotlin.math.abs(stick) > AXIS_DEADZONE -> stick
            else -> 0f
        }
    }

    /** Keys we consume on [KeyEvent.ACTION_UP] so the framework stays in sync. */
    val CONSUME_ON_UP: IntArray = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK
    )

    fun KeyEvent.shouldConsumeOnUp(): Boolean {
        val norm = normalizedKeyCode()
        return keyCode in CONSUME_ON_UP || norm in CONSUME_ON_UP
    }
}

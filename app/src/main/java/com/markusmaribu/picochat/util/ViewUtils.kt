package com.markusmaribu.picochat.util

import android.view.View
import android.view.ViewGroup

fun View.clearFocusability() {
    isFocusable = false
    isFocusableInTouchMode = false
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).clearFocusability()
        }
    }
}

package com.markusmaribu.picochat.ui.compose.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Equivalent of android.view.animation.AccelerateInterpolator(). */
val AccelerateEasing: Easing = Easing { it * it }

/** Equivalent of android.view.animation.DecelerateInterpolator(). */
val DecelerateEasing: Easing = Easing { 1f - (1f - it) * (1f - it) }

/** Equivalent of android.view.animation.AccelerateDecelerateInterpolator(). */
val AccelerateDecelerateEasing: Easing =
    Easing { (kotlin.math.cos((it + 1) * Math.PI) / 2.0 + 0.5).toFloat() }

const val SWAP_ANIM_DURATION_MS = 300
const val SWAP_ANIM_HOLD_MS = 100L

/**
 * Drives the DS swap / gray-fade transitions that the old activities built
 * from ObjectAnimators on ScaleLayout ([swapOverlayAlpha], per-view slides).
 *
 * [overlayAlpha] and [contentOffsetFraction] feed straight into DsScreen's
 * `overlayAlpha` / `contentOffsetYFraction` parameters; [overlayActive]
 * indicates when DsScreen should apply the gray `swapBackgroundColor`.
 */
class DsTransitionState {

    val overlayAlpha = Animatable(0f)
    val contentOffsetFraction = Animatable(0f)

    var overlayActive by mutableStateOf(false)
        private set
    var isTransitioning by mutableStateOf(false)
        private set

    /**
     * Runs the classic DS transition: content slides out toward
     * [exitDirection] (+1 = down, -1 = up, 0 = fade only) while a gray
     * overlay fades in (300ms accelerate), [onSwitch] flips the underlying
     * state, then after a 100ms hold the new content slides in from the
     * opposite side while the overlay fades out (300ms decelerate).
     */
    suspend fun run(exitDirection: Float = 0f, onSwitch: suspend () -> Unit) {
        if (isTransitioning) return
        isTransitioning = true
        overlayActive = true
        try {
            coroutineScope {
                launch {
                    overlayAlpha.animateTo(
                        1f, tween(SWAP_ANIM_DURATION_MS, easing = AccelerateEasing)
                    )
                }
                if (exitDirection != 0f) {
                    launch {
                        contentOffsetFraction.animateTo(
                            exitDirection, tween(SWAP_ANIM_DURATION_MS, easing = AccelerateEasing)
                        )
                    }
                }
            }

            onSwitch()
            contentOffsetFraction.snapTo(if (exitDirection != 0f) -exitDirection else 0f)
            delay(SWAP_ANIM_HOLD_MS)

            coroutineScope {
                launch {
                    overlayAlpha.animateTo(
                        0f, tween(SWAP_ANIM_DURATION_MS, easing = DecelerateEasing)
                    )
                }
                if (exitDirection != 0f) {
                    launch {
                        contentOffsetFraction.animateTo(
                            0f, tween(SWAP_ANIM_DURATION_MS, easing = DecelerateEasing)
                        )
                    }
                }
            }
        } finally {
            overlayAlpha.snapTo(0f)
            contentOffsetFraction.snapTo(0f)
            overlayActive = false
            isTransitioning = false
        }
    }
}

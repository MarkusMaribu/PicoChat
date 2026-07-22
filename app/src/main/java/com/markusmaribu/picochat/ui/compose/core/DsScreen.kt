package com.markusmaribu.picochat.ui.compose.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

/**
 * dp-width of the reference device where the layout proportions were designed.
 * All DsScreens override density so their dp-width matches this value, keeping
 * element proportions consistent on every device (port of ScaleLayout's
 * attachBaseContext density trick).
 */
private const val DESIGN_WIDTH_DP = 393f

/**
 * The fixed 4:3 reference size (in px) that all DS screens are laid out at:
 * the largest 4:3 area that fits two stacked screens within the device's
 * portrait dimensions. Derived from the application display metrics so it is
 * identical for the phone window and any Presentation window.
 */
@Composable
fun rememberDsReferenceSize(): IntSize {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        val metrics = appContext.resources.displayMetrics
        val portraitW = minOf(metrics.widthPixels, metrics.heightPixels)
        val portraitH = maxOf(metrics.widthPixels, metrics.heightPixels)
        val s = minOf(portraitW / 4f, portraitH / 6f)
        IntSize((s * 4).toInt(), (s * 3).toInt())
    }
}

/** The density all DS content is composed with, so dp resolve identically to
 *  the old activities' density-override contexts. */
@Composable
fun rememberDsDensity(): Density {
    val appContext = LocalContext.current.applicationContext
    val fontScale = LocalDensity.current.fontScale
    return remember(appContext, fontScale) {
        val metrics = appContext.resources.displayMetrics
        val portraitW = minOf(metrics.widthPixels, metrics.heightPixels)
        Density(density = portraitW / DESIGN_WIDTH_DP, fontScale = fontScale)
    }
}

/**
 * Compose port of ScaleLayout: composes [content] at the fixed 4:3 reference
 * size (with the DS design density), then uniformly scales and letterboxes it
 * to fit the incoming constraints. Pointer input is transformed automatically
 * by the graphics layer, replacing the manual inverse-matrix touch mapping.
 *
 * @param contentRotation clockwise rotation (0/90/180/270) applied to the
 *   rendered content — used when a Presentation display hasn't rotated to
 *   match the device orientation.
 * @param contentOffsetYFraction vertical offset of the content as a fraction
 *   of the reference height (+1 slides fully down); used by swap transitions.
 *   Content is clipped to the 4:3 area while offset.
 * @param swapBackgroundColor when specified, fills the 4:3 area behind the
 *   (possibly offset) content — the swap-transition backdrop.
 * @param overlayAlpha alpha of an overlay drawn on top of the content in
 *   [swapBackgroundColor] — the grey fade-in/fade-out effect.
 */
@Composable
fun DsScreen(
    modifier: Modifier = Modifier,
    contentRotation: Int = 0,
    contentOffsetYFraction: Float = 0f,
    swapBackgroundColor: Color = Color.Unspecified,
    overlayAlpha: Float = 0f,
    content: @Composable BoxScope.() -> Unit
) {
    val ref = rememberDsReferenceSize()
    val dsDensity = rememberDsDensity()
    val rotation = ((contentRotation % 360) + 360) % 360

    Layout(
        modifier = modifier,
        content = {
            CompositionLocalProvider(LocalDensity provides dsDensity) {
                Box(Modifier.fillMaxSize()) {
                    if (swapBackgroundColor.isSpecified) {
                        Box(Modifier.fillMaxSize().background(swapBackgroundColor))
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .graphicsLayer {
                                translationY = contentOffsetYFraction * size.height
                            },
                        content = content
                    )
                    if (overlayAlpha > 0f && swapBackgroundColor.isSpecified) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = overlayAlpha }
                                .background(swapBackgroundColor)
                        )
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val rotated90 = rotation % 180 != 0
        val visW = if (rotated90) ref.height else ref.width
        val visH = if (rotated90) ref.width else ref.height

        val layoutW = if (constraints.hasBoundedWidth) constraints.maxWidth else visW
        val layoutH = if (constraints.hasBoundedHeight) constraints.maxHeight else visH

        val placeable = measurables.first().measure(
            Constraints.fixed(ref.width, ref.height)
        )

        val scale = minOf(
            layoutW.toFloat() / visW,
            layoutH.toFloat() / visH
        )

        layout(layoutW, layoutH) {
            placeable.placeWithLayer(0, 0) {
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                rotationZ = rotation.toFloat()
                scaleX = scale
                scaleY = scale
                translationX = (layoutW - ref.width) / 2f
                translationY = (layoutH - ref.height) / 2f
            }
        }
    }
}

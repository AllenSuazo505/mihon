package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.viewpager.widget.ViewPager
import java.util.WeakHashMap

/**
 * Book-style page-curl transition for the horizontal pager viewers.
 *
 * While a page turns, it rotates in 3D around the spine edge (the left edge for left-to-right
 * reading, the right edge for right-to-left reading) until it is edge-on, while the page
 * underneath is revealed. A soft shadow grows from the spine as the page lifts, and a matching
 * shadow fades from the page settling underneath, which is what sells the curl. The rotation
 * never passes 90 degrees, so the back of a page is never shown mirrored.
 *
 * The page that is turning must be drawn on top: left-to-right pagers use reverse drawing
 * order, right-to-left pagers use normal drawing order (see [PagerViewer]).
 */
class PageCurlTransformer(private val isReversed: Boolean) : ViewPager.PageTransformer {

    /** Per-page shadow drawables, kept weakly so recycled pages can be collected. */
    private val shadows = WeakHashMap<View, GradientDrawable>()

    override fun transformPage(view: View, position: Float) {
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        if (width == 0f || height == 0f) return

        // Perspective depth: larger values flatten the rotation.
        view.cameraDistance = width * CAMERA_DISTANCE_FACTOR

        if (position < -1f || position > 1f) {
            reset(view)
            return
        }

        if (!isReversed) {
            // Left-to-right: pages in [-1, 0] turn around the left (spine) edge.
            if (position <= 0f) {
                turn(view, progress = -position, hingeLeft = true)
            } else {
                // Page settling in underneath: flat, with a fading spine shadow.
                settle(view, progress = 1f - position, hingeLeft = true)
            }
        } else {
            // Right-to-left: pages in [0, 1] turn around the right (spine) edge.
            if (position >= 0f) {
                turn(view, progress = position, hingeLeft = false)
            } else {
                settle(view, progress = 1f + position, hingeLeft = false)
            }
        }
    }

    /** Rotates [view] around its spine edge until edge-on as [progress] goes 0 -> 1. */
    private fun turn(view: View, progress: Float, hingeLeft: Boolean) {
        view.pivotX = if (hingeLeft) 0f else view.width.toFloat()
        view.pivotY = view.height / 2f
        view.rotationY = (if (hingeLeft) -MAX_TURN_DEGREES else MAX_TURN_DEGREES) * progress
        val scale = 1f - LIFT_SHRINK * progress
        view.scaleX = scale
        view.scaleY = scale
        setShadow(view, (progress * TURN_SHADOW_ALPHA).toInt(), hingeLeft)
    }

    /** Leaves [view] flat while its spine shadow fades as [progress] goes 0 -> 1. */
    private fun settle(view: View, progress: Float, hingeLeft: Boolean) {
        view.rotationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        setShadow(view, (progress * SETTLE_SHADOW_ALPHA).toInt(), hingeLeft)
    }

    /** Restores a page to its untransformed state (used for off-screen pages). */
    private fun reset(view: View) {
        view.rotationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        setShadow(view, 0, hingeLeft = true)
    }

    /**
     * Draws a soft shadow growing from the spine edge of [view] into its overlay, darkening
     * with [alpha]. The drawable is cached per view so frames don't allocate.
     */
    private fun setShadow(view: View, alpha: Int, hingeLeft: Boolean) {
        val drawable = shadows.getOrPut(view) {
            GradientDrawable(
                if (hingeLeft) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
                intArrayOf(Color.BLACK, Color.TRANSPARENT),
            ).also { view.overlay.add(it) }
        }
        drawable.alpha = alpha
        drawable.setBounds(0, 0, view.width, view.height)
    }

    companion object {
        /** Rotation of a fully turned page: edge-on, never past it. */
        private const val MAX_TURN_DEGREES = 90f

        /** How much a turning page shrinks as it lifts off the stack. */
        private const val LIFT_SHRINK = 0.03f

        /** Shadow strength on the turning page at full turn. */
        private const val TURN_SHADOW_ALPHA = 110

        /** Shadow strength on the settling page at the start of the turn. */
        private const val SETTLE_SHADOW_ALPHA = 70

        /** Perspective depth, as a multiple of the page width. */
        private const val CAMERA_DISTANCE_FACTOR = 12f
    }
}

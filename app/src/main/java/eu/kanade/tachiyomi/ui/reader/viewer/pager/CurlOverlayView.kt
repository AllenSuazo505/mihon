package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen overlay that draws a REAL book page curl during forward page turns.
 *
 * Why an overlay instead of a [androidx.viewpager.widget.ViewPager.PageTransformer]:
 * a transformer can only rotate, scale, or fade the flat page view, which is why the
 * previous build looked like a presentation slide. A genuine curl has to BEND the page,
 * and bending needs custom drawing: the turning page is captured as a bitmap and redrawn
 * through a curl mesh ([Canvas.drawBitmapMesh]), so the sheet visibly rolls up around a
 * moving curl line, its back face shows (mirrored by the fold itself, dimmed as paper
 * backing), a soft shadow falls on the page being revealed, and the next page shows
 * through where the sheet has lifted.
 *
 * The overlay is touch-transparent (it never consumes touch events, so the pager's drag
 * keeps working underneath it) and fully opaque while a turn is in progress, so the
 * pager's own sliding pages are hidden behind it. It only ever draws during a forward
 * turn; backward turns fall back to the pager's stock slide.
 *
 * The viewer owns the bitmaps' lifecycle: [setSegment] takes ownership of freshly
 * captured bitmaps, [clearCurl] recycles them and hides the overlay.
 */
class CurlOverlayView(context: Context) : View(context) {

    /** The page being turned, captured from its pager holder. */
    private var turningBitmap: Bitmap? = null

    /** The page revealed underneath the turning page. */
    private var revealedBitmap: Bitmap? = null

    /** The page that stays put beside the curl in a two-page spread (landscape), or null. */
    private var staticBitmap: Bitmap? = null

    /** True when the reader shows a two-page spread (landscape): curl on one half only. */
    private var spread = false

    /** True when the curl happens on the right half of a spread (LTR); false = left half (R2L). */
    private var curlOnRight = true

    /** True when the page peels from the right edge (LTR forward); false = left edge (R2L). */
    private var peelFromRight = true

    /**
     * Turn progress 0..1: 0 = the page is flat and fully covering, 1 = it has fully
     * turned away and the revealed page is all that remains.
     */
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /**
     * Curl depth 0..100, from the reader's Page curl settings: drives how fat the
     * roll is, from a tight 4% of the page width up to a fat 24% (50 = default).
     */
    var depthPercent: Int = 50

    /** Shadow paint fading right from the curl line (peel from right). */
    private var shadowRightPaint: Paint? = null

    /** Shadow paint fading left from the curl line (peel from left). */
    private var shadowLeftPaint: Paint? = null

    /** Curl-rect width the cached shadow paints were built for. */
    private var shadowCurlWidth = -1

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // Never consume touches: the pager's drag must keep reaching the pager below.
        return false
    }

    /**
     * Starts (or replaces) a curl segment. Takes ownership of [turning], [revealed], and
     * [static] (may be null when not in a spread); previously held bitmaps are recycled.
     */
    fun setSegment(
        turning: Bitmap,
        revealed: Bitmap,
        static: Bitmap?,
        spread: Boolean,
        curlOnRight: Boolean,
        peelFromRight: Boolean,
    ) {
        recycleAll()
        turningBitmap = turning
        revealedBitmap = revealed
        staticBitmap = static
        this.spread = spread
        this.curlOnRight = curlOnRight
        this.peelFromRight = peelFromRight
        visibility = VISIBLE
        invalidate()
    }

    /** Hides the overlay and recycles its bitmaps. Safe to call when already hidden. */
    fun clearCurl() {
        if (visibility == GONE && turningBitmap == null) return
        recycleAll()
        visibility = GONE
    }

    private fun recycleAll() {
        turningBitmap?.recycle()
        turningBitmap = null
        revealedBitmap?.recycle()
        revealedBitmap = null
        staticBitmap?.recycle()
        staticBitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val turning = turningBitmap ?: return
        val revealed = revealedBitmap ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // Opaque background so nothing of the pager's sliding pages shows through.
        canvas.drawColor(Color.BLACK)

        val halfW = w / 2f
        if (spread) {
            // Static half first, then the curl scene on the other half.
            if (curlOnRight) {
                staticBitmap?.let { canvas.drawBitmap(it, null, android.graphics.RectF(0f, 0f, halfW, h.toFloat()), null) }
                drawCurlScene(canvas, revealed, turning, halfW, 0f, halfW, h.toFloat())
            } else {
                drawCurlScene(canvas, revealed, turning, 0f, 0f, halfW, h.toFloat())
                staticBitmap?.let { canvas.drawBitmap(it, null, android.graphics.RectF(halfW, 0f, w.toFloat(), h.toFloat()), null) }
            }
        } else {
            drawCurlScene(canvas, revealed, turning, 0f, 0f, w.toFloat(), h.toFloat())
        }
    }

    /**
     * Draws one curl scene inside the rect ([left], [top], [rectW], [rectH]):
     * the revealed page flat, then the turning page bent through the curl mesh,
     * then a soft shadow on the revealed page beside the curl line.
     */
    private fun drawCurlScene(
        canvas: Canvas,
        revealed: Bitmap,
        turning: Bitmap,
        left: Float,
        top: Float,
        rectW: Float,
        rectH: Float,
    ) {
        canvas.save()
        canvas.translate(left, top)
        canvas.clipRect(0f, 0f, rectW, rectH)

        // The revealed page, flat underneath.
        canvas.drawBitmap(revealed, null, android.graphics.RectF(0f, 0f, rectW, rectH), null)

        // The turning page, bent through the mesh.
        val mesh = buildMesh(rectW, rectH)
        canvas.drawBitmapMesh(turning, MESH_COLS, MESH_ROWS, mesh.verts, 0, mesh.colors, 0, null)

        // Soft shadow on the revealed page beside the curl line.
        ensureShadowPaints(rectW)
        val shadowW = rectW * SHADOW_FRACTION
        val cx = curlLineX(rectW)
        if (peelFromRight) {
            shadowRightPaint?.let {
                it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx, 0f) })
                canvas.drawRect(cx, 0f, cx + shadowW, rectH, it)
            }
        } else {
            shadowLeftPaint?.let {
                it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx, 0f) })
                canvas.drawRect(cx - shadowW, 0f, cx, rectH, it)
            }
        }

        canvas.restore()
    }

    /** X of the curl line inside the curl rect for the current [progress]. */
    private fun curlLineX(rectW: Float): Float =
        if (peelFromRight) rectW * (1f - progress) else rectW * progress

    /** Builds the deformed mesh verts and per-vertex shading for the turning page. */
    private fun buildMesh(rectW: Float, rectH: Float): Mesh {
        val cols = MESH_COLS
        val rows = MESH_ROWS
        val verts = FloatArray((cols + 1) * (rows + 1) * 2)
        val colors = IntArray((cols + 1) * (rows + 1))
        val cx = curlLineX(rectW)
        val radius = rectW * (0.04f + (depthPercent.coerceIn(0, 100) / 100f) * 0.20f)

        for (j in 0..rows) {
            val y = j * rectH / rows
            for (i in 0..cols) {
                val x = i * rectW / cols
                val (sx, shade) = if (peelFromRight) {
                    if (x <= cx) {
                        // Still flat; darken slightly as the curl line approaches.
                        val near = (cx - x) / radius
                        val shade = if (near < 1f) 0.82f + 0.18f * near else 1f
                        x to shade
                    } else {
                        // Rolled around the curl cylinder; past half a turn the sheet
                        // faces away, so it reads as the page's dimmer back face.
                        val theta = min((x - cx) / radius, (2 * PI).toFloat())
                        val sx = cx + radius * sin(theta)
                        val shade = if (theta <= PI.toFloat() / 2f) {
                            1f - 0.25f * (theta / (PI.toFloat() / 2f))
                        } else {
                            BACK_FACE_SHADE
                        }
                        sx to shade
                    }
                } else {
                    if (x >= cx) {
                        val near = (x - cx) / radius
                        val shade = if (near < 1f) 0.82f + 0.18f * near else 1f
                        x to shade
                    } else {
                        val theta = min((cx - x) / radius, (2 * PI).toFloat())
                        val sx = cx - radius * sin(theta)
                        val shade = if (theta <= PI.toFloat() / 2f) {
                            1f - 0.25f * (theta / (PI.toFloat() / 2f))
                        } else {
                            BACK_FACE_SHADE
                        }
                        sx to shade
                    }
                }
                val idx = j * (cols + 1) + i
                verts[idx * 2] = sx
                verts[idx * 2 + 1] = y
                val grey = (shade * 255).toInt().coerceIn(0, 255)
                colors[idx] = Color.rgb(grey, grey, grey)
            }
        }
        return Mesh(verts, colors)
    }

    private fun ensureShadowPaints(rectW: Float) {
        if (shadowRightPaint != null && shadowCurlWidth == rectW.toInt()) return
        shadowCurlWidth = rectW.toInt()
        val shadowW = rectW * SHADOW_FRACTION
        shadowRightPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, shadowW, 0f,
                Color.argb(SHADOW_ALPHA, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        shadowLeftPaint = Paint().apply {
            shader = LinearGradient(
                -shadowW, 0f, 0f, 0f,
                Color.TRANSPARENT,
                Color.argb(SHADOW_ALPHA, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private class Mesh(val verts: FloatArray, val colors: IntArray)

    companion object {
        /** Mesh columns across the page (rows stay at 1: the curl bends horizontally only). */
        private const val MESH_COLS = 48
        private const val MESH_ROWS = 1

        // Curl radius is driven by depthPercent (see buildMesh), not a constant.

        /** How far the shadow beside the curl line reaches, as a fraction of rect width. */
        private const val SHADOW_FRACTION = 0.22f

        /** Shadow strength at the curl line. */
        private const val SHADOW_ALPHA = 90

        /** Brightness of the sheet's back face once it has folded past half a turn. */
        private const val BACK_FACE_SHADE = 0.58f
    }
}

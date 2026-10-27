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
import kotlin.math.sin

/**
 * Full-screen overlay that draws a REAL book page curl during page turns, in both
 * directions and both reading directions.
 *
 * Why an overlay instead of a [androidx.viewpager.widget.ViewPager.PageTransformer]:
 * a transformer can only rotate, scale, or fade the flat page view, which is why an
 * earlier build looked like a presentation slide. A genuine curl has to BEND the page,
 * and bending needs custom drawing: the turning page is captured as a bitmap and redrawn
 * through a curl mesh ([Canvas.drawBitmapMesh]), so the sheet visibly rolls up around
 * a moving curl line, a soft shadow falls on the page being revealed, and the next page
 * shows through where the sheet has lifted.
 *
 * The overlay is touch-transparent (it never consumes touch events, so the pager's drag
 * keeps working underneath it) and fully opaque while a turn is in progress, so the
 * pager's own sliding pages are hidden behind it.
 *
 * Two modes, both driven by the viewer from the pager's scroll callbacks:
 *
 * - Peel (single page): the turning page peels off one edge and the revealed page
 *   shows through where it has lifted. [progress] is the fraction turned away:
 *   0 = the page lies flat and fully covering, 1 = it has fully turned away.
 *   Forward turns peel off the reading-advance edge (right for LTR, left for R2L);
 *   backward turns peel the returning page back on from the opposite edge, so no
 *   direction ever falls back to a plain slide while the curl is enabled.
 * - Spread (landscape, two pages): a one-page advance moves one page across the
 *   spread, so there is no page that "stays put" — the migrating page crosses from
 *   one half to the other while the two neighbouring pages sit underneath as the
 *   base. [progress] is the migration: 0 = the page sits flat on its start half,
 *   1 = it sits flat on its destination half, with a curl at its leading edge
 *   mid-migration. Because both end states are exact, the overlay hands off to the
 *   pager at turn end with no jump.
 *
 * The mesh mappings are deliberately monotonic (a quarter-turn roll, never a weaving
 * roll), so the turning page can never smear into duplicated ghost strips: each
 * screen column shows one layer of the sheet, and everything already turned is
 * hidden inside the roll instead of folding back across the page.
 *
 * The viewer owns the bitmaps' lifecycle: [setPeelSegment] / [setSpreadSegment] take
 * ownership of freshly captured bitmaps, [clearCurl] recycles them and hides the
 * overlay. A size change (e.g. rotation) clears the segment, so stale bitmaps
 * captured at the old size are never drawn into the new layout.
 */
class CurlOverlayView(context: Context) : View(context) {

    /** The page being turned (peel mode), captured from its pager holder. */
    private var turningBitmap: Bitmap? = null

    /** The page revealed underneath the turning page (peel mode). */
    private var revealedBitmap: Bitmap? = null

    /** Left base page (spread mode), or null outside a spread. */
    private var leftBaseBitmap: Bitmap? = null

    /** Right base page (spread mode), or null outside a spread. */
    private var rightBaseBitmap: Bitmap? = null

    /** The page migrating across the spread (spread mode), or null outside a spread. */
    private var migratingBitmap: Bitmap? = null

    /** True when drawing a spread migration; false when drawing a single-page peel. */
    private var spread = false

    /** Peel mode: true when the page peels from the right edge; false = left edge. */
    private var peelFromRight = true

    /** Spread mode: true when the migrating page moves from the right half to the left. */
    private var migrateFromRight = true

    /**
     * Turn progress 0..1. Peel mode: fraction turned away (0 = flat cover, 1 = gone).
     * Spread mode: migration across the halves (0 = start half, 1 = destination half).
     */
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /**
     * Curl depth 0..100, from the reader's Page curl settings: drives how fat the
     * roll is (peel: 4% to 24% of the page width; spread: 8% to 36% of a half width).
     */
    var depthPercent: Int = 50

    /** Shadow paint fading right from a line (peel from right / migrating right). */
    private var shadowRightPaint: Paint? = null

    /** Shadow paint fading left from a line (peel from left / migrating left). */
    private var shadowLeftPaint: Paint? = null

    /** Roll-highlight paint fading right from a line (peel from right). */
    private var highlightRightPaint: Paint? = null

    /** Roll-highlight paint fading left from a line (peel from left). */
    private var highlightLeftPaint: Paint? = null

    /** Rect width the cached paints were built for. */
    private var cachedPaintWidth = -1

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // Never consume touches: the pager's drag must keep reaching the pager below.
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A size change (rotation, window resize) invalidates captured bitmaps: drop
        // the segment so the viewer re-captures at the new size instead of drawing
        // stale bitmaps into the new layout.
        if ((w != oldw || h != oldh) && (oldw != 0 || oldh != 0)) {
            clearCurl()
        }
    }

    /**
     * Starts (or replaces) a single-page peel segment. Takes ownership of [turning]
     * and [revealed]; previously held bitmaps are recycled.
     */
    fun setPeelSegment(
        turning: Bitmap,
        revealed: Bitmap,
        peelFromRight: Boolean,
    ) {
        recycleAll()
        turningBitmap = turning
        revealedBitmap = revealed
        spread = false
        this.peelFromRight = peelFromRight
        visibility = VISIBLE
        invalidate()
    }

    /**
     * Starts (or replaces) a spread migration segment. Takes ownership of [leftBase],
     * [rightBase], and [migrating]; previously held bitmaps are recycled.
     * [migrateFromRight] is true when the migrating page moves right half to left.
     */
    fun setSpreadSegment(
        leftBase: Bitmap,
        rightBase: Bitmap,
        migrating: Bitmap,
        migrateFromRight: Boolean,
    ) {
        recycleAll()
        leftBaseBitmap = leftBase
        rightBaseBitmap = rightBase
        migratingBitmap = migrating
        spread = true
        this.migrateFromRight = migrateFromRight
        visibility = VISIBLE
        invalidate()
    }

    /** Hides the overlay and recycles its bitmaps. Safe to call when already hidden. */
    fun clearCurl() {
        if (visibility == GONE && turningBitmap == null && migratingBitmap == null) return
        recycleAll()
        visibility = GONE
    }

    private fun recycleAll() {
        turningBitmap?.recycle()
        turningBitmap = null
        revealedBitmap?.recycle()
        revealedBitmap = null
        leftBaseBitmap?.recycle()
        leftBaseBitmap = null
        rightBaseBitmap?.recycle()
        rightBaseBitmap = null
        migratingBitmap?.recycle()
        migratingBitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // Opaque background so nothing of the pager's sliding pages shows through.
        canvas.drawColor(Color.BLACK)

        if (spread) {
            val left = leftBaseBitmap ?: return
            val right = rightBaseBitmap ?: return
            val migrating = migratingBitmap ?: return
            val halfW = w / 2f
            canvas.drawBitmap(left, null, android.graphics.RectF(0f, 0f, halfW, h.toFloat()), null)
            canvas.drawBitmap(right, null, android.graphics.RectF(halfW, 0f, w.toFloat(), h.toFloat()), null)
            drawMigration(canvas, migrating, halfW, h.toFloat())
        } else {
            val turning = turningBitmap ?: return
            val revealed = revealedBitmap ?: return
            drawCurlScene(canvas, revealed, turning, 0f, 0f, w.toFloat(), h.toFloat())
        }
    }

    /**
     * Draws one peel scene inside the rect ([left], [top], [rectW], [rectH]):
     * the revealed page flat, then the turning page bent through the curl mesh,
     * then the roll highlight and a soft shadow beside the fold line.
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
        val mesh = buildPeelMesh(rectW, rectH)
        canvas.drawBitmapMesh(turning, MESH_COLS, MESH_ROWS, mesh.verts, 0, mesh.colors, 0, null)

        // Roll highlight on the fold and soft shadow on the revealed page.
        ensurePaints(rectW)
        val radius = peelRadius(rectW)
        val cx = peelLineX(rectW)
        val shadowW = rectW * SHADOW_FRACTION
        if (peelFromRight) {
            highlightRightPaint?.let {
                it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx, 0f) })
                canvas.drawRect(cx, 0f, cx + radius, rectH, it)
            }
            shadowRightPaint?.let {
                it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx, 0f) })
                canvas.drawRect(cx, 0f, cx + shadowW, rectH, it)
            }
        } else {
            highlightLeftPaint?.let {
                it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx, 0f) })
                canvas.drawRect(cx - radius, 0f, cx, rectH, it)
            }
            shadowLeftPaint?.let {
                it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx, 0f) })
                canvas.drawRect(cx - shadowW, 0f, cx, rectH, it)
            }
        }

        canvas.restore()
    }

    /**
     * Draws the migrating page across a spread: the page translates from its start
     * half to its destination half, its leading edge curled mid-migration and flat
     * at both ends, so both rest states are exact. [halfW] is one spread half's width.
     */
    private fun drawMigration(canvas: Canvas, migrating: Bitmap, halfW: Float, h: Float) {
        val p = progress
        // Curl amount: 0 at both ends (page flat on its half), 1 mid-migration.
        val curlAmount = sin(PI.toFloat() * p)
        val radius = halfW * (0.08f + (depthPercent.coerceIn(0, 100) / 100f) * 0.28f)
        val rollArc = (PI.toFloat() / 2f) * radius

        canvas.save()
        canvas.clipRect(0f, 0f, halfW * 2f, h)
        val mesh = buildMigrationMesh(halfW, h, p, curlAmount, radius, rollArc)
        canvas.drawBitmapMesh(migrating, MESH_COLS, MESH_ROWS, mesh.verts, 0, mesh.colors, 0, null)

        // Soft shadow on the base beside the leading edge.
        ensurePaints(halfW * 2f)
        val shadowW = halfW * SHADOW_FRACTION
        if (migrateFromRight) {
            // Moving left: leading edge is the page's left edge.
            val leadX = halfW * (1f - p)
            shadowLeftPaint?.let {
                it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(leadX, 0f) })
                canvas.drawRect(leadX - shadowW, 0f, leadX, h, it)
            }
        } else {
            // Moving right: leading edge is the page's right edge.
            val leadX = halfW * (1f + p)
            shadowRightPaint?.let {
                it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(leadX, 0f) })
                canvas.drawRect(leadX, 0f, leadX + shadowW, h, it)
            }
        }
        canvas.restore()
    }

    /** X of the peel line inside the curl rect for the current [progress]. */
    private fun peelLineX(rectW: Float): Float {
        val radius = peelRadius(rectW)
        // The line overshoots by one radius at full turn so the last of the sheet
        // slides off the edge instead of leaving a remnant band behind.
        return if (peelFromRight) {
            rectW * (1f - progress) - progress * radius
        } else {
            rectW * progress + progress * radius
        }
    }

    /** Roll radius for a peel, from [depthPercent]. */
    private fun peelRadius(rectW: Float): Float =
        rectW * (0.04f + (depthPercent.coerceIn(0, 100) / 100f) * 0.20f)

    /**
     * Builds the deformed mesh verts and per-vertex shading for a peeling page.
     *
     * The mapping is strictly monotonic, so it can never smear into duplicated
     * ghost strips: the flat sheet, then a quarter-turn roll around the curl line
     * (the sheet's content foreshortening as it curves up, exactly like a real
     * surface turning away), and everything past the roll collapses onto the
     * roll's peak line — degenerate triangles that draw nothing, i.e. the turned
     * sheet is hidden inside the roll instead of weaving back and forth across
     * the page. The roll band plus the highlight and shadow drawn over it are
     * what sell the bend.
     */
    private fun buildPeelMesh(rectW: Float, rectH: Float): Mesh {
        val cols = MESH_COLS
        val rows = MESH_ROWS
        val verts = FloatArray((cols + 1) * (rows + 1) * 2)
        val colors = IntArray((cols + 1) * (rows + 1))
        val cx = peelLineX(rectW)
        val radius = peelRadius(rectW)
        val rollArc = (PI.toFloat() / 2f) * radius

        for (j in 0..rows) {
            val y = j * rectH / rows
            for (i in 0..cols) {
                val x = i * rectW / cols
                val (sx, shade) = if (peelFromRight) {
                    if (x <= cx) {
                        // Still flat; darken slightly as the curl line approaches.
                        val near = (cx - x) / radius
                        val shade = if (near < 1f) 0.85f + 0.15f * near else 1f
                        x to shade
                    } else {
                        val d = x - cx
                        if (d <= rollArc) {
                            // Quarter-turn roll around the curl line.
                            val sx = cx + radius * sin(d / radius)
                            val shade = 1f - 0.25f * (d / rollArc)
                            sx to shade
                        } else {
                            // Past the roll: hidden inside it (degenerate sliver).
                            (cx + radius) to 1f
                        }
                    }
                } else {
                    if (x >= cx) {
                        val near = (x - cx) / radius
                        val shade = if (near < 1f) 0.85f + 0.15f * near else 1f
                        x to shade
                    } else {
                        val d = cx - x
                        if (d <= rollArc) {
                            val sx = cx - radius * sin(d / radius)
                            val shade = 1f - 0.25f * (d / rollArc)
                            sx to shade
                        } else {
                            (cx - radius) to 1f
                        }
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

    /**
     * Builds the migration mesh for a spread: the page translates from its start
     * half to its destination half while its leading edge curls. The mapping is
     * monotonic across the page (flat interpolated with the curled shape), flat at
     * both ends, so both rest states are exact.
     */
    private fun buildMigrationMesh(
        halfW: Float,
        h: Float,
        p: Float,
        curlAmount: Float,
        radius: Float,
        rollArc: Float,
    ): Mesh {
        val cols = MESH_COLS
        val rows = MESH_ROWS
        val verts = FloatArray((cols + 1) * (rows + 1) * 2)
        val colors = IntArray((cols + 1) * (rows + 1))

        for (j in 0..rows) {
            val y = j * h / rows
            for (i in 0..cols) {
                val u = i * 1f / cols
                // Distance from the leading edge, in page coordinates.
                val d = if (migrateFromRight) u * halfW else (1f - u) * halfW
                // Curled shape: quarter-turn roll near the leading edge, flat beyond.
                val curled = if (d <= rollArc) radius * sin(d / radius) else radius + (d - rollArc)
                // Blend flat and curled by the curl amount; flat at both ends.
                val along = (1f - curlAmount) * d + curlAmount * curled
                val sx = if (migrateFromRight) {
                    halfW * (1f - p) + along
                } else {
                    halfW * (1f + p) - along
                }
                // Shade the curled leading strip darker; flat elsewhere.
                val shade = if (d <= rollArc) {
                    1f - 0.28f * (1f - d / rollArc) * curlAmount
                } else {
                    1f
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

    private fun ensurePaints(rectW: Float) {
        if (shadowRightPaint != null && cachedPaintWidth == rectW.toInt()) return
        cachedPaintWidth = rectW.toInt()
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
        highlightRightPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, shadowW, 0f,
                Color.argb(HIGHLIGHT_ALPHA, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        highlightLeftPaint = Paint().apply {
            shader = LinearGradient(
                -shadowW, 0f, 0f, 0f,
                Color.TRANSPARENT,
                Color.argb(HIGHLIGHT_ALPHA, 255, 255, 255),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private class Mesh(val verts: FloatArray, val colors: IntArray)

    companion object {
        /** Mesh columns across the page (rows stay at 1: the curl bends horizontally only). */
        private const val MESH_COLS = 48
        private const val MESH_ROWS = 1

        // Curl radius is driven by depthPercent (see peelRadius / drawMigration).

        /** How far the shadow beside the fold line reaches, as a fraction of rect width. */
        private const val SHADOW_FRACTION = 0.22f

        /** Shadow strength at the fold line. */
        private const val SHADOW_ALPHA = 90

        /** Roll-highlight strength at the fold line. */
        private const val HIGHLIGHT_ALPHA = 70
    }
}

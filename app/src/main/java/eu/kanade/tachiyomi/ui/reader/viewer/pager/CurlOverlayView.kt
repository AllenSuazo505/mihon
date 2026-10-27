package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Full-screen overlay that draws a REAL book page curl during page turns, in both
 * directions and both reading directions, tuned toward the Apple Books reference:
 * a page that bends around a moving curl line (never a flat slide or a stiff fold),
 * the back of the turning page visible and shaded as it rolls over, a soft shadow
 * falling on the page beneath, the curl following the finger 1:1 through the
 * pager's scroll callbacks, and the pager's own decelerating settle when released.
 *
 * Why an overlay instead of a [androidx.viewpager.widget.ViewPager.PageTransformer]:
 * a transformer can only rotate, scale, or fade the flat page view, which is why an
 * earlier build looked like a presentation slide. A genuine curl has to BEND the page,
 * and bending needs custom drawing: the turning page is captured as a bitmap and
 * redrawn through a curl mesh ([Canvas.drawBitmapMesh]), so the sheet visibly rolls
 * up around a moving curl line, a soft shadow falls on the page being revealed, and
 * the next page shows through where the sheet has lifted.
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
 * What makes it read as a real page (Apple Books cues):
 *
 * - The curl line is gently bowed along the page height (mid-height leads), so the
 *   fold is a curve, not a rigid vertical ruler-edge. The mesh has [MESH_ROWS] rows
 *   so the line can bow; each row's mapping is still strictly monotonic, so the
 *   turning page can never smear into duplicated ghost strips.
 * - The turned-over part of the sheet is drawn as a mirrored, shaded back face
 *   beside the roll (like thin paper with the ink showing through), instead of
 *   vanishing into the roll. Without it the turn reads as a wipe; with it the sheet
 *   visibly flips over. The back face is clipped to its own side of the curl line,
 *   so it never paints over the roll itself.
 * - Shadows are two-layer: a tight contact shadow at the fold plus a wide soft
 *   falloff on the revealed page, both tracking the (bowed) curl line.
 *
 * Survey note (Oct 2026 refinement): existing open-source curl implementations
 * were evaluated before refining this mesh — harism/android-pagecurl (Apache 2.0,
 * OpenGL ES 1.0 cylinder model with front/back faces), oleksandrbalan/pagecurl
 * (Jetpack Compose component), eschao/android-PageFlip (OpenGL ES 2.0), and
 * NBReader's SimulationPageAnimation (Bezier fold + clip paths + gradient
 * shadows). None was adopted as code: every realistic one is a standalone GL view
 * or Compose component with its own page/touch model, and none plugs into this
 * overlay's ViewPager scroll-segment, spread-migration, or R2L model — adopting
 * one would have meant rewriting the overlay around it with no emulator to test
 * on. The techniques above (back face, layered shadows, curved line) are adapted
 * from those projects' proven approach, implemented here from scratch; no
 * third-party code is copied, so no new attribution is owed.
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
     * roll is (peel: 5% to 27% of the page width; spread: 8% to 36% of a half width).
     */
    var depthPercent: Int = 50

    /** Cached gradient paints for one rect width; rebuilt when the width changes. */
    private var cachedPaintWidth = -1
    private var shadowContactRightPaint: Paint? = null
    private var shadowContactLeftPaint: Paint? = null
    private var shadowWideRightPaint: Paint? = null
    private var shadowWideLeftPaint: Paint? = null
    private var highlightRightPaint: Paint? = null
    private var highlightLeftPaint: Paint? = null

    /** Unit-gradient paints for the back face, positioned per band with a matrix. */
    private var backShadeToRightPaint: Paint? = null
    private var backShadeToLeftPaint: Paint? = null

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
            canvas.drawBitmap(left, null, RectF(0f, 0f, halfW, h.toFloat()), null)
            canvas.drawBitmap(right, null, RectF(halfW, 0f, w.toFloat(), h.toFloat()), null)
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
     * then the mirrored back face beside the roll, then the roll highlight and
     * two-layer shadow tracking the bowed curl line.
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
        canvas.drawBitmap(revealed, null, RectF(0f, 0f, rectW, rectH), null)

        // The turning page, bent through the mesh.
        val mesh = buildPeelMesh(rectW, rectH)
        canvas.drawBitmapMesh(turning, MESH_COLS, MESH_ROWS, mesh.verts, 0, mesh.colors, 0, null)

        // The back of the turned-over sheet, mirrored and shaded, on its own side
        // of the curl line — the cue that sells the flip instead of a wipe.
        drawBackFace(canvas, turning, rectW, rectH)

        // Roll highlight and two-layer shadow, following the bowed line per band.
        drawPeelOverlays(canvas, rectW, rectH)

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

        // Back of the curled leading strip, mirrored onto the base beside the
        // leading edge. Scaled by curlAmount, so both rest states stay exact.
        if (curlAmount > 0.01f) {
            drawMigrationBackSliver(canvas, migrating, halfW, h, p, curlAmount, radius, rollArc)
        }

        val mesh = buildMigrationMesh(halfW, h, p, curlAmount, radius, rollArc)
        canvas.drawBitmapMesh(migrating, MESH_COLS, MESH_ROWS, mesh.verts, 0, mesh.colors, 0, null)

        // Two-layer shadow on the base beside the leading edge.
        ensurePaints(halfW * 2f)
        val contactW = halfW * SHADOW_CONTACT_FRACTION
        val wideW = halfW * SHADOW_WIDE_FRACTION
        if (migrateFromRight) {
            // Moving left: leading edge is the page's left edge.
            val leadX = halfW * (1f - p)
            shadowContactLeftPaint?.let {
                it.shader?.setLocalMatrix(Matrix().apply { setTranslate(leadX, 0f) })
                canvas.drawRect(leadX - contactW, 0f, leadX, h, it)
            }
            shadowWideLeftPaint?.let {
                it.shader?.setLocalMatrix(Matrix().apply { setTranslate(leadX, 0f) })
                canvas.drawRect(leadX - wideW, 0f, leadX, h, it)
            }
        } else {
            // Moving right: leading edge is the page's right edge.
            val leadX = halfW * (1f + p)
            shadowContactRightPaint?.let {
                it.shader?.setLocalMatrix(Matrix().apply { setTranslate(leadX, 0f) })
                canvas.drawRect(leadX, 0f, leadX + contactW, h, it)
            }
            shadowWideRightPaint?.let {
                it.shader?.setLocalMatrix(Matrix().apply { setTranslate(leadX, 0f) })
                canvas.drawRect(leadX, 0f, leadX + wideW, h, it)
            }
        }
        canvas.restore()
    }

    /**
     * Mirrored back of the migrating page's curled leading strip, on the base side
     * of the leading edge. Width and opacity scale with [curlAmount], so the sliver
     * vanishes exactly at both rest states.
     */
    private fun drawMigrationBackSliver(
        canvas: Canvas,
        migrating: Bitmap,
        halfW: Float,
        h: Float,
        p: Float,
        curlAmount: Float,
        radius: Float,
        rollArc: Float,
    ) {
        val bmpW = migrating.width
        val bmpH = migrating.height
        if (bmpW <= 0 || bmpH <= 0) return
        val stripW = minOf(0.5f * rollArc * curlAmount, 0.2f * halfW)
        if (stripW <= 0.5f) return
        val alphaPaint = Paint().apply { alpha = (230f * curlAmount).toInt().coerceIn(0, 255) }
        if (migrateFromRight) {
            // Leading edge = page's left edge, at leadX; sliver to its left.
            val leadX = halfW * (1f - p)
            val srcW = (stripW / halfW * bmpW).toInt().coerceIn(1, bmpW)
            canvas.save()
            canvas.translate(leadX, 0f)
            canvas.scale(-1f, 1f)
            canvas.drawBitmap(
                migrating,
                Rect(0, 0, srcW, bmpH),
                RectF(0f, 0f, stripW, h),
                alphaPaint,
            )
            canvas.restore()
            // Shade: darker toward the leading edge, fading with curlAmount.
            val shade = Paint().apply {
                alpha = (255f * curlAmount).toInt().coerceIn(0, 255)
                shader = LinearGradient(
                    0f, 0f, 1f, 0f,
                    intArrayOf(
                        Color.argb(BACK_UNIFORM_ALPHA, 0, 0, 0),
                        Color.argb(BACK_ROLL_ALPHA, 0, 0, 0),
                    ),
                    null,
                    Shader.TileMode.CLAMP,
                )
                shader?.setLocalMatrix(
                    Matrix().apply {
                        setScale(stripW, 1f)
                        postTranslate(leadX - stripW, 0f)
                    },
                )
            }
            canvas.drawRect(leadX - stripW, 0f, leadX, h, shade)
        } else {
            // Leading edge = page's right edge, at leadX; sliver to its right.
            val leadX = halfW * (1f + p)
            val srcX0 = ((1f - stripW / halfW) * bmpW).toInt().coerceIn(0, bmpW - 1)
            canvas.save()
            canvas.translate(leadX + stripW, 0f)
            canvas.scale(-1f, 1f)
            canvas.drawBitmap(
                migrating,
                Rect(srcX0, 0, bmpW, bmpH),
                RectF(0f, 0f, stripW, h),
                alphaPaint,
            )
            canvas.restore()
            val shade = Paint().apply {
                alpha = (255f * curlAmount).toInt().coerceIn(0, 255)
                shader = LinearGradient(
                    0f, 0f, 1f, 0f,
                    intArrayOf(
                        Color.argb(BACK_ROLL_ALPHA, 0, 0, 0),
                        Color.argb(BACK_UNIFORM_ALPHA, 0, 0, 0),
                    ),
                    null,
                    Shader.TileMode.CLAMP,
                )
                shader?.setLocalMatrix(
                    Matrix().apply {
                        setScale(stripW, 1f)
                        postTranslate(leadX, 0f)
                    },
                )
            }
            canvas.drawRect(leadX, 0f, leadX + stripW, h, shade)
        }
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
        rectW * (0.05f + (depthPercent.coerceIn(0, 100) / 100f) * 0.22f)

    /**
     * Curl-line X at mesh row [row] (0..[MESH_ROWS]): the base line bowed so the
     * middle of the fold leads the turn, like a page pulled from the middle of its
     * edge. Pinned at the top and bottom edges, so the bow never breaks the rect.
     */
    private fun curlLineXAt(row: Int, rectW: Float): Float {
        val base = peelLineX(rectW)
        val bow = rectW * BOW_FRACTION * sin(PI.toFloat() * row / MESH_ROWS)
        return if (peelFromRight) base - bow else base + bow
    }

    /** Curl-line X at the middle of mesh band [band] (0..[MESH_ROWS]-1). */
    private fun curlLineXAtBand(band: Int, rectW: Float): Float {
        val base = peelLineX(rectW)
        val bow = rectW * BOW_FRACTION * sin(PI.toFloat() * (band + 0.5f) / MESH_ROWS)
        return if (peelFromRight) base - bow else base + bow
    }

    /**
     * Builds the deformed mesh verts and per-vertex shading for a peeling page.
     *
     * The mapping is strictly monotonic within each row, so it can never smear
     * into duplicated ghost strips: the flat sheet, then a quarter-turn roll
     * around the curl line (the sheet's content foreshortening as it curves up,
     * exactly like a real surface turning away), and everything past the roll
     * collapses onto the roll's peak line — degenerate triangles that draw
     * nothing. The turned-over sheet itself is drawn separately as the back face
     * (see [drawBackFace]); the roll band plus the highlight and shadow drawn
     * over it are what sell the bend.
     */
    private fun buildPeelMesh(rectW: Float, rectH: Float): Mesh {
        val cols = MESH_COLS
        val rows = MESH_ROWS
        val verts = FloatArray((cols + 1) * (rows + 1) * 2)
        val colors = IntArray((cols + 1) * (rows + 1))
        val radius = peelRadius(rectW)
        val rollArc = (PI.toFloat() / 2f) * radius

        for (j in 0..rows) {
            val y = j * rectH / rows
            val cx = curlLineXAt(j, rectW)
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
     * Draws the mirrored back of the turned-over sheet beside the roll, one band
     * at a time following the bowed curl line. The back face is the part of the
     * sheet past the roll, flipped over: its free edge lands farthest from the
     * roll, its roll-adjacent content sits against the roll, and it is shaded
     * darker overall and darker still toward the roll (occlusion). It is drawn
     * only on its own side of the curl line, so it never paints over the roll,
     * and it vanishes exactly at both rest states (no sheet past the roll at
     * progress 0; everything off-screen at progress 1).
     */
    private fun drawBackFace(canvas: Canvas, turning: Bitmap, rectW: Float, rectH: Float) {
        val bmpW = turning.width
        val bmpH = turning.height
        if (bmpW <= 0 || bmpH <= 0) return
        val radius = peelRadius(rectW)
        val rollArc = (PI.toFloat() / 2f) * radius
        ensurePaints(rectW)

        for (band in 0 until MESH_ROWS) {
            val y0 = band * rectH / MESH_ROWS
            val y1 = (band + 1) * rectH / MESH_ROWS
            val cx = curlLineXAtBand(band, rectW)
            val srcY0 = (y0 / rectH * bmpH).toInt().coerceIn(0, bmpH - 1)
            val srcY1 = (y1 / rectH * bmpH).toInt().coerceIn(srcY0 + 1, bmpH)

            if (peelFromRight) {
                // Sheet past the roll: s in (cx + rollArc, rectW]. Only the part
                // whose screen position falls left of the curl line is visible.
                val backLen = rectW - cx - rollArc
                val stripW = backLen - radius
                if (stripW <= 0f) continue
                val srcX0 = ((cx + rollArc + radius) / rectW * bmpW).toInt()
                if (srcX0 >= bmpW) continue
                canvas.save()
                canvas.translate(cx, y0)
                canvas.scale(-1f, 1f)
                canvas.drawBitmap(
                    turning,
                    Rect(srcX0, srcY0, bmpW, srcY1),
                    RectF(0f, 0f, stripW, y1 - y0),
                    null,
                )
                canvas.restore()
                backShadeToRightPaint?.let {
                    it.shader?.setLocalMatrix(
                        Matrix().apply {
                            setScale(stripW, 1f)
                            postTranslate(cx - stripW, 0f)
                        },
                    )
                    canvas.drawRect(cx - stripW, y0, cx, y1, it)
                }
            } else {
                // Mirror: sheet before the roll, s in [0, cx - rollArc).
                val backLen = cx - rollArc
                val stripW = backLen - radius
                if (stripW <= 0f) continue
                val srcX1 = ((cx - rollArc - radius) / rectW * bmpW).toInt()
                if (srcX1 <= 0) continue
                canvas.save()
                canvas.translate(cx + stripW, y0)
                canvas.scale(-1f, 1f)
                canvas.drawBitmap(
                    turning,
                    Rect(0, srcY0, srcX1, srcY1),
                    RectF(0f, 0f, stripW, y1 - y0),
                    null,
                )
                canvas.restore()
                backShadeToLeftPaint?.let {
                    it.shader?.setLocalMatrix(
                        Matrix().apply {
                            setScale(stripW, 1f)
                            postTranslate(cx, 0f)
                        },
                    )
                    canvas.drawRect(cx, y0, cx + stripW, y1, it)
                }
            }
        }
    }

    /**
     * Roll highlight plus two-layer shadow (tight contact shadow at the fold,
     * wide soft falloff on the revealed page), drawn per band so both track the
     * bowed curl line.
     */
    private fun drawPeelOverlays(canvas: Canvas, rectW: Float, rectH: Float) {
        val radius = peelRadius(rectW)
        ensurePaints(rectW)
        val contactW = rectW * SHADOW_CONTACT_FRACTION
        val wideW = rectW * SHADOW_WIDE_FRACTION

        for (band in 0 until MESH_ROWS) {
            val y0 = band * rectH / MESH_ROWS
            val y1 = (band + 1) * rectH / MESH_ROWS
            val cx = curlLineXAtBand(band, rectW)
            if (peelFromRight) {
                highlightRightPaint?.let {
                    it.shader?.setLocalMatrix(Matrix().apply { setTranslate(cx, 0f) })
                    canvas.drawRect(cx, y0, cx + radius, y1, it)
                }
                shadowContactRightPaint?.let {
                    it.shader?.setLocalMatrix(Matrix().apply { setTranslate(cx, 0f) })
                    canvas.drawRect(cx, y0, cx + contactW, y1, it)
                }
                shadowWideRightPaint?.let {
                    it.shader?.setLocalMatrix(Matrix().apply { setTranslate(cx, 0f) })
                    canvas.drawRect(cx, y0, cx + wideW, y1, it)
                }
            } else {
                highlightLeftPaint?.let {
                    it.shader?.setLocalMatrix(Matrix().apply { setTranslate(cx, 0f) })
                    canvas.drawRect(cx - radius, y0, cx, y1, it)
                }
                shadowContactLeftPaint?.let {
                    it.shader?.setLocalMatrix(Matrix().apply { setTranslate(cx, 0f) })
                    canvas.drawRect(cx - contactW, y0, cx, y1, it)
                }
                shadowWideLeftPaint?.let {
                    it.shader?.setLocalMatrix(Matrix().apply { setTranslate(cx, 0f) })
                    canvas.drawRect(cx - wideW, y0, cx, y1, it)
                }
            }
        }
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
                    1f - 0.38f * (1f - d / rollArc) * curlAmount
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

    private fun gradientPaint(
        x0: Float,
        x1: Float,
        colors: IntArray,
        positions: FloatArray?,
    ): Paint {
        return Paint().apply {
            shader = LinearGradient(x0, 0f, x1, 0f, colors, positions, Shader.TileMode.CLAMP)
        }
    }

    private fun ensurePaints(rectW: Float) {
        if (shadowContactRightPaint != null && cachedPaintWidth == rectW.toInt()) return
        cachedPaintWidth = rectW.toInt()
        val contactW = rectW * SHADOW_CONTACT_FRACTION
        val wideW = rectW * SHADOW_WIDE_FRACTION
        val stops = floatArrayOf(0f, 0.45f, 1f)
        shadowContactRightPaint = gradientPaint(
            0f, contactW,
            intArrayOf(
                Color.argb(SHADOW_CONTACT_ALPHA, 0, 0, 0),
                Color.argb(SHADOW_CONTACT_ALPHA / 2, 0, 0, 0),
                Color.TRANSPARENT,
            ),
            stops,
        )
        shadowContactLeftPaint = gradientPaint(
            -contactW, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(SHADOW_CONTACT_ALPHA / 2, 0, 0, 0),
                Color.argb(SHADOW_CONTACT_ALPHA, 0, 0, 0),
            ),
            stops,
        )
        shadowWideRightPaint = gradientPaint(
            0f, wideW,
            intArrayOf(
                Color.argb(SHADOW_WIDE_ALPHA, 0, 0, 0),
                Color.argb(SHADOW_WIDE_ALPHA / 2, 0, 0, 0),
                Color.TRANSPARENT,
            ),
            stops,
        )
        shadowWideLeftPaint = gradientPaint(
            -wideW, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(SHADOW_WIDE_ALPHA / 2, 0, 0, 0),
                Color.argb(SHADOW_WIDE_ALPHA, 0, 0, 0),
            ),
            stops,
        )
        highlightRightPaint = gradientPaint(
            0f, wideW,
            intArrayOf(
                Color.argb(HIGHLIGHT_ALPHA, 255, 255, 255),
                Color.argb(HIGHLIGHT_ALPHA / 2, 255, 255, 255),
                Color.TRANSPARENT,
            ),
            stops,
        )
        highlightLeftPaint = gradientPaint(
            -wideW, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(HIGHLIGHT_ALPHA / 2, 255, 255, 255),
                Color.argb(HIGHLIGHT_ALPHA, 255, 255, 255),
            ),
            stops,
        )
        // Unit gradients for the back face; positioned per band with a matrix.
        backShadeToRightPaint = gradientPaint(
            0f, 1f,
            intArrayOf(
                Color.argb(BACK_UNIFORM_ALPHA, 0, 0, 0),
                Color.argb(BACK_ROLL_ALPHA, 0, 0, 0),
            ),
            null,
        )
        backShadeToLeftPaint = gradientPaint(
            0f, 1f,
            intArrayOf(
                Color.argb(BACK_ROLL_ALPHA, 0, 0, 0),
                Color.argb(BACK_UNIFORM_ALPHA, 0, 0, 0),
            ),
            null,
        )
    }

    private class Mesh(val verts: FloatArray, val colors: IntArray)

    companion object {
        /** Mesh columns across the page; rows let the curl line bow along its height. */
        private const val MESH_COLS = 48
        private const val MESH_ROWS = 8

        /** How far the curl line's middle leads the turn, as a fraction of rect width. */
        private const val BOW_FRACTION = 0.02f

        /** Tight contact shadow at the fold, as a fraction of rect width. */
        private const val SHADOW_CONTACT_FRACTION = 0.10f

        /** Wide soft shadow falloff, as a fraction of rect width. */
        private const val SHADOW_WIDE_FRACTION = 0.35f

        /** Contact shadow strength at the fold line. */
        private const val SHADOW_CONTACT_ALPHA = 120

        /** Wide shadow strength at the fold line. */
        private const val SHADOW_WIDE_ALPHA = 55

        /** Roll-highlight strength at the fold line. */
        private const val HIGHLIGHT_ALPHA = 95

        /** Back-face darkening far from the roll. */
        private const val BACK_UNIFORM_ALPHA = 35

        /** Back-face darkening at the roll (occlusion). */
        private const val BACK_ROLL_ALPHA = 110
    }
}

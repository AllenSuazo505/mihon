package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.Scroller
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.GeneralSettings
import eu.kanade.tachiyomi.ui.reader.viewer.LocalPageColorFilter
import eu.kanade.tachiyomi.ui.reader.viewer.PagedSettings
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.collectChanges
import eu.kanade.tachiyomi.ui.reader.viewer.mouseWheelPaging
import eu.kanade.tachiyomi.ui.reader.viewer.position
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(val activity: ReaderActivity) : Viewer {

    val graph by lazy { activity.appGraph }
    val downloadManager by lazy { graph.downloadManager }

    private val scope = MainScope()

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    val pager = createPager()

    private val settingsViewModel = activity.settingsViewModel

    /**
     * Settings every paged viewer uses, like scale mode on images or tap zones.
     */
    val paged: PagedSettings
        get() = settingsViewModel.paged.value

    /**
     * Settings only the pager viewers use, like splitting double pages.
     */
    val simplePaged: SimplePagedSettings
        get() = settingsViewModel.simplePaged.value

    /**
     * Settings shared by every viewer, like allow taps or page transitions.
     */
    val general: GeneralSettings
        get() = settingsViewModel.general.value

    val imageZoomType: ZoomStartPosition
        get() = paged.page.zoomStart.position(
            auto = when (this) {
                is L2RPagerViewer -> ZoomStartPosition.LEFT
                is R2LPagerViewer -> ZoomStartPosition.RIGHT
                else -> ZoomStartPosition.CENTER
            },
        )

    private val pagerItems = PagerItems(isReversed = this is R2LPagerViewer)

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this, pagerItems)

    private val tracker = PagerPageTracker(activity, pagerItems, ::setChaptersInternal) { page, forward ->
        getPageHolder(page)?.onPageSelected(forward)
    }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            tracker.onPageChange(position)
        }

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            updateCurl(position, positionOffset)
        }

        override fun onPageScrollStateChanged(state: Int) {
            tracker.isIdle = state == ViewPager.SCROLL_STATE_IDLE
            if (state == ViewPager.SCROLL_STATE_IDLE) {
                // A shown curl dropping into place gets its soft settle sound
                // (the loop is stopped either way) and its subtle settle haptic.
                if (curlShownThisScroll) {
                    curlSound?.onTurnSettled(general.pageTurnSoundEnabled)
                    if (general.pageTurnHapticEnabled) performCurlSettleHaptic()
                    curlShownThisScroll = false
                }
                curlOverlay?.clearCurl()
                curlSegmentKey = null
                curlLastScrollX = pager.scrollX
                curlScrollDirection = null
            }
        }
    }

    /**
     * The curl overlay drawn on top of the pager during forward turns, if the reader
     * content has been composed yet.
     */
    private var curlOverlay: CurlOverlayView? = null

    /** Orientation last seen by the reader content, for rotation handling. */
    private var lastOrientation = activity.resources.configuration.orientation

    /** Pager scrollX at the previous scroll callback, for scroll-direction detection. */
    private var curlLastScrollX = 0

    /** Last known scroll direction (true = adapter index increasing), held while still. */
    private var curlScrollDirection: Boolean? = null

    /** Key of the curl segment currently shown, so bitmaps are captured once per turn. */
    private var curlSegmentKey: String? = null

    /** Gesture-synced paper audio for the curl, created lazily on the first turn. */
    private var curlSound: CurlSound? = null

    /** Whether a curl segment was shown during the current scroll (for the settle sound). */
    private var curlShownThisScroll = false

    /** Whether this viewer currently shows a two-page spread (landscape, horizontal pager). */
    private fun isLandscapeSpread(): Boolean =
        pager.isHorizontal() &&
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Drives the curl overlay from the pager's scroll callbacks. The curl plays in
     * both directions and both reading directions — no direction falls back to the
     * pager's stock slide while the curl is enabled. Bitmaps are captured once per
     * turn segment from the pager holders themselves.
     *
     * Single page: a forward turn peels the outgoing page off the reading-advance
     * edge (right for LTR, left for R2L); a backward turn peels the returning page
     * back on from the opposite edge. Spread: a one-page advance moves one page
     * across the spread, so the overlay migrates that page from its start half to
     * its destination half over the two neighbouring pages as the base — there is
     * no page that "stays put", and both rest states are exact, so the overlay
     * hands off to the pager at turn end with no jump.
     */
    private fun updateCurl(position: Int, positionOffset: Float) {
        val overlay = curlOverlay
        if (overlay == null) return
        if (tracker.isIdle) {
            // Non-animated jumps (chapter sets, adapter refreshes) report a scroll
            // callback without ever leaving idle; never show the overlay for those,
            // or a stale snapshot would sit on top of the reader.
            overlay.clearCurl()
            curlSegmentKey = null
            curlLastScrollX = pager.scrollX
            curlScrollDirection = null
            return
        }
        if (!pager.isHorizontal() || !general.usePageTransitions || !general.pageCurlEnabled ||
            activity.isScrollingThroughPages
        ) {
            overlay.clearCurl()
            curlSegmentKey = null
            curlLastScrollX = pager.scrollX
            curlScrollDirection = null
            return
        }

        // Scroll direction from scrollX movement, held while the finger is still so
        // a held drag never flips the segment mid-turn. No movement yet means no
        // direction yet: wait for the first real movement instead of guessing.
        val scrollX = pager.scrollX
        val increasing = when {
            scrollX > curlLastScrollX -> true
            scrollX < curlLastScrollX -> false
            else -> curlScrollDirection
        }
        curlLastScrollX = scrollX
        if (increasing == null) return
        curlScrollDirection = increasing

        // Adapter segment being scrolled, and progress 0..1 along it.
        val (outgoingPos, incomingPos, progress) = if (increasing) {
            Triple(position, position + 1, positionOffset)
        } else {
            Triple(position + 1, position, 1f - positionOffset)
        }

        // Reading forward means the adapter index moves with the reading direction:
        // increasing for left-to-right, decreasing for right-to-left.
        val readingForward = if (isReversed) !increasing else increasing

        overlay.depthPercent = general.pageCurlDepthPercent

        val spread = isLandscapeSpread()
        if (spread) {
            // The page present in both rest spreads, on opposite halves: it migrates
            // across, while the pages on either side sit underneath as the base.
            val migratingPos = if (incomingPos > outgoingPos) incomingPos else outgoingPos
            val baseLeftPos = minOf(outgoingPos, incomingPos)
            val baseRightPos = maxOf(outgoingPos, incomingPos) + 1
            val migrateFromRight = incomingPos > outgoingPos
            val key = "S:$baseLeftPos:$migratingPos:$baseRightPos:$migrateFromRight:" +
                "${overlay.width}x${overlay.height}"
            if (key != curlSegmentKey) {
                curlSegmentKey = key
                val leftItem = adapter.items.getOrNull(baseLeftPos)
                val migratingItem = adapter.items.getOrNull(migratingPos)
                val rightItem = adapter.items.getOrNull(baseRightPos)
                if (leftItem !is ReaderPage || migratingItem !is ReaderPage ||
                    rightItem !is ReaderPage
                ) {
                    // Chapter transitions and unlaid-out holders get the stock slide.
                    overlay.clearCurl()
                    return
                }
                val left = captureHolderBitmap(leftItem) ?: run {
                    overlay.clearCurl()
                    return
                }
                val migrating = captureHolderBitmap(migratingItem) ?: run {
                    left.recycle()
                    overlay.clearCurl()
                    return
                }
                val right = captureHolderBitmap(rightItem) ?: run {
                    left.recycle()
                    migrating.recycle()
                    overlay.clearCurl()
                    return
                }
                overlay.setSpreadSegment(
                    leftBase = left,
                    rightBase = right,
                    migrating = migrating,
                    migrateFromRight = migrateFromRight,
                )
                curlShownThisScroll = true
            }
            overlay.progress = progress
        } else {
            // Single page: forward peels the outgoing page off the advance edge;
            // backward peels the returning page back on from the opposite edge,
            // with progress inverted so the page sweeps back into place.
            val (turningPos, revealedPos, peelFromRight, curlProgress) = if (readingForward) {
                Quad(outgoingPos, incomingPos, !isReversed, progress)
            } else {
                Quad(incomingPos, outgoingPos, isReversed, 1f - progress)
            }
            val key = "P:$turningPos:$revealedPos:$peelFromRight:${overlay.width}x${overlay.height}"
            if (key != curlSegmentKey) {
                curlSegmentKey = key
                val turningItem = adapter.items.getOrNull(turningPos)
                val revealedItem = adapter.items.getOrNull(revealedPos)
                if (turningItem !is ReaderPage || revealedItem !is ReaderPage) {
                    // Chapter transitions and unlaid-out holders get the stock slide.
                    overlay.clearCurl()
                    return
                }
                val turning = captureHolderBitmap(turningItem) ?: run {
                    overlay.clearCurl()
                    return
                }
                val revealed = captureHolderBitmap(revealedItem) ?: run {
                    turning.recycle()
                    overlay.clearCurl()
                    return
                }
                overlay.setPeelSegment(
                    turning = turning,
                    revealed = revealed,
                    peelFromRight = peelFromRight,
                )
                curlShownThisScroll = true
            }
            overlay.progress = curlProgress
        }
        // Gesture-synced paper rustle: volume and rate follow the finger through
        // the curl. Only while a curl segment is actually shown.
        if (general.pageTurnSoundEnabled) {
            if (curlSound == null) curlSound = CurlSound(pager.context)
            curlSound?.onCurlProgress(progress, SystemClock.uptimeMillis())
        }
    }

    /** Renders a pager holder's current appearance into a bitmap, or null if unavailable. */
    private fun captureHolderBitmap(page: ReaderPage): Bitmap? {
        val holder = getPageHolder(page) ?: return null
        if (holder.width <= 0 || holder.height <= 0) return null
        val bitmap = Bitmap.createBitmap(holder.width, holder.height, Bitmap.Config.ARGB_8888)
        holder.draw(Canvas(bitmap))
        return bitmap
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        pager.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // Two pages each side: the curl overlay needs the revealed page's holder to
        // already exist when a turn starts (in a landscape spread, two ahead).
        pager.offscreenPageLimit = 2
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        installCurlSpeedScroller()
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = Offset(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            activity.onViewerTap(pos)
        }
        pager.longTapListener = f@{
            if (activity.viewModel.state.value.menuVisible || general.longTapEnabled) {
                val item = adapter.items.getOrNull(pager.currentItem)
                if (item is ReaderPage) {
                    activity.onPageLongTap(item)
                    return@f true
                }
            }
            false
        }

        settingsViewModel.general.collectChanges(scope) { old, new ->
            if (old != null && old.theme != new.theme) {
                refreshAdapter()
            }
        }
        settingsViewModel.simplePaged.collectChanges(scope) { old, new ->
            if (old != null) {
                refreshAdapter()
            }
            if (old?.dualPageSplit != new.dualPageSplit && !new.dualPageSplit) {
                cleanupPageSplit()
            }
        }
        settingsViewModel.paged.collectChanges(scope) { old, new ->
            if (old != null && old.page != new.page) {
                refreshAdapter()
            }
        }
    }

    /**
     * Mihon Gold: one subtle haptic pulse as a curled page lands. Kept deliberately
     * light (a tick on modern devices, a short low-amplitude pulse otherwise) so it
     * stays pleasant across many page turns. Independent of the sound toggle.
     */
    private fun performCurlSettleHaptic() {
        val vibrator = pager.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return
        if (!vibrator.hasVibrator()) return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                vibrator.vibrate(VibrationEffect.createOneShot(25, 60))
            }
            else -> {
                @Suppress("DEPRECATION")
                vibrator.vibrate(25)
            }
        }
    }

    override fun destroy() {
        super.destroy()
        scope.cancel()
        curlSound?.release()
        curlSound = null
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /** The page images' colours, for holders made later - see [LocalPageColorFilter]. */
    var pageColorFilter: ColorFilter? = null
        private set

    @Composable
    override fun Content(modifier: Modifier) {
        val filter = LocalPageColorFilter.current
        val pageFilter = remember(filter) { filter?.asAndroidColorFilter() }
        SideEffect {
            pageColorFilter = pageFilter
            // The pager keeps every page it holds as a child.
            pager.children.filterIsInstance<PagerPageHolder>().forEach { it.pageColorFilter = pageFilter }
        }
        // Rotation handling: the activity survives rotation, so the spread has to be
        // engaged here. Right-to-left spreads are anchored on their right page, so
        // entering landscape shifts one item back to show the spread's left page too.
        // Leaving landscape keeps the spread's left page, the next unread page.
        val orientation = LocalConfiguration.current.orientation
        SideEffect {
            if (orientation != lastOrientation) {
                val wasLandscape = lastOrientation == Configuration.ORIENTATION_LANDSCAPE
                val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
                lastOrientation = orientation
                if (pager.isVisible && pager.isHorizontal()) {
                    if (isReversed && isLandscape && !wasLandscape) {
                        val target = pager.currentItem - 1
                        if (target >= 0 && adapter.items.getOrNull(target) is ReaderPage) {
                            pager.setCurrentItem(target, false)
                        }
                    }
                    adapter.notifyDataSetChanged()
                }
            }
        }
        AndroidView(
            factory = { context ->
                DisallowInterceptBarrier(context).apply {
                    addView(pager)
                    // The curl overlay sits on top of the pager but never consumes
                    // touches, so the pager's drag keeps working underneath it.
                    curlOverlay = CurlOverlayView(context).also { overlay ->
                        overlay.visibility = View.GONE
                        addView(
                            overlay,
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                        )
                    }
                }
            },
            modifier = modifier.mouseWheelPaging(this),
        )
    }

    /**
     * Returns the PagerPageHolder for the provided page
     */
    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance(PagerPageHolder::class.java)
            .firstOrNull { it.item == page }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        tracker.setChapters(chapters)
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        // Remove listener so the change in item doesn't trigger it
        pager.removeOnPageChangeListener(pagerListener)

        val forceTransition = general.alwaysShowChapterTransition ||
            adapter.items.getOrNull(pager.currentItem) is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        // Layout the pager once a chapter is being set
        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            pager.isVisible = true
        }

        pager.addOnPageChangeListener(pagerListener)
        // Manually call onPageChange to update the UI
        tracker.onPageChange(pager.currentItem)
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        var position = adapter.items.indexOf(page)
        // Right-to-left landscape spreads are anchored on their right page: jumping
        // to a page must land on the spread that shows it on the right, i.e. one
        // item back, unless that item is a chapter transition or a page split.
        if (position != -1 && isReversed && page !is InsertPage && isLandscapeSpread()) {
            val shifted = position - 1
            if (shifted >= 0 && adapter.items.getOrNull(shifted) is ReaderPage) {
                position = shifted
            }
        }
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, true)
            // manually call onPageChange since ViewPager listener is not triggered in this case
            if (currentPosition == position) {
                tracker.onPageChange(position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    /**
     * Whether pages run right to left, so moving forward goes left.
     */
    protected open val isReversed = false

    override fun moveForward() = if (isReversed) moveLeft() else moveRight()

    override fun moveBackward() = if (isReversed) moveRight() else moveLeft()

    /**
     * Moves to the page at the right.
     */
    private fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            val holder = (tracker.currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && paged.navigateToPan && holder.canPanRight()) {
                holder.panRight()
            } else {
                pager.setCurrentItem(pager.currentItem + 1, general.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the left.
     */
    private fun moveLeft() {
        if (pager.currentItem != 0) {
            val holder = (tracker.currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && paged.navigateToPan && holder.canPanLeft()) {
                holder.panLeft()
            } else {
                pager.setCurrentItem(pager.currentItem - 1, general.usePageTransitions)
            }
        }
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        activity.runOnUiThread {
            // Need to insert on UI thread else images will go blank
            adapter.onPageSplit(currentPage, newPage)
        }
    }

    private fun cleanupPageSplit() {
        adapter.cleanupPageSplit()
    }
}

/**
 * Keeps [requestDisallowInterceptTouchEvent] from reaching Compose. Its interop filter cancels the
 * gesture when a child re-allows intercepting mid-drag, which stops a zoomed page from handing the
 * drag to the pager at its edge.
 */
private class DisallowInterceptBarrier(context: Context) : FrameLayout(context) {
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
}

/**
 * Mihon Gold: turn-speed control for the page curl. DirectionalViewPager offers no
 * scroll-duration API, so the pager's smooth-scroll duration is scaled by swapping
 * its scroller for one that divides durations by the speed factor (100 = stock
 * speed, 200 = twice as fast, 50 = half speed). The speed is read fresh on every
 * scroll start, so the settings slider takes effect without reinstalling. Drags
 * still follow the finger; only animated turns (tap zones, flings settling) scale.
 */
private class CurlSpeedScroller(
    context: Context,
    private val speedPercent: () -> Int,
) : Scroller(context, Interpolator { t ->
    // The stock ViewPager quintic ease-out, so the feel stays familiar.
    val u = t - 1f
    u * u * u * u * u + 1f
}) {
    override fun startScroll(startX: Int, startY: Int, dx: Int, dy: Int, duration: Int) {
        val factor = (speedPercent() / 100f).coerceIn(0.25f, 4f)
        super.startScroll(startX, startY, dx, dy, (duration / factor).toInt().coerceAtLeast(1))
    }
}

/**
 * Installs the [CurlSpeedScroller] on this viewer's pager. DirectionalViewPager
 * keeps its scroller in a private field of the pinned 1.0.0 artifact (protected
 * from minification by a keep rule in proguard-rules.pro); if the swap ever
 * fails, turns simply run at stock speed and everything else still works.
 */
private fun PagerViewer.installCurlSpeedScroller() {
    try {
        val field = androidx.viewpager.widget.DirectionalViewPager::class.java
            .getDeclaredField("mScroller")
        field.isAccessible = true
        field.set(pager, CurlSpeedScroller(pager.context) { general.pageCurlSpeedPercent })
    } catch (e: Exception) {
        logcat { "Could not install curl speed scroller, using stock speed: $e" }
    }
}

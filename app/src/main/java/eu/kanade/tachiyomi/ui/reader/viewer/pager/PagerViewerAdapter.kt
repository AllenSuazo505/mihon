package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class PagerViewerAdapter(
    private val viewer: PagerViewer,
    private val pagerItems: PagerItems,
) : ViewPagerAdapter() {

    /**
     * List of currently set items.
     */
    val items: List<Any>
        get() = pagerItems.items

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val update = pagerItems.setChapters(chapters, forceTransition, viewer.pager.currentItem)
        notifyDataSetChanged()

        update.position?.let { viewer.pager.setCurrentItem(it, false) }

        // Will skip insert page otherwise
        update.insertPage?.let(viewer::moveToPage)
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return items.size
    }

    /**
     * Two-page spread in landscape: horizontal pages take half the pager width, so
     * two sit side by side and each forward turn moves the spread by one page, like
     * a book. Chapter transitions stay full-width. Portrait and vertical pagers are
     * unchanged.
     */
    override fun getPageWidth(position: Int): Float {
        if (!viewer.pager.isHorizontal()) return 1f
        if (viewer.activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return 1f
        }
        if (items.getOrNull(position) is ChapterTransition) return 1f
        return 0.5f
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        return when (val item = items[position]) {
            is ReaderPage -> PagerPageHolder(readerThemedContext, viewer, item)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = items.indexOf(view.item)
            if (position != -1) {
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (pagerItems.onPageSplit(currentPage, newPage)) {
            notifyDataSetChanged()
        }
    }

    fun cleanupPageSplit() {
        pagerItems.cleanupPageSplit()
        notifyDataSetChanged()
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }
}

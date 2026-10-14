package zip.arcanum.arcanum.gallery

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The list the viewer was opened from, handed over at the moment of the tap (#151).
 *
 * The viewer used to work out its swipe order for itself, from the sort settings. That
 * agreed with the screen only as long as the sort was the whole story, and it never was:
 * the Gallery's folder filter, its photos/videos filter and the search box, and the Files
 * browser's search and hidden files all narrowed what was on screen while the viewer
 * swiped through everything. Whatever the screen is showing is now passed over as it is,
 * so the two cannot disagree however many ways of narrowing a list are added later.
 *
 * Paths rather than ids, because the Files browser lists the vault itself and knows no
 * index ids. Held in memory only: after a process death it is empty, and the viewer falls
 * back to working the order out, which is what it did before.
 */
@Singleton
class ViewerOrder @Inject constructor() {
    // One reference, so a reader never sees one vault's id with another vault's list.
    @Volatile private var current: Pair<String, List<String>> = "" to emptyList()

    fun set(containerId: String, paths: List<String>) {
        current = containerId to paths
    }

    /**
     * The handed-over list, if it is the one [path] was opened from. A list from another
     * vault, or one that does not hold the file at all (the editor's saved copy, say),
     * is not this viewer's list.
     */
    fun forFile(containerId: String, path: String): List<String>? {
        val (id, list) = current
        return list.takeIf { id == containerId && path in list }
    }
}

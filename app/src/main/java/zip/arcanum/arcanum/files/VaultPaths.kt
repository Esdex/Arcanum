package zip.arcanum.arcanum.files

import zip.arcanum.crypto.NativeFileInfo

/** Path arithmetic inside a vault that has to know what the entries are. */
internal object VaultPaths {

    /** Linux gives up after 40 links in one lookup (MAXSYMLINKS); so does this. */
    const val MAX_LINK_HOPS = 40

    /**
     * The path [path] really names, with every symbolic link on the way followed - folders
     * and the last name alike, like realpath(3) (#188). A relative target is read from the
     * folder the link is in, an absolute one from the vault's root.
     *
     * Null when the path cannot be accounted for: a name [entryAt] does not know, a link with
     * no target, or more than [MAX_LINK_HOPS] links, which is how a ring of them ends.
     *
     * [entryAt] looks one name up in one folder, the folder given as an absolute path.
     */
    fun resolve(path: String, entryAt: (dir: String, name: String) -> NativeFileInfo?): String? {
        val pending = ArrayDeque(path.split('/').filter { it.isNotEmpty() })
        val done = mutableListOf<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name == ".") continue
            if (name == "..") { done.removeLastOrNull(); continue }
            val entry = entryAt("/" + done.joinToString("/"), name) ?: return null
            if (entry.isSymlink) {
                if (++hops > MAX_LINK_HOPS) return null
                val target = entry.linkTarget ?: return null
                if (target.startsWith("/")) done.clear()
                target.split('/').filter { it.isNotEmpty() }.asReversed().forEach { pending.addFirst(it) }
            } else {
                done += name
            }
        }
        return "/" + done.joinToString("/")
    }
}

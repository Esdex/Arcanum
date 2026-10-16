package zip.arcanum.arcanum.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import zip.arcanum.crypto.NativeFileInfo

class VaultPathsTest {

    // A small vault: entries by their full path, looked up the way a listing would answer.
    private val entries = mutableMapOf<String, NativeFileInfo>()

    private fun put(path: String, kind: Int, target: String? = null) {
        entries[path] = NativeFileInfo(
            name = path.substringAfterLast('/'), path = path, size = 0L,
            isDirectory = kind == NativeFileInfo.KIND_DIRECTORY, lastModified = 0L,
            kind = kind, linkTarget = target
        )
    }
    private fun dir(path: String)  = put(path, NativeFileInfo.KIND_DIRECTORY)
    private fun file(path: String) = put(path, NativeFileInfo.KIND_REGULAR)
    private fun link(path: String, target: String) = put(path, NativeFileInfo.KIND_SYMLINK, target)

    private fun resolve(path: String) = VaultPaths.resolve(path) { d, name ->
        entries[if (d == "/") "/$name" else "$d/$name"]
    }

    init {
        dir("/Photos"); dir("/Photos/2026"); file("/Photos/2026/a.jpg")
        dir("/Links")
    }

    @Test
    fun aPathWithoutLinksIsItself() {
        assertEquals("/Photos/2026/a.jpg", resolve("/Photos/2026/a.jpg"))
        assertEquals("/", resolve("/"))
    }

    @Test
    fun absoluteFolderLink() {
        link("/Links/year", "/Photos/2026")
        assertEquals("/Photos/2026/a.jpg", resolve("/Links/year/a.jpg"))
    }

    @Test
    fun relativeFolderLinkIsReadFromTheLinksOwnFolder() {
        link("/Links/year", "../Photos/2026")
        assertEquals("/Photos/2026/a.jpg", resolve("/Links/year/a.jpg"))
        link("/Photos/same", "2026")
        assertEquals("/Photos/2026/a.jpg", resolve("/Photos/same/a.jpg"))
    }

    @Test
    fun linkToAFileAtTheEnd() {
        link("/Links/pic.jpg", "/Photos/2026/a.jpg")
        assertEquals("/Photos/2026/a.jpg", resolve("/Links/pic.jpg"))
    }

    @Test
    fun aChainOfLinksIsFollowedToTheEnd() {
        link("/Links/one", "two")
        link("/Links/two", "/Photos")
        assertEquals("/Photos/2026/a.jpg", resolve("/Links/one/2026/a.jpg"))
    }

    @Test
    fun aRingOfLinksGivesUp() {
        link("/Links/a", "b")
        link("/Links/b", "a")
        assertNull(resolve("/Links/a/x.jpg"))
    }

    @Test
    fun aBrokenLinkOrAnUnknownNameGivesUp() {
        link("/Links/gone", "/Nowhere")
        assertNull(resolve("/Links/gone/a.jpg"))
        assertNull(resolve("/Photos/missing.jpg"))
    }

    @Test
    fun dotsAreTakenAsTheyCome() {
        assertEquals("/Photos/2026/a.jpg", resolve("/Photos/./2026/../2026/a.jpg"))
        assertEquals("/Photos", resolve("/../Photos"))
    }
}

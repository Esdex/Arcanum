package zip.arcanum.arcanum.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import zip.arcanum.crypto.NativeFileInfo

/** A closed vault's paths do not stay behind in the lists handed to the viewer and the player. */
class ClosedVaultListsTest {

    @Test
    fun viewerOrderForgetsOnlyTheClosedVault() {
        val order = ViewerOrder()
        order.set("a", listOf("/x.jpg"))
        order.clear("b")
        assertEquals(listOf("/x.jpg"), order.forFile("a", "/x.jpg"))
        order.clear("a")
        assertNull(order.forFile("a", "/x.jpg"))
    }

    @Test
    fun audioQueueForgetsOnlyTheClosedVault() {
        val queue = AudioPlayerQueue()
        queue.set("a", listOf(NativeFileInfo("s.mp3", "/s.mp3", 1L, false, 0L)), 0)
        queue.clear("b")
        assertEquals(1, queue.playlist.size)
        queue.clear("a")
        assertEquals(0, queue.playlist.size)
        assertEquals("", queue.containerId)
    }
}

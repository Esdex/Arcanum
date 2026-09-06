package zip.arcanum.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import zip.arcanum.core.security.IdleMonitor
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VaultFileDescriptor
import zip.arcanum.crypto.VeraCryptEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Whether a PDF inside a vault can be rendered without ever writing it out (#137).
 *
 * The system renderer, `android.graphics.pdf.PdfRenderer`, wants a seekable descriptor,
 * and a file inside a vault has none: it is reachable only through the native layer, a
 * range at a time. Writing a decrypted copy to the cache to get a descriptor is the one
 * thing we will not do - that path was deleted on purpose with #103.
 *
 * What this checks is the way round it: `StorageManager.openProxyFileDescriptor`, which
 * hands back a real descriptor whose reads arrive as callbacks. The provider already
 * serves Open with through one; the question here is whether pdfium is happy on the other
 * end of it, since "should work" is not "does work".
 *
 * The fixture is made on the device by `PdfDocument`, so nothing has to be pushed: two
 * pages, each with a red block on white at a known place. Rendering the pages back and
 * finding that block is what says the bytes travelled correctly.
 */
@RunWith(AndroidJUnit4::class)
class PdfFromVaultTest {

    private val engine = VeraCryptEngine(IdleMonitor())

    @Test
    fun aPdfInsideAVaultRendersThroughAProxyDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null) ?: error("no external files dir")
        val volume = File(dir, "pdf-spike.hc")
        volume.delete()

        val created = runBlocking {
            engine.createContainer(
                path = volume.absolutePath, sizeBytes = 16L * 1024 * 1024,
                password = PASSWORD, algorithm = 0, hashAlgorithm = 0, filesystem = 0,
                quickFormat = true, entropyBytes = ByteArray(32)
            )
        }
        assertTrue("the vault was not created: $created", created is CryptoResult.Success)

        val pdf = twoPagePdf()
        android.util.Log.i(TAG, "fixture is ${pdf.size} bytes")

        val handle = runBlocking {
            when (val r = engine.mountContainer(path = volume.absolutePath, password = PASSWORD)) {
                is CryptoResult.Success -> r.value
                is CryptoResult.Failure -> throw AssertionError("would not mount: ${r.error}")
            }
        }
        assertEquals(
            "the PDF would not go into the vault",
            VeraCryptEngine.ERR_OK, engine.writeFile(handle, PATH, pdf, 0L)
        )

        // Read back through the very class the viewer uses, so this is the shipped path and
        // not a copy of it.
        val size = pdf.size.toLong()
        val pages: Int
        val hits = IntArray(2)
        try {
            VaultFileDescriptor(context, engine, PATH, size) { handle }.use { vault ->
                PdfRenderer(vault.descriptor).use { renderer ->
                    pages = renderer.pageCount
                    for (index in 0 until minOf(pages, 2)) {
                        renderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                page.width, page.height, Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            hits[index] = bitmap.getPixel(BLOCK_X, BLOCK_Y)
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            runBlocking { engine.unmountContainer(handle) }
            volume.delete()
        }

        android.util.Log.i(TAG, "pages=$pages, file is $size bytes")
        assertEquals("the renderer did not see both pages", 2, pages)
        // Rendered without an alpha channel of its own, so compare the colour only.
        assertEquals("page 1 did not carry its block", RED, hits[0] or ALPHA)
        assertEquals("page 2 did not carry its block", RED, hits[1] or ALPHA)
    }

    /**
     * How much of a large PDF has to travel through the vault before a page appears.
     *
     * This is the number the viewer's design hangs on. If pdfium reads the whole file to
     * open it, a 300 MB document costs 300 MB of decryption before anything is drawn and
     * the screen needs a size cap like the one GIFs have. If it seeks - the cross-reference
     * table is at the END of a PDF, which is exactly what a seekable descriptor is for -
     * then a big document costs no more than a small one until its pages are looked at.
     */
    @Test
    fun aLargePdfIsOpenedByReadingOnlyPartOfIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null) ?: error("no external files dir")
        val volume = File(dir, "pdf-spike-big.hc")
        volume.delete()

        val created = runBlocking {
            engine.createContainer(
                path = volume.absolutePath, sizeBytes = 64L * 1024 * 1024,
                password = PASSWORD, algorithm = 0, hashAlgorithm = 0, filesystem = 1,
                quickFormat = true, entropyBytes = ByteArray(32)
            )
        }
        assertTrue("the vault was not created: $created", created is CryptoResult.Success)

        val handle = runBlocking {
            when (val r = engine.mountContainer(path = volume.absolutePath, password = PASSWORD)) {
                is CryptoResult.Success -> r.value
                is CryptoResult.Failure -> throw AssertionError("would not mount: ${r.error}")
            }
        }

        // Written straight into the vault as it is generated, so nothing the size of the
        // document is ever held in memory. PdfDocument is no use here: it re-encodes what it
        // is given and a page of noise came out 33 KB, which measures nothing. This is a
        // plain PDF written by hand, one uncompressed content stream of about a megabyte per
        // page, so each page costs real bytes that only that page needs.
        val sink = VaultSink(engine, handle, BIG_PATH)
        writeBigPdf(sink, BIG_PAGES, BYTES_PER_PAGE)
        val size = sink.written

        val bytesRead = AtomicLong()
        val thread = HandlerThread("pdf-spike-big").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = size
            override fun onRead(offset: Long, requested: Int, data: ByteArray): Int {
                if (offset >= size) return 0
                val want = minOf(requested.toLong(), size - offset).toInt()
                var read = 0
                while (read < want) {
                    val chunk = engine.readFile(handle, BIG_PATH, offset + read, want - read)
                        ?: throw ErrnoException("onRead", OsConstants.EIO)
                    if (chunk.isEmpty()) break
                    chunk.copyInto(data, read)
                    read += chunk.size
                }
                bytesRead.addAndGet(read.toLong())
                return read
            }
            override fun onRelease() {}
        }

        val storage = context.getSystemService(StorageManager::class.java)
        val toOpen: Long
        val toFirstPage: Long
        val toLastPage: Long
        val pages: Int
        try {
            storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper)
            ).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    pages = renderer.pageCount
                    toOpen = bytesRead.get()
                    renderPage(renderer, 0)
                    toFirstPage = bytesRead.get() - toOpen
                    renderPage(renderer, pages - 1)
                    toLastPage = bytesRead.get() - toOpen - toFirstPage
                }
            }
        } finally {
            thread.quitSafely()
            runBlocking { engine.unmountContainer(handle) }
            volume.delete()
        }

        android.util.Log.i(
            TAG,
            "big: file is $size bytes, $pages pages; read to open $toOpen, " +
                "to render page 1 $toFirstPage, to render page $pages $toLastPage"
        )
        assertEquals("the renderer did not see every page", BIG_PAGES, pages)
        assertTrue(
            "opening read the whole document ($toOpen of $size bytes): the viewer needs a size cap",
            toOpen < size / 2
        )
    }

    private fun renderPage(renderer: PdfRenderer, index: Int) {
        renderer.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.recycle()
        }
    }

    /**
     * A multi-page PDF with one uncompressed content stream per page, each about
     * [bytesPerPage] bytes of drawing operations. Written straight out, with the
     * cross-reference table built from the offsets as they go by.
     */
    private fun writeBigPdf(sink: VaultSink, pages: Int, bytesPerPage: Int) {
        val out = java.io.BufferedOutputStream(sink, 1 shl 20)
        val offsets = HashMap<Int, Long>()
        var written = 0L
        fun emit(text: String) {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            out.write(bytes)
            written += bytes.size
        }
        fun startObject(number: Int) {
            out.flush()
            offsets[number] = written
            emit("$number 0 obj\n")
        }

        val op = "1 0 0 rg 10 10 50 50 re f\n"
        val perPage = StringBuilder(bytesPerPage + op.length).apply {
            while (length < bytesPerPage) append(op)
        }.toString()

        emit("%PDF-1.4\n")
        val pageNumbers = (0 until pages).map { 3 + it * 2 }
        startObject(1)
        emit("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        startObject(2)
        emit("<< /Type /Pages /Count $pages /Kids [ " +
            pageNumbers.joinToString(" ") { "$it 0 R" } + " ] >>\nendobj\n")
        for (index in 0 until pages) {
            val pageNumber = pageNumbers[index]
            startObject(pageNumber)
            emit("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] " +
                "/Contents ${pageNumber + 1} 0 R /Resources << >> >>\nendobj\n")
            startObject(pageNumber + 1)
            emit("<< /Length ${perPage.length} >>\nstream\n")
            emit(perPage)
            emit("\nendstream\nendobj\n")
        }
        out.flush()
        val xrefAt = written
        val last = 2 + pages * 2
        emit("xref\n0 ${last + 1}\n0000000000 65535 f \n")
        for (number in 1..last) {
            emit(String.format("%010d 00000 n \n", offsets.getValue(number)))
        }
        emit("trailer\n<< /Size ${last + 1} /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        out.flush()
    }

    /** Writes into a file in the vault as it goes, so nothing large is held in memory. */
    private class VaultSink(
        private val engine: VeraCryptEngine,
        private val handle: Long,
        private val path: String
    ) : java.io.OutputStream() {
        var written = 0L
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            val result = engine.writeAt(handle, path, slice, written)
            if (result != VeraCryptEngine.ERR_OK) error("write into the vault failed: $result")
            written += len
        }
    }

    /** Two pages, each with a red block covering the point the test samples. */
    private fun twoPagePdf(): ByteArray {
        val document = PdfDocument()
        repeat(2) { index ->
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
            val page = document.startPage(info)
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawRect(
                20f, 20f, (PAGE_W - 20).toFloat(), (PAGE_H / 2).toFloat(),
                Paint().apply { color = RED }
            )
            document.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "PDFSPIKE"
        const val PASSWORD = "test1234"
        const val PATH = "/spike.pdf"
        const val PAGE_W = 200
        const val PAGE_H = 300
        const val BLOCK_X = PAGE_W / 2
        const val BLOCK_Y = PAGE_H / 4
        const val RED = Color.RED
        const val ALPHA = 0xFF000000.toInt()
        const val BIG_PATH = "/spike-big.pdf"
        const val BIG_PAGES = 16
        /** About a megabyte of drawing operations per page, so 16 MB in all. */
        const val BYTES_PER_PAGE = 1 shl 20
    }
}

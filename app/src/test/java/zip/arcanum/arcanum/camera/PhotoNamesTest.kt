package zip.arcanum.arcanum.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class PhotoNamesTest {

    private val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 23, 18, 30, 12) }.time

    @Test
    fun aPhotoIsNamedAfterTheSecondItWasTaken() {
        assertEquals("IMG_20261023_183012.jpg", photoName(at, emptySet()))
    }

    @Test
    fun aNameInUseGetsTheNextFreeNumber() {
        assertEquals("IMG_20261023_183012_1.jpg", photoName(at, setOf("IMG_20261023_183012.jpg")))
        assertEquals("IMG_20261023_183012_2.jpg",
            photoName(at, setOf("IMG_20261023_183012.jpg", "IMG_20261023_183012_1.jpg", "other.jpg")))
    }

    @Test
    fun namesClashWhateverTheirCase() {
        // FAT and exFAT see these as one name.
        assertEquals("IMG_20261023_183012_1.jpg", photoName(at, setOf("img_20261023_183012.JPG")))
    }
}

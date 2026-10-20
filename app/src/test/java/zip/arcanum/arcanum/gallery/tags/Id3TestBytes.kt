package zip.arcanum.arcanum.gallery.tags

import java.io.ByteArrayOutputStream

// Tags built byte by byte for the tests, so that no fixture is someone's recording.

internal class F(val id: String, val body: ByteArray, val flags: Int = 0)

internal fun syncsafe(n: Int) = byteArrayOf(
    (n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte()
)
internal fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

internal fun tag(major: Int, vararg frames: F, padding: Int = 0, flags: Int = 0, extended: ByteArray = ByteArray(0)): ByteArray {
    val body = ByteArrayOutputStream()
    body.write(extended)
    for (f in frames) {
        body.write(f.id.toByteArray(Charsets.ISO_8859_1))
        body.write(if (major == 4) syncsafe(f.body.size) else be32(f.body.size))
        body.write(byteArrayOf((f.flags shr 8).toByte(), f.flags.toByte()))
        body.write(f.body)
    }
    body.write(ByteArray(padding))
    val b = body.toByteArray()
    return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, flags.toByte()) +
        syncsafe(b.size) + b + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)   // a frame of audio
}

internal fun utf16(s: String, terminated: Boolean = true) =
    byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE) + if (terminated) byteArrayOf(0, 0) else ByteArray(0)
internal fun text16(s: String, terminated: Boolean = true) = byteArrayOf(1) + utf16(s, terminated)
internal fun text8(vararg values: String) = byteArrayOf(3) + values.joinToString("\u0000").toByteArray(Charsets.UTF_8)
internal fun latin1(s: String) = byteArrayOf(0) + s.toByteArray(Charsets.ISO_8859_1)

internal fun lyrics16(lang: String, description: String, text: String) =
    byteArrayOf(1) + lang.toByteArray(Charsets.ISO_8859_1) + utf16(description) + utf16(text, terminated = false)

internal fun picture(type: Int, data: ByteArray, description: String = "", enc: Int = 0): ByteArray {
    val desc = if (enc == 1) utf16(description) else description.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
    return byteArrayOf(enc.toByte()) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, type.toByte()) + desc + data
}

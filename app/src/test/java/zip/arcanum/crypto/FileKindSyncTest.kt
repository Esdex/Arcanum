package zip.arcanum.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-checks the native ARC_KIND_* values (app/src/main/cpp/arcanum_file_kind.h)
 * against NativeFileInfo.Companion's KIND_* constants.
 *
 * Built the same way as [ErrorCodeSyncTest] and for the same reason: the kind
 * crosses the JNI boundary as a plain jint, so nothing at compile time enforces
 * that the two sides agree. Drifting here does not crash — it relabels, so a
 * folder lists as a link or a link lists as an ordinary file, which is exactly
 * the class of bug #163 was about in the first place.
 *
 * Each Kotlin constant is referenced by name rather than by reflection, so
 * renaming one breaks the compilation of this test instead of silently skipping
 * the check.
 */
class FileKindSyncTest {

    /** Hand-mirrors NativeFileInfo.Companion's KIND_* constants by name. */
    private val kotlinKinds: Map<String, Int> = linkedMapOf(
        "ARC_KIND_REGULAR" to NativeFileInfo.KIND_REGULAR,
        "ARC_KIND_DIRECTORY" to NativeFileInfo.KIND_DIRECTORY,
        "ARC_KIND_SYMLINK" to NativeFileInfo.KIND_SYMLINK,
        "ARC_KIND_SPECIAL" to NativeFileInfo.KIND_SPECIAL,
    )

    @Test
    fun nativeFileKinds_matchKotlinCompanionConstants() {
        val native = parseKindDefines(locateHeader())

        assertTrue(
            "Parsed zero ARC_KIND_* #defines from arcanum_file_kind.h — parsing is " +
                "broken, not just out of sync",
            native.isNotEmpty()
        )

        val onlyInNative = native.keys - kotlinKinds.keys
        val onlyInKotlin = kotlinKinds.keys - native.keys
        assertTrue(
            "ARC_KIND_* defined in arcanum_file_kind.h but missing from " +
                "NativeFileInfo.Companion: $onlyInNative",
            onlyInNative.isEmpty()
        )
        assertTrue(
            "KIND_* defined in NativeFileInfo.Companion but missing from " +
                "arcanum_file_kind.h: $onlyInKotlin",
            onlyInKotlin.isEmpty()
        )
        assertEquals(
            "kind count must match between arcanum_file_kind.h and " +
                "NativeFileInfo.Companion",
            kotlinKinds.size, native.size
        )

        for ((name, kotlinValue) in kotlinKinds) {
            assertEquals(
                "$name mismatch: arcanum_file_kind.h defines ${native[name]} but " +
                    "NativeFileInfo has $kotlinValue",
                kotlinValue, native.getValue(name)
            )
        }
    }

    private fun locateHeader(): File {
        val candidates = listOf(
            File("src/main/cpp/arcanum_file_kind.h"),
            File("app/src/main/cpp/arcanum_file_kind.h"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "Could not find arcanum_file_kind.h. Tried: " +
                    candidates.joinToString(", ") { it.path } +
                    " (cwd=${File(".").absolutePath})"
            )
    }

    /** Parses `#define ARC_KIND_<NAME> <value>` lines; trailing comments ignored. */
    private fun parseKindDefines(file: File): Map<String, Int> {
        val pattern = Regex("""^#define\s+(ARC_KIND_\w+)\s+\(?(-?\d+)\)?""")
        val result = linkedMapOf<String, Int>()
        file.forEachLine { raw ->
            val line = raw.trim()
            if (!line.startsWith("#define")) return@forEachLine
            val m = pattern.find(line) ?: return@forEachLine
            val (name, value) = m.destructured
            result[name] = value.toInt()
        }
        return result
    }
}

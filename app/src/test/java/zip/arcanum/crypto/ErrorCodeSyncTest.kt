package zip.arcanum.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-checks the native ERR_* error codes (app/src/main/cpp/arcanum_errors.h)
 * against VeraCryptEngine.Companion's ERR_* constants.
 *
 * arcanum_errors.h is the single source of truth on the native side (see its
 * header comment) — the JNI layer returns these as plain jint/jlong values,
 * so nothing at compile time enforces that the Kotlin companion object stays
 * in sync with it. This test parses the header directly and compares every
 * value against the Kotlin side, referencing each Kotlin constant by name
 * (e.g. `VeraCryptEngine.ERR_WRONG_PASSWORD`) rather than via reflection, so
 * renaming a constant on either side breaks *compilation* of this test file
 * instead of silently skipping the check. Counts are compared in both
 * directions so a code added to only one side fails the test.
 */
class ErrorCodeSyncTest {

    /** Hand-mirrors VeraCryptEngine.Companion's ERR_* constants by name. */
    private val kotlinErrorCodes: Map<String, Int> = linkedMapOf(
        "ERR_OK" to VeraCryptEngine.ERR_OK,
        "ERR_FILE" to VeraCryptEngine.ERR_FILE,
        "ERR_READ" to VeraCryptEngine.ERR_READ,
        "ERR_WRONG_PASSWORD" to VeraCryptEngine.ERR_WRONG_PASSWORD,
        "ERR_UNSUPPORTED" to VeraCryptEngine.ERR_UNSUPPORTED,
        "ERR_NO_SPACE" to VeraCryptEngine.ERR_NO_SPACE,
        "ERR_NO_SLOT" to VeraCryptEngine.ERR_NO_SLOT,
        "ERR_FS" to VeraCryptEngine.ERR_FS,
        "ERR_RAND" to VeraCryptEngine.ERR_RAND,
        "ERR_HIDDEN_BOUNDARY" to VeraCryptEngine.ERR_HIDDEN_BOUNDARY,
        "ERR_READ_ONLY" to VeraCryptEngine.ERR_READ_ONLY,
        "ERR_DIR_FULL" to VeraCryptEngine.ERR_DIR_FULL,
    )

    @Test
    fun nativeErrorCodes_matchKotlinCompanionConstants() {
        val nativeCodes = parseErrorDefines(locateHeader())

        assertTrue(
            "Parsed zero ERR_* #defines from arcanum_errors.h — parsing is broken, " +
                "not just out of sync",
            nativeCodes.isNotEmpty()
        )

        val onlyInNative = nativeCodes.keys - kotlinErrorCodes.keys
        val onlyInKotlin = kotlinErrorCodes.keys - nativeCodes.keys
        assertTrue(
            "ERR_* codes defined in arcanum_errors.h but missing from " +
                "VeraCryptEngine.Companion: $onlyInNative",
            onlyInNative.isEmpty()
        )
        assertTrue(
            "ERR_* codes defined in VeraCryptEngine.Companion but missing from " +
                "arcanum_errors.h: $onlyInKotlin",
            onlyInKotlin.isEmpty()
        )
        assertEquals(
            "ERR_* code count must match between arcanum_errors.h and " +
                "VeraCryptEngine.Companion",
            kotlinErrorCodes.size, nativeCodes.size
        )

        for ((name, kotlinValue) in kotlinErrorCodes) {
            val nativeValue = nativeCodes.getValue(name)
            assertEquals(
                "$name mismatch: arcanum_errors.h defines $nativeValue but " +
                    "VeraCryptEngine.Companion.$name is $kotlinValue",
                kotlinValue, nativeValue
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Gradle runs JVM unit tests with the app module directory as the working
     * directory, so "src/main/cpp/arcanum_errors.h" is the expected path — but
     * this doesn't assume it; it also tries the path relative to a repo-root
     * working directory, and fails with a clear message listing every path it
     * tried (plus the actual cwd) if neither exists, rather than a bare
     * FileNotFoundException.
     */
    private fun locateHeader(): File {
        val candidates = listOf(
            File("src/main/cpp/arcanum_errors.h"),
            File("app/src/main/cpp/arcanum_errors.h"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "Could not find arcanum_errors.h. Tried: " +
                    candidates.joinToString(", ") { it.path } +
                    " (cwd=${File(".").absolutePath})"
            )
    }

    /** Parses `#define ERR_<NAME> <value>` lines; value may be negative and/or
     *  parenthesized (e.g. `-10` or `(-10)`). Trailing comments are ignored. */
    private fun parseErrorDefines(file: File): Map<String, Int> {
        val definePattern = Regex("""^#define\s+(ERR_\w+)\s+\(?(-?\d+)\)?""")
        val result = linkedMapOf<String, Int>()
        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("#define")) return@forEachLine
            val match = definePattern.find(line) ?: return@forEachLine
            val (name, valueStr) = match.destructured
            result[name] = valueStr.toInt()
        }
        return result
    }
}

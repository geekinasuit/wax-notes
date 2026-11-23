package com.geekinasuit.waxnotes.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Scans the commonMain sources named in KMP_COMMON_MAIN, which kmp_module sets. */
class CommonMainGuardTest {
  @Test
  fun commonMainReferencesNoPlatform() {
    val files = System.getenv("KMP_COMMON_MAIN").orEmpty().split(",").filter { it.isNotEmpty() }
    assertTrue(files.isNotEmpty(), "KMP_COMMON_MAIN names no files, so nothing would be scanned")

    val violations =
      files.flatMap { path ->
        // A missing file throws here: an unreadable source must not pass.
        PlatformReferences.find(File(path).readText()).map { "$path:${it.line}: ${it.text}" }
      }
    if (violations.isNotEmpty()) {
      fail("commonMain must not reference the JVM or Android:\n" + violations.joinToString("\n"))
    }
  }
}

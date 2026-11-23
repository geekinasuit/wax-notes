package com.geekinasuit.waxnotes.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformReferencesTest {
  private fun assertFlagged(vararg sources: String) {
    for (source in sources) {
      assertTrue(PlatformReferences.find(source).isNotEmpty(), "should be flagged: $source")
    }
  }

  private fun assertAllowed(vararg sources: String) {
    for (source in sources) {
      assertEquals(emptyList(), PlatformReferences.find(source), "should be allowed: $source")
    }
  }

  @Test
  fun flagsPlatformImports() =
    assertFlagged(
      "import java.util.UUID",
      "import javax.crypto.Cipher",
      "import android.content.Context",
      "import `java`.util.UUID",
    )

  @Test
  fun flagsFullyQualifiedReferences() =
    assertFlagged(
      "val id = java.util.UUID.randomUUID()",
      "val c: android.content.Context? = null",
      "@java.lang.Deprecated fun f() {}",
      "val id = java\n  .util.UUID.randomUUID()",
    )

  @Test
  fun flagsImplicitJavaLang() =
    assertFlagged(
      "val now = System.currentTimeMillis()",
      "val clock = System::nanoTime",
      "Thread { }.start()",
      "val t: Thread? = null",
      "val local = ThreadLocal<Int>()",
      "val r = Runnable { }",
      "Math.abs(-1)",
      "Integer.parseInt(\"1\")",
      "Runtime.getRuntime()",
      "Class.forName(\"x\")",
    )

  @Test
  fun flagsTheEndOfARange() =
    assertFlagged(
      "0L..System.currentTimeMillis()",
      "0..java.util.Random().nextInt()",
      "0..Integer.MAX_VALUE",
    )

  @Test
  fun flagsJvmOnlyStandardLibrary() =
    assertFlagged(
      "@Synchronized fun f() {}",
      "@get:Synchronized val x = 1",
      "synchronized(lock) { }",
      "String.format(\"%d\", 1)",
      "\"%d\".format(1)",
      "val c = Foo::class.java",
      "val c = foo.javaClass",
    )

  @Test
  fun flagsCodeInsideStringTemplates() = assertFlagged("val s = \"\${System.nanoTime()}\"")

  @Test
  fun countsComments() = assertFlagged("// see java.util.UUID", "/** Like System.nanoTime. */")

  @Test
  fun allowsCommonCode() =
    assertAllowed(
      "import kotlin.jvm.JvmInline",
      "import androidx.compose.runtime.Composable",
      "import kotlinx.coroutines.flow.Flow",
      "val now = Clock.System.now()",
      "import kotlin.time.Clock.System as SystemClock\nval now = SystemClock.now()",
      "import kotlinx.coroutines.Runnable as Task\nval t: Task? = null",
      "import kotlinx.atomicfu.locks.synchronized as locked\nfun f() = locked(l) { }",
      "val r = 0..Clock.System.now()",
      "import com.example.java.Thing",
      "import com.example.android.Thing",
      "// The operating System. A fresh sentence.",
      "val javascript = 1",
      "val c = Foo::class",
      "date.format(pattern)",
    )

  @Test
  fun reportsTheLine() {
    assertEquals(
      listOf(PlatformReference(3, "java.u")),
      PlatformReferences.find("package p\n\nimport java.util.UUID\n"),
    )
  }
}

package com.geekinasuit.waxnotes.build

/** A reference to the JVM or Android platform found in a source file. */
data class PlatformReference(val line: Int, val text: String)

/**
 * Finds references to the JVM or Android platform in Kotlin source text.
 *
 * A heuristic, not a compile: it matches text, and comments and strings count too, because telling
 * them from code (string templates hold code) would need a lexer whose mistakes hide violations.
 * A false positive in prose is fixed by rewording it; in code, by qualifying the name or importing it
 * under an alias.
 */
object PlatformReferences {
  // Not preceded by a word character or a dot, so `my.java.thing` and `Clock.System` are not matches.
  // The dots of a range are not member access: `0..System.nanoTime()` is a match.
  private const val ROOT = """(?:(?<![\w.])|(?<=\.\.))"""

  private val patterns =
    listOf(
      // Packages, imported or fully qualified. `androidx` is allowed: Compose Multiplatform uses it.
      """$ROOT(?:java|javax|android)\s*\.\s*\w""",
      // Implicitly imported java.lang types, where the bare name is also an English word. A space and
      // a capital after the dot is a new sentence.
      """$ROOT(?:System|Math|Integer|Runtime)\s*(?:\.[a-zA-Z]|\.\s*[a-z]|::)""",
      """${ROOT}Class\s*\.\s*forName""",
      // Implicitly imported java.lang types, any mention.
      """$ROOT(?:Thread|ThreadLocal|Runnable|ProcessBuilder|StringBuffer)\b""",
      // JVM-only parts of the Kotlin standard library.
      """@(?:\w+:)?Synchronized\b""",
      """${ROOT}synchronized\s*\(""",
      """\bString\s*\.\s*format\b""",
      """"\s*\.\s*format\s*\(""",
      """::\s*class\s*\.\s*java\w*""",
      """\.\s*javaClass\b|${ROOT}javaClass\b""",
    )
      .map(::Regex)

  fun find(source: String): List<PlatformReference> {
    // Backticks are legal around any identifier: `java`.util is java.util.
    val text = source.replace("`", "")
    return patterns
      .flatMap { it.findAll(text) }
      .sortedBy { it.range.first }
      .map { match ->
        PlatformReference(
          line = text.substring(0, match.range.first).count { it == '\n' } + 1,
          text = match.value,
        )
      }
  }
}

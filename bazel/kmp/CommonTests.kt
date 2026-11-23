package com.geekinasuit.waxnotes.build

import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.model.RunnerBuilder

/** Runs every test class in the jar named in KMP_COMMON_TEST_JAR, which kmp_module sets. */
@RunWith(CommonTestsRunner::class)
class CommonTests

class CommonTestsRunner(suite: Class<*>, builder: RunnerBuilder) :
  Suite(
    builder,
    suite,
    TestClasses.find(File(System.getenv("KMP_COMMON_TEST_JAR"))).toTypedArray(),
  )

/**
 * Finds the classes in a compiled jar that have tests, declared or inherited, wherever their source
 * sits. On the JVM, kotlin.test's `@Test` is JUnit's.
 *
 * A class JUnit can't run, such as an object or an interface, is included, so that JUnit fails it
 * and its tests are not lost silently. An abstract class is left out if another class found
 * inherits its tests.
 */
object TestClasses {
  fun find(jar: File): List<Class<*>> {
    val classes = JarFile(jar).use { open -> open.entries().toList().map { it.name } }
      .filter { it.endsWith(".class") }
      .map { it.removeSuffix(".class").replace('/', '.') }
      .sorted()
      .map { Class.forName(it, false, javaClass.classLoader) }
    val withTests = classes.filter(::hasTests)
    val found = withTests.filterNot { base ->
      isAbstractClass(base) && withTests.any { it != base && base.isAssignableFrom(it) }
    }
    check(found.isNotEmpty()) { "no tests in $jar" }
    return found
  }

  private fun hasTests(type: Class<*>): Boolean =
    generateSequence(type) { it.superclass }
      .flatMap { it.declaredMethods.asSequence() }
      .any { it.isAnnotationPresent(Test::class.java) }

  private fun isAbstractClass(type: Class<*>) =
    Modifier.isAbstract(type.modifiers) && !type.isInterface
}

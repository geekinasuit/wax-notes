package com.geekinasuit.waxnotes.build

import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runners.Suite
import org.junit.runners.model.RunnerBuilder

/** Runs every test class in the jar named in KMP_COMMON_TEST_JAR, which kmp_module sets. */
@RunWith(CommonTestsRunner::class)
class CommonTests

class CommonTestsRunner(suite: Class<*>, builder: RunnerBuilder) :
  Suite(
    FailInterfaces(builder),
    suite,
    TestClasses.find(File(System.getenv("KMP_COMMON_TEST_JAR"))).toTypedArray(),
  )

/**
 * Fails an interface, whatever its annotations: JUnit would skip one with `@Ignore`, and leave one
 * with `@RunWith` to its runner. JUnit reports what this throws as the interface's own failure.
 */
private class FailInterfaces(private val builder: RunnerBuilder) : RunnerBuilder() {
  override fun runnerForClass(type: Class<*>): Runner? {
    require(!type.isInterface) {
      "${type.name}: tests in an interface aren't supported; share them in an abstract class"
    }
    return builder.runnerForClass(type)
  }
}

/**
 * Finds the classes in a compiled jar that have tests, declared or inherited, wherever their source
 * sits. On the JVM, kotlin.test's `@Test` is JUnit's.
 *
 * A class JUnit can't run, such as an object, is included, so that JUnit fails it and its tests are
 * not lost silently. An abstract class is left out if another class found inherits its tests.
 *
 * JUnit doesn't look for `@Test` in an interface, so an override without its own `@Test` would
 * silently not run. An interface in the jar, or one that a class in the jar implements, is therefore
 * included if it declares tests, for the runner to fail.
 */
object TestClasses {
  fun find(jar: File): List<Class<*>> {
    val classes = JarFile(jar).use { open -> open.entries().toList().map { it.name } }
      .filter { it.endsWith(".class") }
      .map { it.removeSuffix(".class").replace('/', '.') }
      .sorted()
      .map { Class.forName(it, false, javaClass.classLoader) }
    val withTests = (classes + interfacesOf(classes)).distinct().filter(::hasTests)
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

  private fun interfacesOf(classes: List<Class<*>>): List<Class<*>> {
    val seen = mutableSetOf<Class<*>>()
    fun visit(type: Class<*>) {
      if (seen.add(type)) (listOfNotNull(type.superclass) + type.interfaces).forEach { visit(it) }
    }
    classes.forEach { visit(it) }
    return seen.filter { it.isInterface }
  }

  private fun isAbstractClass(type: Class<*>) =
    Modifier.isAbstract(type.modifiers) && !type.isInterface
}

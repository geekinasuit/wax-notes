package com.geekinasuit.waxnotes.build

import java.io.File
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

class TestClassesTest {
  private val found by lazy {
    TestClasses.find(File(System.getenv("KMP_COMMON_TEST_JAR"))).map { it.name.substringAfterLast('.') }
  }

  @Test
  fun findsATestInAnyFile() = assertContains(found, "FirstInFile")

  @Test
  fun findsASecondClassInAFile() = assertContains(found, "SecondInFile")

  @Test
  fun findsANestedClass() = assertContains(found, "Outer\$Nested")

  @Test
  fun findsAClassThatInheritsItsTests() = assertContains(found, "InheritsItsTests")

  /** JUnit fails them. Left out, their tests would be lost silently. */
  @Test
  fun includesTestsThatCannotRun() {
    assertContains(found, "BaseNobodyExtends")
    assertContains(found, "Singleton")
  }

  /** The runner fails it, so no test goes unrun because an override in a class lacks `@Test`. */
  @Test
  fun includesAnInterfaceThatDeclaresTests() {
    assertContains(found, "TestInInterface")
    assertContains(found, "ContractInAnotherJar")
    assertContains(found, "ContractOfABase")
  }

  @Test
  fun leavesOutABaseThatRunsInASubclass() = assertFalse("Base" in found)

  @Test
  fun leavesOutClassesWithoutTests() {
    assertFalse("NotATest" in found, "@Test in a comment or string")
    assertFalse("Outer" in found, "only its nested class has tests")
  }

  @Test
  fun failsIfThereAreNoTests() {
    val empty = File.createTempFile("empty", ".jar")
    JarOutputStream(empty.outputStream()).use {}
    assertFails { TestClasses.find(empty) }
  }

  @Test
  fun theSuiteRunsTheClassesFound() {
    val finished = mutableSetOf<String>()
    val failed = mutableSetOf<String>()
    val core = JUnitCore()
    core.addListener(
      object : RunListener() {
        override fun testFinished(description: Description) {
          finished += classOf(description)
        }

        override fun testFailure(failure: Failure) {
          failed += classOf(failure.description)
        }

        // kotlinc copies an interface's method bodies into a nested DefaultImpls class.
        private fun classOf(test: Description) =
          test.className.substringAfterLast('.').removeSuffix("\$DefaultImpls")
      },
    )
    core.run(CommonTests::class.java)

    assertEquals(
      setOf(
        "BaseNobodyExtends",
        "Singleton",
        "TestInInterface",
        "ContractInAnotherJar",
        "IgnoredContract",
        "ContractRunAsAnEmptySuite",
        "ContractOfABase",
      ),
      failed,
    )
    assertEquals(
      setOf(
        "FirstInFile",
        "SecondInFile",
        "Outer\$Nested",
        "InheritsItsTests",
        "ImplementsTestInInterface",
      ),
      finished - failed,
    )
  }
}

package com.geekinasuit.waxnotes.build

import java.io.File
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFails
import kotlin.test.assertFalse

class TestClassesTest {
  private val found by lazy {
    TestClasses.find(File(System.getenv("FIXTURES_JAR"))).map { it.name.substringAfterLast('.') }
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
    assertContains(found, "HasTestBody")
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
}

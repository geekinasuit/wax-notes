package com.geekinasuit.waxnotes.build.fixtures

import kotlin.test.Test

// Test classes in the places a test can sit. The file's name says nothing about tests.

class FirstInFile {
  @Test fun runs() {}
}

class SecondInFile {
  @Test fun runs() {}
}

class Outer {
  class Nested {
    @Test fun runs() {}
  }
}

abstract class Base {
  @Test fun runs() {}
}

class InheritsItsTests : Base()

abstract class BaseNobodyExtends {
  @Test fun cannotRun() {}
}

object Singleton {
  @Test fun cannotRun() {}
}

// Tests in an interface aren't supported, whether or not a class implements it.

interface TestInInterface {
  @Test fun inInterface() {}
}

class ImplementsTestInInterface : TestInInterface {
  @Test fun runs() {}
}

class ImplementsContractInAnotherJar : ContractInAnotherJar {
  override fun declaredInAnotherJar() {}
}

class ImplementsIgnoredContract : IgnoredContract {
  override fun declaredInAnIgnoredContract() {}
}

class ImplementsContractRunAsAnEmptySuite : ContractRunAsAnEmptySuite {
  override fun declaredInAContractRunAsAnEmptySuite() {}
}

class ExtendsABaseInAnotherJar : BaseInAnotherJar()

// Not a test class: here @Test is only in a comment and a string.
class NotATest {
  // @Test fun commentedOut() {}
  fun text() = "@Test fun quoted() {}"
}

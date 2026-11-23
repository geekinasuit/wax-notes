package com.geekinasuit.waxnotes.build.fixtures

import kotlin.test.Ignore
import kotlin.test.Test
import org.junit.runner.RunWith
import org.junit.runners.Suite

// In a jar of their own, as contracts from a module's test_deps would be.

interface ContractInAnotherJar {
  @Test fun declaredInAnotherJar()
}

@Ignore
interface IgnoredContract {
  @Test fun declaredInAnIgnoredContract()
}

@RunWith(Suite::class)
@Suite.SuiteClasses
interface ContractRunAsAnEmptySuite {
  @Test fun declaredInAContractRunAsAnEmptySuite()
}

interface ContractOfABase {
  @Test fun declaredInAContractOfABase()
}

interface ExtendsContractOfABase : ContractOfABase

abstract class BaseInAnotherJar : ExtendsContractOfABase {
  override fun declaredInAContractOfABase() {}
}

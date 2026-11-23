"""Bazel macro for a KMP-disciplined shared module."""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")

_MAIN = "src/commonMain/kotlin/"
_TEST = "src/commonTest/kotlin/"

def module_sources(name, on_error = fail):
    """The calling package's commonMain and commonTest sources, if no BUILD file can hide any.

    Args:
      name: the module's name, for the error.
      on_error: called with the error's message if there is a BUILD file in or below src.

    Returns:
      A struct of the lists main and test; or, after an error, what on_error returned.
    """
    hidden = native.subpackages(include = ["src/**"], allow_empty = True)
    if hidden:
        return on_error(
            "%s: BUILD files in or below src hide sources or tests: %s" % (name, ", ".join(hidden)),
        )
    return struct(
        main = native.glob([_MAIN + "**/*.kt"], allow_empty = False),
        test = native.glob([_TEST + "**/*.kt"], allow_empty = False),
    )

def kmp_module(name, deps = [], test_deps = [], visibility = ["//visibility:public"]):
    """A shared module compiled for the JVM from its commonMain sources only.

    A BUILD file in or below src fails the build: it would make the files under it invisible here,
    so tests there wouldn't run and sources there would be neither compiled nor guarded.

    Tests declared in an interface fail <name>_common_test, even if the interface comes from
    test_deps. Share tests in an abstract class instead.

    Declares:
      <name>                      the library
      <name>_common_test          runs every test class in commonTest; fails if there is none
      <name>_common_main_guard    fails if commonMain references the JVM or Android platform

    Args:
      name: the library's name.
      deps: library dependencies. Each must also publish Kotlin Multiplatform artifacts with iOS,
          which nothing here checks.
      test_deps: extra dependencies of the commonTest sources.
      visibility: the library's visibility.
    """
    srcs = module_sources(name)

    kt_jvm_library(
        name = name,
        srcs = srcs.main,
        visibility = visibility,
        deps = deps,
    )

    kt_jvm_library(
        name = name + "_common_test_lib",
        testonly = True,
        srcs = srcs.test,
        visibility = ["//visibility:private"],
        deps = [
            ":" + name,
            "@maven//:junit_junit",
            "@rules_kotlin//kotlin/compiler:kotlin-test",
            "@rules_kotlin//kotlin/compiler:kotlin-test-junit",
        ] + deps + test_deps,
    )

    kt_jvm_test(
        name = name + "_common_test",
        env = {
            "KMP_COMMON_TEST_JAR": "%s/%s_common_test_lib.jar" % (native.package_name(), name),
        },
        test_class = "com.geekinasuit.waxnotes.build.CommonTests",
        runtime_deps = [
            ":%s_common_test_lib" % name,
            "//bazel/kmp:common_tests",
        ],
    )

    kt_jvm_test(
        name = name + "_common_main_guard",
        size = "small",
        data = srcs.main,
        env = {
            "KMP_COMMON_MAIN": ",".join(["%s/%s" % (native.package_name(), src) for src in srcs.main]),
        },
        test_class = "com.geekinasuit.waxnotes.build.CommonMainGuardTest",
        runtime_deps = ["//bazel/kmp:guard"],
    )

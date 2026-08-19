load("@io_bazel_rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")

kt_jvm_test(
    name = "StatisticsListenerTest",
    srcs = ["StatisticsListenerTest.kt"],
    associates = ["//src/org/perses/reduction/event"],
    data = ["golden_statistics_listener_test.properties"],
    deps = [
        "//:guava",
        "//:truth",
        "//src/org/perses/listener:listeners",
        "//src/org/perses/reduction:abstract_reducer",
        "//src/org/perses/reduction/reducer:node_based_reducer",
        "//src/org/perses/spartree",
    ],
)

kt_jvm_test(
    name = "TestScriptExecutionListenerTest",
    srcs = ["TestScriptExecutionListenerTest.kt"],
    deps = [
        "//:guava",
        "//:truth",
        "//src/org/perses/listener:listeners",
        "//src/org/perses/reduction:abstract_reducer",
    ],
)

kt_jvm_test(
    name = "ReductionTraceListenerTest",
    srcs = ["ReductionTraceListenerTest.kt"],
    data = ["//test_data/delta_1"],
    deps = [
        "//:jackson",
        "//:truth",
        "//src/org/perses/listener:listeners",
        "//src/org/perses/reduction/reducer:node_based_reducer",
        "//src/org/perses/util",
        "//test/org/perses/reduction:reducer_functional_test_base",
    ],
)

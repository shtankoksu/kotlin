package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

enum class Foo {
    interface
}

fun box(): String {
    testNotRenamed("interface", { Foo.interface })

    return "OK"
}
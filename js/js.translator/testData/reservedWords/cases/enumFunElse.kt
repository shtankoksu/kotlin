package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

enum class Foo {
    BAR
    fun `else`() { `else`() }

    fun test() {
        testNotRenamed("else", { ::`else` })
    }
}

fun box(): String {
    Foo.BAR.test()

    return "OK"
}
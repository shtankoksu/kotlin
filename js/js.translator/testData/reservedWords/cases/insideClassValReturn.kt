package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

class TestClass {
    val `return`: Int = 0

    fun test() {
        testNotRenamed("return", { `return` })
    }
}

fun box(): String {
    TestClass().test()

    return "OK"
}
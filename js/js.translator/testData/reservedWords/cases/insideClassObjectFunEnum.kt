package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

class TestClass {
    class object {
        fun enum() { enum() }

        fun test() {
            testNotRenamed("enum", { ::enum })
        }
    }
}

fun box(): String {
    TestClass.test()

    return "OK"
}
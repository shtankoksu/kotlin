package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

data class DataClass(`if`: String) {
    {
        testRenamed("if", { `if` })
    }
}

fun box(): String {
    DataClass("123")

    return "OK"
}
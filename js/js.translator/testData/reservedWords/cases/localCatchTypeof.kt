package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

fun box(): String {
    

    
    try {
        throw Exception()
    }
    catch(typeof: Exception) {
        testRenamed("typeof", { typeof })
    }

    return "OK"
}
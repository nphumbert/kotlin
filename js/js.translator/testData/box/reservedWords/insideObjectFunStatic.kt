package foo

// NOTE THIS FILE IS AUTO-GENERATED by the generateTestDataForReservedWords.kt. DO NOT EDIT!

object TestObject {
    fun static() { static() }

    fun test() {
        testNotRenamed("static", { static() })
    }
}

fun box(): String {
    TestObject.test()

    return "OK"
}
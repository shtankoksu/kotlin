class SomeClass {
  class SomeInternal

  fun some(a : S<caret>)
}

// TIME: 2
// EXIST: SomeClass, SomeInternal
// EXIST: String~(jet)
// EXIST: IllegalStateException
// EXIST: StringBuilder
// EXIST_JAVA_ONLY: StringBuffer
// EXIST_JS_ONLY: HTMLStyleElement
// EXIST_JAVA_ONLY: Statement@Statement~(java.sql)
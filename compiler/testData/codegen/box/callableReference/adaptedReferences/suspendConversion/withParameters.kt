// !LANGUAGE: +SuspendConversion
// WITH_RUNTIME
// WITH_COROUTINES
// IGNORE_BACKEND_FIR: JVM_IR

import helpers.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

fun runSuspend(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

fun foo(s: String): String = s + "K"

suspend fun invokeSuspend(fn: suspend (String) -> String, arg: String) = fn.invoke(arg)

fun box(): String {
    var test = "failed"
    runSuspend {
        test = invokeSuspend(::foo, "O")
    }
    return test
}
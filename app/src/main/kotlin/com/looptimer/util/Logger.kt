package com.looptimer.util

import android.util.Log
import com.looptimer.BuildConfig

/**
 * Thin logging facade.
 *
 * The codebase previously had no logging at all: roughly eight empty `catch` blocks and one
 * `printStackTrace()` swallowed every failure, which made field problems undiagnosable. Verbose
 * and debug output is compiled out of release builds; warnings and errors are always kept, since
 * they represent conditions worth reporting.
 */
object Logger {

    private const val PREFIX = "LoopTimer"

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.d("$PREFIX:$tag", message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w("$PREFIX:$tag", message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("$PREFIX:$tag", message, throwable)
    }
}

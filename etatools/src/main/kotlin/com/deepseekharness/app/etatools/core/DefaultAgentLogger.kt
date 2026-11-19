package com.deepseekharness.app.etatools.core

import android.util.Log

/** Minimal AgentLogger implementation for the standalone etatools module: framework Log only. */
object DefaultAgentLogger : AgentLogger {
    private const val TAG = "Etatools"

    override fun debug(message: () -> String) {
        Log.d(TAG, message())
    }

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
    }
}

package com.example.jellyfinserver.core

import android.util.Log

object LogManager {
    private const val TAG = "JellyfinServer"
    private val logBuffer = StringBuilder()
    private var logListener: ((String) -> Unit)? = null

    fun setLogListener(listener: ((String) -> Unit)?) {
        this.logListener = listener
        if (listener != null) {
            val current = synchronized(logBuffer) { logBuffer.toString() }
            listener.invoke(current)
        }
    }

    fun log(message: String) {
        Log.d(TAG, message)
        synchronized(logBuffer) {
            if (logBuffer.length > 50000) logBuffer.delete(0, 10000)
            logBuffer.append(message).append("\n")
        }
        logListener?.invoke(message)
    }

    fun getLogs(): String = synchronized(logBuffer) { logBuffer.toString() }

    fun clear() {
        synchronized(logBuffer) { logBuffer.setLength(0) }
        logListener?.invoke("")
    }
}

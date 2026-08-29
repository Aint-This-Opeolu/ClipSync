package com.clipsync.android

import android.util.Log

/**
 * Tiny in-process pub/sub so [ClipSyncService] can report log lines to whichever
 * activity is currently visible, without a Binder connection. Safe because the
 * service and activity always run in the same process.
 */
object ClipSyncLog {
    private const val TAG = "ClipSync"
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun log(message: String) {
        Log.i(TAG, message)
        listeners.forEach { it(message) }
    }
}

/** Same pattern as [ClipSyncLog], for the one-line status shown at the top of the screen. */
object ClipSyncStatus {
    @Volatile
    var current: String = "Stopped"
        private set

    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun set(status: String) {
        current = status
        listeners.forEach { it(status) }
    }
}

package com.zalopilot.app.util

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.delay

suspend fun randomDelay(minMs: Long = 800, maxMs: Long = 3000) {
    val wait = (minMs..maxMs).random()
    delay(wait)
}

fun isZaloRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return am.runningAppProcesses?.any {
        it.processName == "com.zing.zalo"
    } == true
}

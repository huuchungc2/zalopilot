package com.zalopilot.app.util

import kotlinx.coroutines.delay

suspend fun randomDelay(minMs: Long, maxMs: Long) {
    val ms = if (minMs >= maxMs) minMs else (minMs..maxMs).random()
    delay(ms)
}

fun LongRange.random(): Long = (this.first + (Math.random() * (this.last - this.first)).toLong())

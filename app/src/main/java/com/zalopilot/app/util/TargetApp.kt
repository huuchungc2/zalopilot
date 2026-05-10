package com.zalopilot.app.util

/**
 * Nhận diện app Zalo / biến thể package (webview, activity phụ, bản VNG…)
 * không phụ thuộc so sánh bằng một package cố định.
 */
fun isZaloRelatedPackage(packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false
    return packageName.contains("zalo", ignoreCase = true)
}

package com.zalopilot.app.util

/**
 * Nhận diện app Zalo / biến thể package (webview, activity phụ, bản VNG…)
 * không phụ thuộc so sánh bằng một package cố định.
 */
fun isZaloRelatedPackage(packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false
    val p = packageName.lowercase()
    if (p.contains("zalopilot")) return false
    return p.contains("zalo")
}

/** Visit / đọc feed — chỉ khi đang ở app Zalo chính, không phải ZaloPilot hay launcher. */
fun isZaloMainAppPackage(packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false
    val p = packageName.lowercase()
    if (p.contains("zalopilot")) return false
    return p == "com.zing.zalo" || p.startsWith("com.zing.zalo.")
}

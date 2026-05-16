package com.zalopilot.app.util

/**
 * Cách user bắt đầu bot — quyết định [ZaloPilotAccessibilityService.prepareZaloForCurrentMode].
 *
 * - [HOME_LIKE_BUTTON]: từ app ZaloPilot (▶ Like Nhật ký / Like danh bạ) — luôn mở Zalo, chờ, rồi tab.
 * - [FLOATING_ON_ZALO]: user đã mở Zalo, menu nổi ▶ Bắt đầu like — đọc cây Zalo, chỉ chuyển tab nếu sai.
 * - [POLL_AUTO]: poll thấy Zalo + Tự động — giống mở Zalo từ app (không chặn nếu user đã DỪNG).
 */
enum class BotStartEntry {
    HOME_LIKE_BUTTON,
    FLOATING_ON_ZALO,
    POLL_AUTO
}

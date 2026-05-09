# ZaloPilot — Claude Code Context

## Dự án là gì
Android app tự động like bài đăng Zalo qua Accessibility Service.
Không dùng root. Không dùng Zalo API. Hoàn toàn hợp lệ về mặt kỹ thuật Android.

## Tech Stack
- **Ngôn ngữ:** Kotlin (không dùng Java)
- **UI:** Jetpack Compose (không dùng XML layout)
- **DI:** Hilt
- **Database:** Room
- **Async:** Kotlin Coroutines + Flow
- **Min SDK:** 26 (Android 8.0)
- **Package:** com.zalopilot.app

## Cấu trúc thư mục
```
app/src/main/java/com/zalopilot/app/
├── accessibility/
│   ├── ZaloPilotAccessibilityService.kt  # Core — auto like loop, status overlay
│   ├── NodeFinder.kt                      # Tìm node Zalo với fallback
│   └── ZaloUIScanner.kt                   # Tự học resource-id từ UI Zalo
├── data/model/
│   └── ZaloIDStore.kt                     # Lưu resource-id đã học
├── floating/
│   └── FloatingMenuService.kt             # Nút ZP nổi trên Zalo
├── ui/
│   └── MainActivity.kt                    # UI chính — Compose, Setup Wizard
├── util/
│   ├── LikeSettingsManager.kt             # Cài đặt của sale
│   ├── LikeProgressManager.kt             # Tiến độ like theo ngày
│   ├── Logger.kt                          # Ghi log ra file
│   └── Extensions.kt                      # randomDelay(), isZaloRunning()
└── ZaloPilotApp.kt                        # Hilt Application class
```

## Data Models quan trọng

```kotlin
// LikeSettings — lưu SharedPreferences "like_settings"
data class LikeSettings(
    val dailyLimit: Int = 100,        // Tối đa like/ngày
    val delayMinMs: Long = 1000,      // Delay tối thiểu giữa 2 like (ms)
    val delayMaxMs: Long = 3000,      // Delay tối đa (ms)
    val sessionLimit: Int = 30,       // Nghỉ sau N like liên tiếp
    val restMinMinutes: Int = 5,      // Nghỉ tối thiểu (phút)
    val restMaxMinutes: Int = 10,     // Nghỉ tối đa (phút)
    val quietHourStart: Int = 22,     // Không chạy từ giờ này
    val quietHourEnd: Int = 6,        // Đến giờ này
    val autoStart: Boolean = false,   // Tự chạy khi Zalo mở
    val likeModeStr: String = "FEED"  // "FEED" hoặc "VISIT"
)

// LikeProgress — lưu SharedPreferences "like_progress"
data class LikeProgress(
    val todayLikeCount: Int = 0,
    val lastRunDate: String = "",
    val visitIndex: Int = 0
)
```

## Broadcast Actions
```
com.zalopilot.STATUS_UPDATE   → bot start/stop (extra: running: Boolean)
com.zalopilot.PROGRESS_UPDATE → đã like thêm 1 bài
com.zalopilot.ZALO_STATE      → Zalo mở/tắt (extra: foreground: Boolean)
com.zalopilot.DAILY_LIMIT     → đã đủ giới hạn ngày
```

## Zalo Info
- Package: `com.zing.zalo`
- Nút Like text: `"Thích"`
- Nút đã Like text: `"Đã thích"`
- Tab Nhật ký text: `"Nhật ký"`

## Quy tắc code BẮT BUỘC

### Null-check AccessibilityNodeInfo
```kotlin
// SAI → crash
val node = root.findByText("Thích")
node.performAction(ACTION_CLICK)

// ĐÚNG
val node = nodeFinder.findLikeButtons(root).firstOrNull() ?: return false
node.performAction(ACTION_CLICK)
```

### Không hardcode resource-id
```kotlin
// SAI
root.findAccessibilityNodeInfosByViewId("com.zing.zalo:id/like_btn")

// ĐÚNG
val id = idStore.getLikeButtonID()
if (id.isNotEmpty()) root.findAccessibilityNodeInfosByViewId(id)
```

### Không dùng Thread.sleep()
```kotlin
// SAI
Thread.sleep(1000)

// ĐÚNG
randomDelay(settings.delayMinMs, settings.delayMaxMs)
```

### Không dùng XML layout
```kotlin
// SAI — không có file layout XML trong project
LayoutInflater.from(this).inflate(R.layout.my_layout, null)

// ĐÚNG — tạo view bằng code thuần
val tv = TextView(this).apply {
    text = "ZP"
    setBackgroundColor(Color.parseColor("#0068FF"))
}
```

### Không dùng R.drawable
```kotlin
// SAI — không có drawable nào
setSmallIcon(R.drawable.ic_notification)

// ĐÚNG
setSmallIcon(android.R.drawable.ic_dialog_info)
```

### Log mọi action
```kotlin
logger.log("LIKE", authorName, "SUCCESS")
logger.log("LIKE", authorName, "CLICK_FAILED")
logger.log("SCAN", "Không thấy nút Thích", "EMPTY")
```

## Workflow hằng ngày
```bash
# Edit code locally
git add .
git commit -m "feat: mô tả thay đổi"
git push
# GitHub Actions tự build APK → download về cài lên máy test
```

## Sau mỗi task — BẮT BUỘC cập nhật
1. `TODO.md` — đánh dấu [x] task vừa xong
2. `CHANGELOG.md` — thêm vào section [Unreleased]
3. Báo nếu có dependency mới cần thêm vào `build.gradle.kts`

## Những thứ KHÔNG được làm
- KHÔNG hardcode resource-id Zalo trong code
- KHÔNG dùng Thread.sleep() — luôn dùng coroutine delay()
- KHÔNG dùng XML layout — Compose hoặc code thuần
- KHÔNG lưu API key trong code hay git
- KHÔNG crash khi không tìm thấy node — luôn return null/false
- KHÔNG chạy action khi Zalo không foreground

# ZaloPilot — Claude Code Context

## Dự án là gì
Android app tự động like bài đăng Zalo qua Accessibility Service.
Không dùng root. Không dùng Zalo API. Hoàn toàn hợp lệ về mặt kỹ thuật Android.

## Tech Stack
- **Ngôn ngữ:** Kotlin (không dùng Java)
- **UI:** Jetpack Compose (không dùng XML layout)
- **DI:** Hilt
- **Async:** Kotlin Coroutines + Flow
- **Min SDK:** 26 (Android 8.0)
- **Package:** com.zalopilot.app

> ⚠️ Room được khai báo trong build.gradle.kts nhưng KHÔNG dùng.
> Dữ liệu lưu bằng SharedPreferences. Không thêm @Database/@Dao/@Entity.

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
│   ├── LikeSettingsManager.kt             # Cài đặt (FeedMode, InteractMode...)
│   ├── LikeProgressManager.kt             # Tiến độ like theo ngày
│   ├── Logger.kt                          # Ghi log ra file
│   └── Extensions.kt                      # randomDelay(), isZaloRunning()
└── ZaloPilotApp.kt                        # Hilt Application class
```

## Zalo Info
- Package: `com.zing.zalo`
- Nút Like text: `"Thích"`
- Nút đã Like text: `"Đã thích"`
- Tab Nhật ký text: `"Nhật ký"`

---

## Các quyết định thiết kế quan trọng — KHÔNG thay đổi

### 1. Không lưu danh sách bài đã like vào database

Bot **không lưu** danh sách bài đã like. Mỗi lần chạy đọc trạng thái thực từ màn hình Zalo.

**Lý do:** Feed Zalo không có stable ID. Lưu dễ gây false positive (bỏ qua bài chưa like). Cuộn qua bài đã like nhanh hơn maintain database có thể sai.

**Cách phân biệt bài đã like:** dùng `NodeFinder.isAlreadyLiked()` — nhãn nút like / `btn_like_text`, `isChecked`/`isSelected` của control like; không suy từ reaction count hay `reaction_info` của người khác.

### 2. postKey KHÔNG dùng bounds tuyệt đối

```kotlin
// SAI — sau scroll bài mới xuất hiện cùng tọa độ → key trùng → skip sai
"${rect.left}_${rect.top}_${rect.right}_${rect.bottom}|$author|$snippet"

// ĐÚNG — dựa vào nội dung bài
"CONTENT|$author|$snippet"
// Fallback khi author + snippet đều rỗng:
"BOUNDS|${rect.left}_${rect.top}_${rect.right}_${rect.bottom}"
```

### 3. processedPosts phải clear sau mỗi lần scroll

`processedPosts` chỉ dùng để tránh like lại trong **cùng 1 lần scan**. Sau `scrollFeedWithVerification()` phải gọi `processedPosts.clear()` ngay — feed đã cuộn sang nội dung mới.

Sau cuộn/gesture: chờ feed ổn định (~800–1500ms, `delayFeedSettleAfterScroll`) rồi mới `acquireRoot` + quét lại — tránh empty scan khi RecyclerView lazy-load.

### 4. FeedScanResult — 3 trạng thái, không phải Boolean

`runFeedMode()` trả về `FeedScanResult` (không phải Boolean):
- `LIKED` — like được ít nhất 1 bài → delay tự nhiên → scroll theo FeedMode
- `ALL_SKIPPED` — thấy nút Thích nhưng tất cả đã like → scroll nhanh (300–600ms), KHÔNG đếm consecutiveEmptyScrolls
- `NO_BUTTONS` — không thấy nút Thích nào → đếm consecutiveEmptyScrolls → dừng khi >= 5

> **Đã like (mình)** trên toàn màn hình: `findLikeButtons` có thể rỗng vì đã lọc `isAlreadyLiked`. Khi `hasVisibleSelfAlreadyLikedLikeControl` = true → coi như **`ALL_SKIPPED`** (luôn cuộn tiếp, kể cả FeedMode MANUAL), không nhầm với `NO_BUTTONS`.

> `ALL_SKIPPED` KHÔNG được nhầm với `NO_BUTTONS`. Nếu thấy node nhưng tất cả `isAlreadyLiked()` → `ALL_SKIPPED`. Nếu `findLikeButtons()` trả về list rỗng → `NO_BUTTONS`.

### 5. InteractMode và FeedMode phải được đọc từ settingsManager

```kotlin
// SAI — cứng nhắc, bỏ qua setting của user
performClickWithFallback(node)  // luôn ACTION_CLICK

// ĐÚNG — đọc setting
val interactMode = settingsManager.getInteractMode()
val useGestureFirst = when (interactMode) {
    InteractMode.TAP   -> false
    InteractMode.SWIPE -> true
    InteractMode.MIX   -> (1..2).random() == 1
}
```

FeedMode được đọc trong `autoLikeLoop` sau mỗi `FeedScanResult.LIKED`:
- `SCROLL` → tự cuộn
- `MANUAL` → không cuộn, chờ tay vuốt
- `MIX` → 60% tự cuộn, 40% chờ

> `ALL_SKIPPED` luôn tự cuộn bất kể FeedMode.

### 6. Verify trạng thái sau khi click like

Sau `performLikeClickWithFallbacks()` thành công, **bắt buộc delay 1500ms** rồi đọc lại node xác nhận đã chuyển sang "Đã thích". Nếu chưa chắc → delay thêm 600ms → kiểm tra lần 2. Nếu vẫn không xác nhận (`CLICK_UNCONFIRMED`) → không ghi SUCCESS, không thêm `processedPosts`, không tăng tiến độ — tránh cuộn đi rồi unlike nhầm. Không delay đủ → Zalo chưa animate xong → bot thấy vẫn "Thích" → click lại → unlike.

### 7. isAlreadyLiked() là nguồn truth duy nhất

Mọi logic phân biệt "đã like / chưa like" đều phải qua `NodeFinder.isAlreadyLiked()`. Không duplicate logic này ở chỗ khác. Hàm chỉ coi **tài khoản hiện tại** đã like — không suy từ reaction count / `reaction_info` của người khác.

**Không** kết luận "chưa like" chỉ vì text vẫn là "Thích" (Zalo có thể stale). **Không** dùng chuỗi "Thích" làm bằng chứng để click lại (tránh unlike).

Thứ tự ưu tiên (trên vùng id whitelist / `my_reaction` gần nút like):
1. `isChecked` / `isSelected` trên node id like hợp lệ (`LikeViewIdRules`)
2. API 30+ `stateDescription`: "Đã thích", "Liked", hoặc selected/đã chọn trên vùng like
3. Text / contentDescription rõ "Đã thích" / "Liked" (không chứa số) trên `btn_like_text` / `btn_like_icon` hoặc id whitelist

Target click like: chỉ id whitelist (`btn_like_text`, `btn_like_icon`, `btn_like`, `like_component`); blacklist tuyệt đối `vpager`, layout feed, RecyclerView/FrameLayout/ViewPager làm target. Học ID qua `ZaloUIScanner` phải qua whitelist — không lưu `vpager`. Like: `performClickLikeTargetNoParent` (không leo parent). Mở nhầm image viewer → `GLOBAL_ACTION_BACK` + skip bài.

### 8. Giữ màn hình khi bot chạy

Status overlay (thanh trạng thái trên Zalo) bật `FLAG_KEEP_SCREEN_ON` + `View.keepScreenOn` trong lúc `isRunning` — màn hình **không tự tắt** theo cài đặt timeout, để accessibility vẫn đọc được feed. `stopAutoLike()` / `hideStatusOverlay()` → gỡ cờ, máy tắt màn bình thường lại.

---

## Quy tắc code BẮT BUỘC

### Null-check AccessibilityNodeInfo
```kotlin
// SAI → crash
val node = root.findByText("Thích")
node.performAction(ACTION_CLICK)

// ĐÚNG
val node = nodeFinder.findLikeButtons(root).firstOrNull() ?: return FeedScanResult.NO_BUTTONS
node.performAction(ACTION_CLICK)
```

### Không hardcode resource-id
```kotlin
// SAI
root.findAccessibilityNodeInfosByViewId("com.zing.zalo:id/like_btn")

// ĐÚNG
val id = idStore.getLikeButtonID()
if (id != null) root.findAccessibilityNodeInfosByViewId(id)
```

### Không dùng Thread.sleep()
```kotlin
// SAI
Thread.sleep(1000)

// ĐÚNG
randomDelay(settings.delayMinMs, settings.delayMaxMs)
// hoặc delay coroutine trực tiếp
delay((300L..600L).random())
```

### Không dùng XML layout
```kotlin
// SAI
LayoutInflater.from(this).inflate(R.layout.my_layout, null)

// ĐÚNG
val tv = TextView(this).apply {
    text = "ZP"
    setBackgroundColor(Color.parseColor("#0068FF"))
}
```

### Không dùng R.drawable
```kotlin
// SAI
setSmallIcon(R.drawable.ic_notification)

// ĐÚNG
setSmallIcon(android.R.drawable.ic_dialog_info)
```

### Log mọi action
```kotlin
logger.log(LogTag.CLICK, authorName, "SUCCESS")
logger.log(LogTag.CLICK, authorName, "CLICK_FAILED")
logger.log(LogTag.SCAN, "Không thấy nút Thích", "EMPTY")
```

---

## Data Models

```kotlin
// LikeSettings — lưu SharedPreferences "like_settings" dạng JSON (Gson)
data class LikeSettings(
 val dailyLimit: Int = 100,
 val delayMinMs: Long = 1000,
 val delayMaxMs: Long = 3000,
 val sessionLimit: Int = 30,
 val restMinMinutes: Int = 5,
 val restMaxMinutes: Int = 10,
 val quietHourStart: Int = 22,
 val quietHourEnd: Int = 6,
 val autoStart: Boolean = false,
 val likeModeStr: String = "FEED",
 val interactModeStr: String = "MIX",
 val ecoMode: Boolean = false
)

// FeedMode — cách cuộn feed
enum class FeedMode { SCROLL, MANUAL, MIX }

// InteractMode — cách click nút Like
enum class InteractMode { TAP, SWIPE, MIX }

// LikeProgress — lưu SharedPreferences "like_progress"
data class LikeProgress(
    val todayLikeCount: Int = 0,
    val lastRunDate: String = "",
    val visitIndex: Int = 0
)

// FeedScanResult — kết quả 1 lần scan feed (trả về từ runFeedMode)
enum class FeedScanResult { LIKED, ALL_SKIPPED, NO_BUTTONS }
```

## Broadcast Actions
```
com.zalopilot.STATUS_UPDATE   → bot start/stop (extra: running: Boolean)
com.zalopilot.PROGRESS_UPDATE → đã like thêm 1 bài
com.zalopilot.ZALO_STATE      → Zalo mở/tắt (extra: foreground: Boolean)
com.zalopilot.DAILY_LIMIT     → đã đủ giới hạn ngày
```

---

## Workflow hằng ngày
```bash
git add .
git commit -m "feat: mô tả thay đổi"
git push
# GitHub Actions tự build APK → download về cài lên máy test
```

## Sau mỗi task — BẮT BUỘC cập nhật
1. `TODO.md` — đánh dấu [x] task vừa xong
2. `CHANGELOG.md` — thêm vào section [Unreleased]
3. Báo nếu có dependency mới cần thêm vào `build.gradle.kts`

---

## Những thứ KHÔNG được làm
- KHÔNG hardcode resource-id Zalo trong code
- KHÔNG dùng Thread.sleep() — luôn dùng coroutine delay()
- KHÔNG dùng XML layout — Compose hoặc code thuần
- KHÔNG lưu API key trong code hay git
- KHÔNG crash khi không tìm thấy node — luôn return null/false/FeedScanResult
- KHÔNG chạy action khi Zalo không foreground
- KHÔNG dùng bounds tuyệt đối làm postKey
- KHÔNG duplicate logic isAlreadyLiked ở ngoài NodeFinder
- KHÔNG bỏ qua FeedMode / InteractMode khi scroll hoặc click
- KHÔNG click lại ngay sau like mà không delay 900ms verify

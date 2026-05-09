# ZaloPilot — Architecture

## Tổng quan

```
┌─────────────────────────────────────────────┐
│                ZaloPilot App                 │
│                                             │
│  ┌──────────────┐   ┌─────────────────────┐ │
│  │ FloatingMenu │   │    MainActivity      │ │
│  │   Service    │   │  (Setup Wizard/UI)   │ │
│  └──────┬───────┘   └─────────────────────┘ │
│         │                                   │
│  ┌──────▼──────────────────────────────┐    │
│  │     ZaloPilotAccessibilityService   │    │
│  │  - autoLikeLoop()                   │    │
│  │  - Status Overlay (realtime)        │    │
│  │  - WakeLock                         │    │
│  │  - Detect Zalo foreground/bg        │    │
│  └──────┬──────────────────────────────┘    │
│         │                                   │
│  ┌──────▼──────────────────────────────┐    │
│  │  NodeFinder  │  ZaloUIScanner       │    │
│  │  (tìm node)  │  (tự học ID)         │    │
│  └──────┬───────┴──────────────────────┘    │
│         │                                   │
│  ┌──────▼──────────────────────────────┐    │
│  │           ZaloIDStore               │    │
│  │   (SharedPreferences lưu ID)        │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌──────────────────────────────────────┐   │
│  │  LikeSettingsManager │ LikeProgress  │   │
│  │  Logger              │ Extensions    │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

---

## Flow: Auto Like

```
Zalo lên foreground
        │
        ▼
ZaloUIScanner.scan(root)  ← tự học ID mới nhất
        │
        ▼
autoStart == true?
  YES → startAutoLike()
  NO  → chờ sale bấm Start trên [ZP]
        │
        ▼
autoLikeLoop():
  ┌─ Check quietHour → dừng nếu đúng giờ nghỉ
  ├─ Check dailyLimit → dừng nếu đủ
  ├─ Check isZaloForeground → dừng nếu Zalo tắt
  ├─ Check sessionLimit → nghỉ nếu đủ
  ├─ rootInActiveWindow == null → chờ 5s, thử lại
  ├─ Scan UI nếu chưa scan gần đây
  ├─ runFeedMode():
  │    findLikeButtons() → shouldLike() → click → log → delay
  └─ Không like được → scrollDown() → lặp lại
```

---

## Flow: Tự học ID (Self-healing)

```
ZaloUIScanner.scan(root)
        │
        ▼
Tìm node bằng text "Thích" → lấy resource-id → lưu ZaloIDStore
Tìm node bằng text "Nhật ký" → lưu tab_timeline ID
Tìm RecyclerView → lưu feed_recycler ID
Tìm author name node → lưu author_name ID
        │
        ▼
NodeFinder.findLikeButtons():
  Lấy ID từ ZaloIDStore → tìm bằng ID
  Nếu fail → tìm bằng text "Thích" (fallback)
  Zalo update → ID cũ fail → scan lại → lưu ID mới → tự heal
```

---

## Flow: Setup Wizard (lần đầu cài)

```
Mở app lần đầu
        │
        ▼
isSetupComplete()? (Accessibility ON + Overlay ON)
  NO → SetupWizard:
    Bước 1: Bật Accessibility → tự detect mỗi giây → chuyển bước
    Bước 2: Bật Overlay → tự detect → chuyển bước
    Bước 3: Done → start FloatingMenuService → vào app chính
  YES → ZaloPilotApp (màn hình chính)
```

---

## Cấu trúc file quan trọng

### ZaloPilotAccessibilityService.kt
- `onServiceConnected()` — init, log, toast
- `onAccessibilityEvent()` — detect Zalo foreground/bg, auto start
- `startAutoLike()` / `stopAutoLike()` — public API
- `autoLikeLoop()` — coroutine loop chính
- `runFeedMode()` — tìm và click like
- `scrollDown()` — gesture swipe
- `showStatusOverlay()` / `updateStatus()` — overlay realtime
- `showToast()` — toast feedback

### NodeFinder.kt
- `findLikeButtons(root)` — ID first, text fallback
- `shouldLike(node)` — check chưa like, text == "Thích"
- `getAuthorName(node)` — leo parent tìm tên tác giả
- `findTimelineTab(root)` — tìm tab Nhật ký

### ZaloUIScanner.kt
- `scan(root)` — quét toàn bộ, có cooldown 30s
- `forceScan(root)` — bỏ qua cooldown
- `hasScannedRecently()` — check cooldown
- `scanLikeButton()` / `scanTabTimeline()` / `scanAuthorName()` / `scanFeedRecycler()`

### ZaloIDStore.kt
- SharedPreferences lưu: like_button_id, tab_timeline_id, author_name_id, feed_recycler_id
- Constants: TEXT_LIKE, TEXT_LIKED, TEXT_TIMELINE, CLASS_RECYCLER

---

## Permissions (AndroidManifest)
```xml
BIND_ACCESSIBILITY_SERVICE
BIND_NOTIFICATION_LISTENER_SERVICE
SYSTEM_ALERT_WINDOW
WAKE_LOCK
FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE
INTERNET
RECEIVE_BOOT_COMPLETED
```

---

## Dependencies (build.gradle.kts)
```kotlin
// Compose BOM 2024.05.00
// Hilt 2.51
// Room 2.6.1
// OkHttp 4.12.0
// Gson 2.10.1
// Coroutines 1.8.0
// Lifecycle 2.7.0
```

# ZaloPilot — Tasks cho Cursor

---

## TASK 1 — Fix bug like/unlike toggle

**File:** `ZaloPilotAccessibilityService.kt`, `NodeFinder.kt`

**Vấn đề:**
Sau khi click like, verify đọc lại node bị stale → thấy chưa like → click lại → unlike.

**Fix:**
- Sau click, gọi `rootInActiveWindow` lấy cây node mới hoàn toàn, không dùng node cũ
- Delay verify: lần 1 = 1200ms, lần 2 = 800ms
- Sau 2 lần verify không confirm → log WARNING + bỏ qua, không click thêm
- Tuyệt đối không re-click nếu không có bằng chứng rõ ràng node đang "chưa like"

---

## TASK 2 — Fix không đếm được count like

**File:** `NodeFinder.kt`

**Vấn đề:**
Node số like ("12 friends") là `TextView` sibling cùng cấp với nút Like, không phải child.

**Fix:**
Từ node nút Like → đi lên `parent` → duyệt `child` của parent → tìm `TextView` có text là số nguyên dương hoặc chứa "bạn"/"friends". Log ra `LogTag.CLICK`.

---

## TASK 3 — Fix click nhầm bài có nhạc Zing MP3

**File:** `ZaloPilotAccessibilityService.kt`, `NodeFinder.kt`

**Vấn đề:**
Click nhầm vào media → Zalo mở bottom sheet Zing MP3 → loop kẹt.

**Fix — thêm `isZingMusicBottomSheet(root)` vào `NodeFinder.kt`:**
Detect bằng text: có node "Nghe trên Zing MP3" HOẶC "Đăng lên nhật ký".
Gọi đầu mỗi vòng lặp → nếu detect → `GLOBAL_ACTION_BACK` → delay 500ms → tiếp tục.

---

## TASK 4 — Gộp tất cả escape màn hình lạc vào 1 hàm

**File:** `ZaloPilotAccessibilityService.kt`, `NodeFinder.kt`

**Fix — tạo `detectAndEscapeWrongScreen(root): Boolean`:**
```
1. isFullScreenCommentScreen(root)
   → header "Comments" HOẶC "Bình luận"
   + hint "Write a comment" HOẶC "Nhập bình luận"
   → BACK 1-2 lần
2. isZingMusicBottomSheet(root) → BACK 1 lần
3. isLikelyZaloImageViewer(root) → BACK 1 lần
4. Package không phải com.zing.zalo → log, không BACK
```
Trả về `true` nếu đã escape. Thay thế `tryEscapeCommentScreen()` hiện tại. Gọi đầu mỗi vòng lặp.

---

## TASK 5 — Nâng cấp Dump UI: dump toàn màn hình ra Downloads

**File:** `floating/FloatingMenuService.kt`

**Vấn đề hiện tại:**
- `dumpFirst3FeedItemsToFilesDir()` chỉ dump được feed, lưu vào `filesDir` không xem được

**Fix — thay bằng `dumpFullScreenToDownloads()`:**

Detect tên màn hình bằng text (bilingual VI/EN):
```kotlin
val screenName = when {
    // Contacts
    (findText("Contacts") || findText("Danh bạ")) &&
    (findText("All") || findText("Tất cả")) -> "contacts"
    // Comments
    (findText("Comments") || findText("Bình luận")) &&
    (findHint("Write a comment") || findHint("Nhập bình luận")) -> "comments"
    // Profile
    (findText("Photos") || findText("Ảnh")) &&
    (findText("Videos") || findText("Video")) -> "profile"
    // Chat
    findHint("Message") || findHint("Tin nhắn") -> "chat"
    else -> "unknown"
}
```

Dump toàn bộ cây node (depthLimit=12, nodeLimit=1000) → xuất thẳng ra Downloads qua `MediaStore.Downloads` API.
Tên file: `zp_dump_[screenName]_[timestamp].json`
Toast: `"✅ Downloads: zp_dump_profile_1234567890.json"`

**Thứ tự:** Implement trước TASK 6 — cần dump 3 màn hình (contacts, chat, profile) để viết script Visit Mode chính xác.

---

## TASK 6 — Script Engine Phase 0: ZPEngine primitive

**File mới:** `accessibility/engine/ZPEngine.kt`

**Mục đích:** Tất cả flow mới (Visit Mode, Comment Mode...) viết bằng JSON script — không cần build lại APK khi fix bug hay thêm flow. Feed mode hiện tại giữ nguyên Kotlin, không migrate.

**Các primitive:**

```kotlin
class ZPEngine(private val service: ZaloPilotAccessibilityService) {

    suspend fun acquireRoot(retries: Int = 5): AccessibilityNodeInfo?
    // → dùng lại acquireRootOrNull() hiện có

    suspend fun tap(node: AccessibilityNodeInfo): Boolean
    // → gesture tap vào center của node bounds

    suspend fun tapCenter(rect: Rect): Boolean
    // → gesture tap vào center của rect

    suspend fun swipeUp(screenH: Int): Boolean
    // → gesture swipe từ 70% lên 30% chiều cao

    suspend fun back(): Boolean
    // → GLOBAL_ACTION_BACK + delay 400ms

    suspend fun findText(root: AccessibilityNodeInfo, text: String, ignoreCase: Boolean = true): AccessibilityNodeInfo?
    // → dùng lại findFirstNodeWithTextOrDesc() từ NodeFinder

    suspend fun findHint(root: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo?
    // → tìm node có hint/placeholder matching

    suspend fun inputText(node: AccessibilityNodeInfo, text: String): Boolean
    // → focus node → ACTION_SET_TEXT

    suspend fun waitUntil(timeoutMs: Long, intervalMs: Long = 300, condition: suspend () -> Boolean): Boolean
    // → poll condition mỗi intervalMs cho đến khi true hoặc timeout

    suspend fun ensureScreen(detect: suspend () -> Boolean, navigate: suspend () -> Unit, timeoutMs: Long = 3000): Boolean
    // → waitUntil { detect() }, nếu fail → navigate() → waitUntil lần 2

    suspend fun exists(root: AccessibilityNodeInfo, text: String): Boolean
    // → findText != null

    suspend fun scroll(node: AccessibilityNodeInfo, direction: ScrollDirection = ScrollDirection.DOWN): Boolean
    // → ACTION_SCROLL_FORWARD / BACKWARD

    // --- Bổ sung ---

    suspend fun tapNodeAt(nodes: List<AccessibilityNodeInfo>, index: Int): Boolean
    // → kiểm tra index hợp lệ → tap(nodes[index])

    suspend fun tapSend(root: AccessibilityNodeInfo): Boolean
    // → tìm node clickable có contentDescription "Send"/"Gửi"
    // → hoặc ImageButton nằm góc phải dưới màn hình (bounds.right > 80% width, bounds.bottom > 85% height)
    // → tap node đó

    suspend fun incrementVar(key: String): Int
    // → đọc từ LikeProgressManager → tăng 1 → lưu → trả về giá trị mới

    suspend fun resolveVar(key: String): Any
    // → "$visitIndex"       → LikeProgressManager.getVisitIndex()
    // → "$visitLikeCount"   → LikeSettingsManager.getVisitLikeCount()
    // → "$visitCommentCount"→ LikeSettingsManager.getVisitCommentCount()
    // → số nguyên literal   → parse Int trực tiếp

    suspend fun findAndTapLike(root: AccessibilityNodeInfo): Boolean
    // → wrap NodeFinder.findLikeButtons() → lấy node đầu tiên chưa like → tap
    // → delay 1200ms → verifyLiked

    suspend fun verifyLiked(root: AccessibilityNodeInfo): Boolean
    // → wrap NodeFinder.isAlreadyLiked() trên fresh root
    // → retry 1 lần sau 800ms nếu chưa confirm

    suspend fun inputRandomComment(root: AccessibilityNodeInfo): Boolean
    // → LikeSettingsManager.getVisitCommentList() → random pick
    // → nếu list rỗng → return false (bỏ qua, không crash)
    // → tìm ô input hint "Write a comment"/"Nhập bình luận" → inputText()
}

enum class ScrollDirection { UP, DOWN }
```

---

## TASK 7 — Script Engine Phase 1: JSON DSL loader

**File mới:** `accessibility/engine/ZPScriptRunner.kt`, `accessibility/engine/ZPScript.kt`

**Cấu trúc JSON script:**

```json
{
  "id": "visit_contacts_v1",
  "version": 1,
  "steps": [
    { "id": "s1", "action": "ensureScreen", "screen": "contacts" },
    { "id": "s2", "action": "findContactItems" },
    { "id": "s3", "action": "tapContactAt", "indexVar": "$visitIndex" },
    { "id": "s4", "action": "ensureScreen", "screen": "chat", "timeoutMs": 3000 },
    { "id": "s5", "action": "tapProfileEntry" },
    { "id": "s6", "action": "ensureScreen", "screen": "profile", "timeoutMs": 3000 },
    { "id": "s7", "action": "repeat", "count": "$visitLikeCount", "do": [
      { "action": "findLikeButton" },
      { "action": "tapLike" },
      { "action": "wait", "ms": 1200 },
      { "action": "verifyLiked" }
    ]},
    { "id": "s8", "action": "ifSetting", "key": "visitCommentCount", "gt": 0, "do": [
      { "action": "repeat", "count": "$visitCommentCount", "do": [
        { "action": "findCommentButton" },
        { "action": "tap" },
        { "action": "ensureScreen", "screen": "comments", "timeoutMs": 2000 },
        { "action": "inputRandomComment" },
        { "action": "tapSend" },
        { "action": "back" }
      ]}
    ]},
    { "id": "s9", "action": "back" },
    { "id": "s10", "action": "back" },
    { "id": "s11", "action": "incrementVar", "var": "$visitIndex" },
    { "id": "s12", "action": "saveVar", "var": "$visitIndex" },
    { "id": "s13", "action": "goto", "step": "s1" }
  ]
}
```

**`ZPScriptRunner`:**
- Load script từ `assets/scripts/` (bundled) hoặc `filesDir/scripts/` (downloaded)
- Parse JSON → list of `ZPStep`
- Execute từng step, log `{ stepId, action, result, elapsedMs }`
- Biến `$visitIndex`, `$visitLikeCount`, `$visitCommentCount` resolve qua `ZPEngine.resolveVar()`
- Khi step fail → retry 1 lần → nếu vẫn fail → `detectAndEscapeWrongScreen()` → retry → nếu vẫn fail → skip + log ERROR

**Control flow cần implement trong `ZPScriptRunner`:**

```kotlin
// "repeat" — lặp N lần, N là số hoặc $variable
{ "action": "repeat", "count": "$visitLikeCount", "do": [...] }
// → engine.resolveVar("$visitLikeCount") lấy số N → loop N lần thực thi block "do"

// "goto" — nhảy về step theo id (dùng cho vòng lặp chính)
{ "action": "goto", "step": "s1" }
// → ZPScriptRunner tìm step có id = "s1" → set currentIndex → tiếp tục
// → Phòng infinite loop: đếm số lần goto, nếu > maxIterations (default 10000) → dừng + log

// "ifSetting" — điều kiện dựa vào setting
{ "action": "ifSetting", "key": "visitCommentCount", "gt": 0, "do": [...] }
// → đọc setting key từ LikeSettingsManager → so sánh (gt/lt/eq/gte/lte)
// → nếu đúng → thực thi block "do" → nếu sai → bỏ qua

// "wait" — delay cố định
{ "action": "wait", "ms": 1200 }
// → delay(ms)

// "incrementVar" — tăng biến và lưu
{ "action": "incrementVar", "var": "$visitIndex" }
// → engine.incrementVar("visitIndex")

// "saveVar" — lưu biến hiện tại (dùng để resume sau khi app bị kill)
{ "action": "saveVar", "var": "$visitIndex" }
// → LikeProgressManager.setVisitIndex(currentValue)
```

**Resume sau interrupt:**
- Mỗi lần `saveVar` → persist vào SharedPreferences
- Khi script khởi động lại → đọc `$visitIndex` từ prefs → tiếp tục từ contact đó

**Screen actions được map sang `NodeFinder` methods:**
```
"contacts"  → isContactListScreen(root)   — detect "Contacts"/"Danh bạ" + "All"/"Tất cả"
"chat"      → isChatScreen(root)          — detect hint "Message"/"Tin nhắn"
"profile"   → isProfileScreen(root)       — detect "Photos"/"Ảnh" + "Videos"/"Video"
"comments"  → isFullScreenCommentScreen() — detect "Comments"/"Bình luận" + hint nhập comment
```

**Thêm vào `NodeFinder.kt`:** 4 hàm detect màn hình trên theo pattern text-based bilingual (VI/EN).

---

## TASK 8 — Script bundled: visit_contacts_v1.json

**File mới:** `assets/scripts/visit_contacts_v1.json`

Script hoàn chỉnh cho Visit Mode theo cấu trúc TASK 7. Đây là script mặc định — khi có bug chỉ cần sửa file JSON này, không build lại APK.

**Thêm vào `NodeFinder.kt` — các action node cần thiết cho script:**

```kotlin
// Tìm items trong danh sách contacts
fun findContactListItems(root): List<AccessibilityNodeInfo>
// → clickable items có TextView con là tên người
// → bỏ qua: "Friend requests"/"Lời mời kết bạn", "Birthdays"/"Sinh nhật"

// Tap vào card profile trong màn Chat để vào trang cá nhân
fun findProfileEntryNode(root): AccessibilityNodeInfo?
// → card profile giữa màn hình (25%–75% chiều cao)
// → node có ImageView (avatar) + TextView (tên)

// Tìm nút comment cạnh nút Like
fun findCommentButton(likeNode): AccessibilityNodeInfo?
// → sibling của likeNode trong cùng parent
// → clickable, không có text, className ImageView/ImageButton
// → bounds.left > likeNode.bounds.right (nằm phía phải)
// → không phải nút "..." (3 chấm)

// Random pick comment từ danh sách cài đặt
fun getRandomComment(): String
// → từ LikeSettingsManager.getVisitCommentList()
// → nếu list rỗng → trả về ""
```

---

## TASK 9 — Script loader từ server sungnhon.xyz

**File:** `accessibility/engine/ZPScriptRunner.kt`, `accessibility/engine/ZPScriptStore.kt`, `ui/MainActivity.kt`

**Mục đích:** Tải script từ `https://sungnhon.xyz/ZaloPilot/scripts/` về máy → chạy. Sửa script trên server là app tự lấy về, không build APK.

### Cấu trúc server

Server cần có file index:
```
https://sungnhon.xyz/ZaloPilot/scripts/index.json
```
```json
{
  "scripts": [
    { "id": "visit_contacts_v1", "version": 2, "file": "visit_contacts_v1.json", "desc": "Like/comment danh sách bạn bè" },
    { "id": "feed_like_v1", "version": 1, "file": "feed_like_v1.json", "desc": "Like feed Nhật ký" }
  ]
}
```

### `ZPScriptStore` — quản lý script local

```kotlin
class ZPScriptStore(context: Context) {
    fun listLocal(): List<ZPScriptMeta>
    fun load(id: String): JSONObject?
    fun save(id: String, version: Int, json: JSONObject)
    fun delete(id: String)
    fun getLocalVersion(id: String): Int  // 0 nếu chưa có
}
// Thư mục lưu: context.filesDir/scripts/
```

### UI trong MainActivity — tab "Script"

```
┌─────────────────────────────────┐
│  🌐 Server: sungnhon.xyz        │
│  [🔄 Kiểm tra cập nhật]        │
├─────────────────────────────────┤
│  📋 visit_contacts_v1  v2  [↓] │  ← có bản mới
│  ✅ feed_like_v1       v1      │  ← đang dùng
├─────────────────────────────────┤
│  Script đang dùng:              │
│  visit_contacts_v1 v2           │
│  [▶ Chạy] [🧪 Test 1 vòng]    │
│  [🗑 Xóa cache] [📋 Xem JSON]  │
└─────────────────────────────────┘
```

### Cấu hình URL trong app

**Mặc định hardcode:** `https://sungnhon.xyz/ZaloPilot/scripts/`

**Cho phép override trong MainActivity — tab "Script":**
- Text field "Server URL" — hiện URL đang dùng
- Nút "Lưu" → save vào SharedPreferences key `script_server_url`
- Nút "Khôi phục mặc định" → reset về URL gốc
- App luôn đọc URL từ SharedPreferences trước, fallback về hardcode nếu chưa set



### Logic

1. Tap "Kiểm tra cập nhật" → fetch `index.json` → so sánh version với local
2. Version mới hơn → hiện nút [↓ Tải về] + badge "Mới"
3. Tap tải → download JSON → lưu vào `filesDir/scripts/[id]_v[version].json`
4. Mở app → tự động thông báo nếu có script mới trên server
5. Script đang chạy bị interrupt → resume từ step đã lưu trong `LikeProgressManager`
6. Nút "Xóa cache" → xóa local → tải lại từ server lần sau
7. Nút "Xem JSON" → hiện raw JSON trong dialog (debug)

---

## TASK 10 — UI cài đặt Visit Mode + comment list

**File:** `ui/MainActivity.kt`, `util/LikeSettingsManager.kt`

**Thêm fields vào `LikeSettings`:**
```kotlin
val visitLikeCount: Int = 3,
val visitCommentCount: Int = 0,
val visitActionMode: String = "LIKE_ONLY", // LIKE_ONLY | COMMENT_ONLY | MIX
val visitMaxProfiles: Int = 50,
val visitCommentList: List<String> = listOf("👍", "❤️", "Hay quá!", "Tuyệt vời!")
```

**UI trong MainActivity — tab Cài đặt, section "Visit Mode":**
- Toggle FEED / VISIT mode
- Slider số like mỗi profile (0–10, default 3)
- Slider số comment mỗi profile (0–5, default 0)
- Toggle action: Like only / Comment only / Mix
- Số profile tối đa mỗi phiên
- Nút Reset visitIndex
- **Section "Nội dung bình luận":**
  - Danh sách comment (mỗi dòng 1 cái)
  - Nút "+ Thêm" → dialog nhập text
  - Swipe to delete từng dòng
  - Nút "Khôi phục mặc định"

**Floating menu — khi đang VISIT mode hiện thêm:**
```
[❤️ Like: − 3 +]  [💬 Comment: − 1 +]
[LIKE] [MIX] [COMMENT]
👤 Profile: 12 / 50
```
Tap [−]/[+] → save ngay vào `LikeSettingsManager`, không cần nút Save.

---

## Thứ tự implement

```
TASK 1 → 2 → 3 → 4   (fix bug Kotlin — làm ngay)
TASK 5                 (dump UI ra Downloads — cần trước khi viết script)
↓
[Tony dump 3 màn hình: contacts, chat, profile → gửi file JSON]
↓
TASK 6                 (ZPEngine primitive)
TASK 7                 (JSON DSL loader)
TASK 8                 (script visit_contacts_v1.json — dùng data từ dump)
TASK 10                (UI cài đặt)
TASK 9                 (script loader từ sungnhon.xyz)
```

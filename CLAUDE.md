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

## DỪNG, BẮT ĐẦU và Tự động (autoStart) — SPEC bắt buộc

> **Đọc section này trước khi sửa start/stop/poll/visit reopen.** Đây là logic product đã chốt — **không** đọc code cũ rồi đoán (đặc biệt đừng gọi `startAutoLike(mode)` mà không phân biệt user vs poll).

### Hai cách BẮT ĐẦU like (product — phải giữ)

| # | User làm gì | `BotStartEntry` | Chuỗi bắt buộc |
|---|-------------|-----------------|----------------|
| **1** | Trong app ZaloPilot bấm **▶ Like Nhật ký** hoặc **▶ Like danh bạ** | `HOME_LIKE_BUTTON` | **Mở Zalo** (`launchZaloMain` nếu chưa foreground) → **chờ** `ZALO_LAUNCH_SETTLE_MS` (~1,2s) → **chờ bottom nav** → **tap tab** đúng mode (Nhật ký / Danh bạ→Bạn bè) → `isRunning=true` → chạy loop |
| **2** | **Tự mở Zalo** (đang ở feed hoặc danh bạ), menu nút **ZP** → **▶ Bắt đầu like** | `FLOATING_ON_ZALO` | **Không** launch lại nếu đã thấy cây `com.zing.zalo` (`pickBestAccessibilityRoot` / `windows`) → chờ menu đóng (`FLOATING_MENU_SETTLE_MS`) → **đúng màn rồi thì chạy luôn** → sai tab thì tap chuyển → chạy loop |

**Không trộn:** Flow (1) luôn `preferExistingZalo=false` + settle sau launch. Flow (2) `preferExistingZalo=true` trước; chỉ launch fallback khi không đọc được Zalo.

**Mode:** (1) truyền `LikeMode` từ nút. (2) `inferLikeModeForStart()` từ cây Zalo, không có thì prefs.

**API:** `requestStartAutoLike(context, mode, entry)` → `startAutoLike(..., startEntry)` → `prepareZaloForCurrentMode(mode, entry)`.

**Settings:** Mỗi lần **bắt đầu chạy** (`startAutoLike`) và mỗi **vòng** `autoLikeLoop` / Visit — gọi `settingsManager.load()` (log `SETTINGS_RELOAD_*`). Chỉ `sessionLikeMode` khóa FEED/VISIT trong **một phiên** đang chạy; delay, comment, session limit, visitLikeCount… luôn lấy prefs mới nhất.

**Lỗi hay gặp:** Gọi `ensureZaloForegroundForBot` với `requireRunningBot=true` **trước** `isRunning=true` → fail dù Zalo đã mở. Đọc `rootInActiveWindow` khi overlay ZP là active → fail; phải `pickBestAccessibilityRoot()`.

### Hành vi product (user-facing)

| Hành động | Ý nghĩa |
|-----------|---------|
| **■ DỪNG** (Trang chủ / menu ZP) | Bot **dừng hẳn**: hủy `likeJob`, visit script, overlay; **không** tự chạy lại khi mở Zalo — kể cả bật **Tự động** trong menu ZP. |
| **▶ BẮT ĐẦU** | User **chủ động** chạy lại → bỏ chặn, bot được phép start. |
| **Tự động** (`LikeSettings.autoStart`) | Khi **không** bị chặn bởi DỪNG: mở Zalo foreground → poll có thể gọi `startAutoLike`. |
| **「Ẩn nút ZP」** | Chỉ tắt `FloatingMenuService` / nút nổi — **không** gọi `stopAutoLike`, **không** set cờ chặn. |

**Sai lầm hay gặp:** User bấm DỪNG nhưng bot vẫn «điên» vì (1) poll auto-start **xóa** cờ chặn mỗi lần `startAutoLike`, (2) `stopAutoLike` return sớm khi `!isRunning` nên không set cờ, (3) visit vẫn `ensureZaloForegroundForBot` mở lại Zalo, (4) cờ chỉ RAM — service restart mất cờ.

### Cơ chế chặn (implementation — phải giữ)

Hai lớp (OR với nhau):

1. **RAM:** `autoStartSuppressedByUser` trong `ZaloPilotAccessibilityService`
2. **Persist:** `LikeSettingsManager.isBotRunSuppressed()` — prefs `zalopilot_runtime` / key `bot_run_suppressed`

**Khi user DỪNG** (luôn qua `AccessibilityHelper.requestStopAutoLike(context)`):

- Ghi prefs `bot_run_suppressed = true` **trước** khi gọi service (service có thể null).
- `stopAutoLike(userRequested = true)`: set cả RAM + prefs; `visitScriptRunning = false`; `startAutoLikeInProgress = false`; cancel `likeJob` **kể cả** khi `isRunning` đã false; broadcast `STATUS_UPDATE` + ẩn overlay.

**Khi user BẮT ĐẦU** (`requestStartAutoLike` / broadcast `ACTION_START_AUTO_LIKE`):

- Clear prefs + RAM **trước** start.
- `startAutoLike(..., userInitiated = true)`.

**Khi poll / autoStart tự bật** (`pollOnce` thấy Zalo foreground + `settingsManager.isAutoStart()`):

- `startAutoLike(mode, userInitiated = false)`.
- **Nếu** `isBotStartBlocked()` → **return ngay**, log `START_BLOCKED_USER_STOPPED` — **không** clear cờ chặn.

**`onServiceConnected`:** đọc `settingsManager.isBotRunSuppressed()` → gán `autoStartSuppressedByUser` (sống qua restart accessibility).

### Chỗ code PHẢI check chặn

| Vị trí | Quy tắc |
|--------|---------|
| `pollOnce` → auto start | `!isBotStartBlocked()` && `userInitiated = false` |
| `startAutoLike` đầu hàm | `!userInitiated && isBotStartBlocked()` → return |
| `startAutoLike` clear cờ | **Chỉ** khi `userInitiated == true` |
| `applyZaloBackground` visit reopen | `!isBotStartBlocked()` trước `ensureZaloForegroundForBot` |
| `autoLikeLoop` pause Zalo away + Visit | Không `ensureZaloForegroundForBot` nếu blocked |
| `ensureZaloForegroundForBot` | `requireRunningBot`: chỉ khi bot đã chạy; `prepareZalo` dùng `requireRunningBot=false`. `isBotStartBlocked()` luôn chặn |
| `prepareZaloForCurrentMode` | Phân nhánh theo `BotStartEntry` — xem bảng «Hai cách BẮT ĐẦU» |

### Dừng nội bộ (không phải user DỪNG)

`stopAutoLike(reason, userRequested = false)` — ví dụ hết feed, daily limit, exception:

- **Không** set `bot_run_suppressed` (user vẫn có thể muốn Tự động chạy lại lần sau mở Zalo).
- Vẫn hủy job và `isRunning = false` khi đang chạy.

### File tham chiếu

- `ZaloPilotAccessibilityService.kt` — `startAutoLike`, `stopAutoLike`, `isBotStartBlocked`, `pollOnce`, `applyZaloBackground`
- `LikeSettingsManager.kt` — `isBotRunSuppressed` / `setBotRunSuppressed`
- `AccessibilityHelper.kt` — `requestStartAutoLike` / `requestStopAutoLike(context)`

---

## Feed like (tab Nhật ký) — SPEC bắt buộc

> **Đọc section này trước khi sửa `runFeedMode` / feed like.** Đây là logic product owner đã chốt; **không** thay bằng suy diễn từ code cũ (`findLikeButtons` toàn màn, `isAlreadyLiked`, `verifyLikedNearClickArea` làm nguồn truth chính).
>
> **Trạng thái code:** spec này có thể **chưa** khớp implementation — implement/sửa feed phải bám spec; sau khi sửa cập nhật CHANGELOG.

**Nguồn truth (chỉ trên cùng một feed item / footer bài, quanh nút Thích):** có **ô bình luận** hay không (placeholder «Nhập bình luận» / composer inline / `cmtinput_text` trong footer item — **không** chỉ ô đã mở focus).

| Giai đoạn | Có ô bình luận trên item? | Hành động |
|-----------|---------------------------|-----------|
| **Trước tap** | Có | Đã like → **skip**, không tap Thích |
| **Trước tap** | Chưa | Tap Thích (case 1: chưa like; case 2: đã like nhưng Zalo chưa kịp hiện ô — chưa phân biệt được ở bước này) |
| **Sau tap** (không cuộn ngay; delay; đọc lại **cùng item**) | Có | Like lần đầu thật → `progressManager.incrementAndSave()` → broadcast `PROGRESS_UPDATE` → **rồi mới** cuộn |
| **Sau tap** (cùng item) | Vẫn không | Tap vừa rồi = **unlike** → **tap Thích lại** → khi có ô → `incrementAndSave()` → cuộn |

**Counter «Đã like»:** tăng khi xác nhận like thật theo bảng trên (có ô sau check / sau re-like). **Không** chỉ tăng khi `verifyLikedNearClickArea` / text «Đã thích» / `isAlreadyLiked` pass.

**Sau like thành công:** **không** `scrollFeedWithVerification()` ngay — chờ UI → re-scan item → xử lý re-like nếu cần → mới cuộn + `delayFeedSettleAfterScroll`.

**Không** dùng làm quyết định chính cho feed: list `findLikeButtons()` cả màn rồi like «nút đầu tiên»; `postKey` / author session skip thay cho ô bình luận.

**Visit / profile:** vẫn có thể dùng `isAlreadyLiked()` (layout khác feed) — section §7 bên dưới áp dụng **không** override spec feed này.

---

## Feed comment (tab Nhật ký) — SPEC bắt buộc

> **Đọc section này trước khi sửa comment feed / `runFeedCommentsAfterLike`.** Cài đặt UI: `visitActionMode` = `LIKE_ONLY` | `COMMENT_ONLY` | `MIX` (+ `feedCommentCount` cho số lần gửi/bài ở MIX).
>
> **Trạng thái code:** spec có thể **chưa** khớp — hiện COMMENT_ONLY vẫn quét `findLikeButtons()` rồi neo comment qua nút Thích → dễ lỗi gửi + status `unknown` (không đọc được `getAuthorName`).

**Cuộn:** giống feed like — cùng `autoLikeLoop`, `FeedMode` (SCROLL / MANUAL / MIX), `delayFeedSettleAfterScroll` sau cuộn. **Không** logic skip phức tạp cho comment (không skip author session, không skip theo ô bình luận / postKey cho chế độ comment).

**Nguyên tắc:** **bài đang xử lý = bài comment đó** — comment tới bài nào thì comment bài đó, xong (hoặc fail rõ) rồi mới cuộn.

| `visitActionMode` | Trên mỗi bài (mỗi vòng quét / cuộn) |
|-------------------|--------------------------------------|
| **LIKE_ONLY** (1) | Chỉ **§ Feed like** — không gửi comment |
| **COMMENT_ONLY** (2) | Tìm **nút Bình luận** trên item → tap mở → gõ (`visitCommentList`) → Gửi → cuộn. **Không** dùng `findLikeButtons()` làm đích chính |
| **MIX** (3) | **Like** theo § Feed like (ô bình luận) **rồi** comment **cùng bài** (`feedCommentCount` lần nếu > 0) |

**Luồng comment (2 & 3):**
1. Neo = **nút/icon Bình luận** trong footer item (hoặc item feed đang xử lý), **không** neo qua nút Thích rồi `findCommentButton(likeNode)` như workaround chính.
2. Tap mở (inline / placeholder / bottom sheet) → `fillCommentInputAndSend` → chỉ báo thành công khi **gửi được** (`sent=true`); **không** báo «Comment xong» nếu `SET_TEXT_FAIL` / `SEND_FAIL` / không mở được UI.
3. Status/UI: không phụ thuộc tên tác giả — tránh `unknown` vì `getAuthorName` null; dùng kiểu «Comment bài này» / log `feed_comment` + `sent=…`.

**Cài đặt:** `feedCommentCount = 0` → MIX chỉ like, không gửi comment sau like. COMMENT_ONLY vẫn comment (ít nhất 1 lần/bài khi mode bật).

**Tách với § Feed like:** ô bình luận ở section like = **nhận biết đã like / verify**; ở section comment = **mở UI để gõ** — không trộn skip like vào comment.

---

## Visit danh bạ (`LikeMode.VISIT`) — SPEC bắt buộc

> **Đọc section này trước khi sửa `visitScriptLoop`, `ZPScriptRunner`, `runProfileLikeLoop`, `tapContactAt`, `tapProfileEntry`.** Script: `visit_contacts_v1.json`. Cài đặt: `visitLikeCount`, `visitCommentCount`, `visitMaxProfiles`, `visitActionMode` (dùng chung enum với feed).
>
> **Trạng thái code:** spec **chưa** khớp — dump UI + máy test (2026-05) xác nhận lỗi dưới.

### Luồng đúng (product)

1. **Danh bạ → tab Bạn bè** — `findContactItems`: gom **các hàng bạn đang hiện** trên màn (không phải cả danh bạ 2700+ một lúc).
2. **Xử lý tuần tự** người **1 → 2 → 3…** trên list hiện tại — **không** «1 người → cuộn danh bạ → tap lại hàng đầu» (gây nhảy cóc 4–5, bỏ 2–3).
3. **Hết người trên màn** (đã visit/like/comment xong) → **cuộn danh bạ một lần** → lấy batch mới → lặp.
4. Mỗi người: tap contact → **bắt buộc mở trang cá nhân** (timeline / tab **Bài viết**) — **không** dừng coi như xong khi chỉ ở **chat** (thẻ giữa «Bắt đầu chia sẻ những câu chuyện…», `chatinput_text`).
5. Trên **profile thật** (cover, bio, tab Ảnh/Video, timeline bài): **cuộn timeline** → like/comment theo mode → **BACK** về danh bạ (hoặc `openContactsFriends`) → người kế.
6. Giới hạn phiên: `visitMaxProfiles` (mặc định 50). `visitIndex` = đếm tiến độ lưu prefs — **phải** dùng để chọn hàng đúng thứ tự, không chỉ đếm.

### Màn hình — phân biệt bắt buộc

| Màn | Dấu hiệu (dump / UI) | Được làm gì |
|-----|----------------------|-------------|
| **Danh bạ** | `maintab_contact`, list bạn | `findContactItems`, tap hàng N |
| **Chat 1–1** | `main_chat_view`, `chatinput_text`, thẻ giữa | **Chỉ bước qua** — tap tên/ảnh action bar hoặc thẻ → mở profile |
| **Profile** | `profile_avatar`, timeline, `feedItemFooterBarModule` / bài | `likeProfilePosts`, cuộn, like/comment |

**Chat ≠ profile.** Không gọi `likeProfilePosts` / cuộn timeline khi còn `chatinput_text` và chưa thấy timeline bài.

### Like / comment trên profile

| `visitActionMode` | Hành vi |
|-------------------|---------|
| **LIKE_ONLY** | Like tối đa `visitLikeCount` bài / profile |
| **COMMENT_ONLY** | Comment (neo **nút Bình luận**, không `findLikeButtons` chính) |
| **MIX** | Like rồi comment cùng bài (`visitCommentCount`) |

**Không** áp dụng § Feed like (ô bình luận feed) trên profile timeline — layout khác feed Nhật ký.

**Like profile — nhận diện nút (từ dump):** `feedItemFooterBarModule` có thể chỉ có **text gộp** (`"9 bạn\n2 bình luận\nThích\n"`) với **`childCount: 0`** — không có `btn_like_text` con. Code cũ (`findProfileLikeButtons` → chỉ subtree whitelist) → **EMPTY** → cuộn vô ích. Spec: tap **vùng footer** / parse text «Thích» khi không có node con.

**Sau mỗi bài like (profile):** cuộn timeline tìm bài kế — **chỉ sau** khi đã xử lý bài đang thấy; **không** cuộn mãi khi chưa vào đúng màn profile.

### Điều khiển bot (Visit)

- Start/stop/DỪNG/autoStart: xem section **«DỪNG, BẮT ĐẦU và Tự động»** ở đầu file (áp dụng cả Feed lẫn Visit).
- Visit + rời Zalo: chỉ `ensureZaloForegroundForBot` khi bot đang chạy **và** không bị user DỪNG chặn.

### Script tham chiếu (`visit_contacts_v1.json` v11+)

`s1` findContactItems (batch, cuộn khi hết batch) → `s2` tapContactAt (ordinal trong batch) → wait → `s4` tapProfileEntry (`isProfileTimelineReady`) → wait → `s6` likeProfilePosts (footer text gộp) → … → `s9` scrollContacts (**bỏ qua giữa batch** trong runner) → `visitIndex++` → goto `s1`.

---

## Các quyết định thiết kế quan trọng — KHÔNG thay đổi

### 1. Không lưu danh sách bài đã like vào database

Bot **không lưu** danh sách bài đã like. Mỗi lần chạy đọc trạng thái thực từ màn hình Zalo.

**Lý do:** Feed Zalo không có stable ID. Lưu dễ gây false positive (bỏ qua bài chưa like). Cuộn qua bài đã like nhanh hơn maintain database có thể sai.

**Feed (Nhật ký):** phân biệt đã like / like thật / unlike → **§ Feed like — ô bình luận** (trên).

**Visit / profile:** `NodeFinder.isAlreadyLiked()` — nhãn nút like / `btn_like_text`, `isChecked`/`isSelected`; không suy từ reaction count hay `reaction_info` của người khác.

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

**Feed:** verify = **§ Feed like** — đọc lại item, ô bình luận, re-like nếu cần; **không** cuộn trước khi xong bước đó.

**Visit / profile (và fallback feed nếu spec chưa implement):** sau `performLikeClickWithFallbacks()` thành công, delay ~1500ms (+ retry) rồi xác nhận «Đã thích» / `isAlreadyLiked`. `CLICK_UNCONFIRMED` → không `incrementAndSave()`.

### 7. isAlreadyLiked() — Visit / profile (không phải feed)

**Feed Nhật ký:** **không** dùng làm nguồn truth chính — xem **§ Feed like**.

**Visit / profile:** mọi logic «đã like / chưa like» qua `NodeFinder.isAlreadyLiked()`. Không duplicate ở chỗ khác. Chỉ coi **tài khoản hiện tại** đã like — không suy từ reaction count / `reaction_info` của người khác.

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

### Start / Stop bot (bắt buộc — xem SPEC «DỪNG, BẮT ĐẦU»)

```kotlin
// SAI — poll hoặc auto gọi start mà vô tình bỏ chặn sau user DỪNG
fun startAutoLike(mode: LikeMode) {
    autoStartSuppressedByUser = false  // KHÔNG được clear ở đây cho mọi caller
    ...
}

// SAI — user DỪNG nhưng không persist / return sớm
fun stopAutoLike() {
    if (!isRunning) return  // bỏ qua set bot_run_suppressed
}

// ĐÚNG — UI DỪNG
AccessibilityHelper.requestStopAutoLike(context)  // prefs true trước

// ĐÚNG — poll auto
startAutoLike(mode, userInitiated = false)  // bị chặn nếu isBotStartBlocked()

// ĐÚNG — user BẮT ĐẦU
startAutoLike(mode, userInitiated = true)   // clear suppress rồi mới chạy
stopAutoLike(userRequested = true)
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

## CI-safe Kotlin — tránh lỗi build “cú pháp”

Các lỗi dưới đây hay **lọt qua local** nhưng **fail trên CI** (Kotlin FIR/Gradle strict). Khi sửa code (đặc biệt trong `ZaloPilotAccessibilityService.kt`) phải tự check:

### 1) Import Android util/time rõ ràng
- Dùng `SystemClock.elapsedRealtime()` → luôn có `import android.os.SystemClock`

### 2) Tuyệt đối không `break/continue` trong inline lambda
Không viết kiểu:

```kotlin
val x = foo() ?: run { continue }
list.forEach { if (cond) return@forEach /* hoặc continue */ }
```

Thay bằng `if` cùng scope vòng lặp:

```kotlin
val x = foo()
if (x == null) continue
```

### 3) Range `in` phải đồng nhất kiểu Long/Int
- Đúng: `sinceBack in 0L..1_000L`
- Sai: `sinceBack in 0..1_000L` (trộn Int/Long)

### 4) Tránh “trick” Elvis phức tạp trong code loop
Trong loop ưu tiên code rõ ràng:
- `val x = ...; if (x == null) { ...; continue }`
Thay vì chain `?: run { ... }` dài.

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
- KHÔNG gọi `startAutoLike` từ poll/auto mà **không** truyền `userInitiated = false` hoặc **clear** cờ chặn khi user đã DỪNG
- KHÔNG coi **Ẩn nút ZP** là DỪNG bot — phải `requestStopAutoLike(context)` / `stopAutoLike(userRequested = true)`
- KHÔNG `ensureZaloForegroundForBot` / visit reopen khi `isBotStartBlocked()` — sau DỪNG user không muốn app tự mở Zalo chạy tiếp

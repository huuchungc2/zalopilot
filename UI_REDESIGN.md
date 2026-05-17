# ZaloPilot — Redesign UI theo phong cách iOS

> **Bắt buộc từ nay:** mọi thay đổi UI Compose / overlay phải tuân file này.  
> Cursor rule: `.cursor/rules/ui-ios-redesign.mdc`  
> Implementation: `app/src/main/java/com/zalopilot/app/ui/ZaloPilotUi.kt`

## Mục tiêu

UI gọn kiểu iOS: nền xám nhạt, card trắng bo tròn, typography rõ, bottom nav icon outline Material3, **không dùng emoji làm icon** (tab, empty state, setup step).

---

## Design tokens (`ZpColors`)

```kotlin
// ZaloPilotUi.kt — object ZpColors
val BgPage = Color(0xFFF2F2F7)       // nền toàn trang
val BgCard = Color.White
val BgSecondary = Color(0xFFE5E5EA)  // stat item, nút phụ, track progress

val AccentBlue = Color(0xFF007AFF)   // KHÔNG dùng #0068FF (zaloBlue cũ)
val AccentPurple = Color(0xFFAF52DE) // Like danh bạ

val TextPrimary = Color(0xFF000000)
val TextSecondary = Color(0xFF8E8E93)
val TextBlue = Color(0xFF007AFF)

val ColorGreen = Color(0xFF34C759)
val ColorRed = Color(0xFFFF3B30)
val ColorOrange = Color(0xFFFF9500)

val Divider = Color(0xFFE5E5EA)      // 0.5dp giữa row trong card
```

Overlay View (`FloatingMenuService`): cùng hex — `#007AFF`, `#AF52DE`, `#34C759`, `#FF3B30`.

---

## Composable dùng chung (`ZaloPilotUi.kt`)

| Composable | Mục đích |
|------------|----------|
| `IosScreenTitle(title, subtitle?)` | Tiêu đề 28sp Bold + subtitle 15sp |
| `IosSectionLabel(text)` | Label section uppercase, top 20dp |
| `IosCard(onClick?, contentPadding)` | Card trắng 16dp, border 0.5dp, elevation 0 |
| `IosSecondaryButton(text, onClick)` | Nút phụ BgSecondary, min height 44dp |
| `iosSliderColors` / `iosSwitchColors` | Slider & Switch checked = AccentBlue |

---

## Layout chung

- Nền: `Modifier.background(ZpColors.BgPage)`
- LazyColumn: `contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)`, `spacedBy(12.dp)`
- **Không** header xanh `Box(background = AccentBlue)` ở đầu tab
- Mô tả phụ: `ZpColors.TextSecondary`, không `Color.Gray`

---

## Bottom Navigation

`NavigationBar(containerColor = ZpColors.BgCard)` + `Icons.Outlined.*`:

| Tab | Icon |
|-----|------|
| Trang chủ | `Home` |
| Cài đặt | `Settings` |
| Bình luận | `ChatBubbleOutline` |
| Nhật ký | `Article` |
| Script | `Code` |
| UI | `AccountTree` |

Selected: `AccentBlue` · Unselected: `TextSecondary`

---

## HomeScreen

- Title **ZaloPilot** 28sp Bold `TextPrimary` + **Auto Like Zalo** 15sp `TextSecondary`
- Badge pill `BgSecondary`: dot `ColorGreen` / `ColorRed` + **Đang chạy** / **Đã dừng**
- Card nút: **Like nhật ký** (`AccentBlue`) + **Like danh sách** (`AccentPurple`) 52dp; hàng phụ **Mở Zalo** / **Bật nút nổi** `BgSecondary` 44dp
- Khi chạy: nút **■ Dừng lại** full width `ColorRed` 52dp
- **TIẾN ĐỘ HÔM NAY**: `IosSectionLabel` + `IosCard` — 3 stat `BgSecondary`, progress 5dp `AccentBlue`
- Link **Cài đặt** / **Bình luận** (`TextButton` AccentBlue), không gộp click cả card tiến độ
- **TỰ CHẠY**: card `contentPadding = 0`, `HorizontalDivider` 0.5dp — Tự động chạy lại + Ngủ trưa

---

## SettingsScreen

- `IosScreenTitle("Cài đặt", version)`
- Section: Giới hạn like, Tốc độ, Tiết kiệm pin, Pin & sạc (divider trong card), Like danh bạ, Chế độ feed
- Comment → tab **Bình luận** (không nhét danh sách câu dài vào đây)
- Nút **Lưu cài đặt** full width `AccentBlue` 14dp radius

---

## CommentScreen

- `IosScreenTitle("Bình luận", …)`
- Chế độ LIKE_ONLY / COMMENT_ONLY / MIX, slider feed & visit comment, `OutlinedTextField` danh sách câu
- **Lưu bình luận** riêng; merge qua `settingsManager.load()` để không ghi đè tab khác

---

## LogScreen

- Title **Nhật ký** 28sp (không header xanh)
- `TabRow` `BgCard` / tab selected `AccentBlue`
- Hàng action: `IosSecondaryButton`
- Toggle trong `IosCard` + divider
- Log card `RoundedCornerShape(12.dp)`; border đỏ chỉ khi failure
- Empty: chữ thuần, không emoji

---

## UiTreeScreen

- Title **UI Tree** 28sp
- Nút Quét / Quét UI / Load lại: `IosSecondaryButton`
- Filter bar: nền `BgCard`
- Node highlight giữ (xanh lá Thích, xanh dương Bình luận); badge **TAP** / **ON** thay emoji

---

## SetupWizard

- Nền `BgPage`, title `TextPrimary`
- `StepCard` với `Icon` Material (`AccessibilityNew`, `Layers`, `CheckCircle`) — không emoji ♿🪟✅
- `IosCard` + nút `AccentBlue`

---

## ScriptScreen

- `IosScreenTitle` + `IosCard` + `AccentBlue` buttons — không header xanh, không param `zaloBlue`

---

## Checklist trước khi merge UI

- [ ] Không `#0068FF` / `zaloBlue` / `Color.Gray` trong UI mới
- [ ] Dùng `IosCard` / `IosSectionLabel` / `ZpColors`
- [ ] Không emoji icon tab hoặc empty state
- [ ] Logic bot không đổi khi chỉ đổi style

---

## Files

1. `ui/ZaloPilotUi.kt` — tokens + composable
2. `ui/MainActivity.kt` — màn chính
3. `ui/ScriptScreen.kt`
4. `floating/FloatingMenuService.kt` — màu overlay khớp token (View, không Compose)

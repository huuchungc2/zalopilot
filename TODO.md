# ZaloPilot — TODO

## ✅ HOÀN THÀNH

- [x] Setup Android project, Kotlin, Hilt, Compose
- [x] ZaloPilotAccessibilityService — core loop
- [x] NodeFinder — tìm node với fallback
- [x] ZaloUIScanner — tự học resource-id
- [x] ZaloIDStore — lưu ID đã học
- [x] LikeSettingsManager — cài đặt sale
- [x] LikeProgressManager — tiến độ theo ngày
- [x] Logger — ghi log ra file
- [x] FloatingMenuService — nút ZP nổi trên Zalo
- [x] MainActivity — Compose UI 3 tab
- [x] Setup Wizard — hướng dẫn cấp quyền tự động
- [x] Status Overlay — hiện realtime trên Zalo
- [x] Toast feedback — thông báo mỗi action
- [x] WakeLock — giữ CPU khi bot chạy
- [x] Detect Zalo foreground/background
- [x] Auto mode / Manual mode
- [x] FeedMode (SCROLL / MANUAL / MIX) — prefs `feed_mode` + UI Cài đặt + `autoLikeLoop`
- [x] Click like — log chi tiết, parent ACTION_CLICK, gesture fallback, dedupe bounds, `isVisibleToUser`
- [x] NodeFinder — `getAuthorName` không trả về text nút Thích; `shouldLike` + `btn_like` không text
- [x] UI map — export/import `ui_map.json` + nút "Quét UI" (force scan & export)
- [x] GitHub Actions build APK
- [x] App icon
- [x] Counter **Đã duyệt** (like + skip/lỗi) + hiển thị Trang chủ/menu nổi
- [x] Tab **Nhật ký** — danh sách log cuộn/chạm được (weight + LazyColumn fillMaxSize)

---

## 🔴 ƯU TIÊN CAO — Cần làm ngay

### Debug & Test
- [x] `NodeFinder.debugDump(root)` — in toàn bộ node tree ra log để debug
- [x] Dump feed item UI tree (liked/unliked) ra filesDir + export Downloads trong tab Nhật ký
- [ ] Màn hình debug trong app — hiện ID đã học được từ ZaloIDStore
- [ ] Test thực tế trên máy Samsung — xác nhận tìm được nút Thích
- [ ] Xử lý trường hợp Zalo đang ở sai tab → tự navigate sang tab Nhật ký

### Ổn định
- [x] Giữ màn hình khi bot chạy — `FLAG_KEEP_SCREEN_ON` trên status overlay
- [x] Chế độ Eco — poll/scan/delay chậm hơn, màn tắt nới ngưỡng root null
- [x] `isAlreadyLiked` — reaction_info qua id+bounds (không `===` sibling), quét cây con parent
- [x] Cuộn feed — `ACTION_SCROLL_FORWARD` trên RecyclerView đã học + fallback vuốt có callback
- [x] Retry khi click fail — gesture fallback sau ACTION_CLICK (đã có trong service)
- [x] Cuối feed / cuộn không đổi — `consecutiveScrollNoProgress` + dừng sau 5 (ALL_SKIPPED & cảnh báo LIKE)
- [x] Dừng khi không thấy nút Thích lặp lại (`NO_BUTTONS` × 5 — có sẵn)
- [x] Chống unlike nhầm: sau click verify + delta composer (xuất hiện/biến mất) → confirmed hoặc re-click; rule cũ "no composer → re-click" giữ làm fallback
- [x] Không pause/stop nhầm khi có heads-up notification/SystemUI overlay nổi lên khi bot đang chạy
- [x] Vào nhầm full-screen "Bình luận" → tự `GLOBAL_ACTION_BACK` 1–2 lượt + skip bài; dừng bot nếu kẹt 3 lần liên tục
- [x] InteractMode (TAP/MIX) chi phối cả cuộn feed (TAP ⇒ vuốt tay, MIX ⇒ random); thêm toggle "VUỐT TAY KHI CUỘN" override
- [x] Counter "Đã like" tự update ở UI/floating menu (Android 13+ — `RECEIVER_NOT_EXPORTED` + `setPackage` cho broadcast nội bộ)
- [x] Tiết kiệm pin: toggle "Chỉ chạy khi cắm sạc" + "Pause khi pin thấp" (ngưỡng tùy chỉnh) + "Tiết kiệm khi rời Zalo" (pause-không-stop, slow poll 10–20s, tự resume khi mở Zalo lại)
- [x] Fix overlay KEEP_SCREEN_ON: khi pause-rời-Zalo phải gỡ flag để màn tự tắt (đỡ hao pin); resume khi quay lại Zalo
- [x] Fix slow-poll grace 5s: tránh resume chậm khi user vô tình rời Zalo trong vài giây
- [x] Fix Bug like→unlike + count không update: tách finder verify (`findLikeAreaNodeAt`) khỏi `findLikeButtons` (vốn lọc isAlreadyLiked), tăng verify 3-pass, không re-click khi không có evidence
- [ ] Handle trường hợp Zalo hiện popup quảng cáo / dialog → tự đóng

---

## 🟡 ƯU TIÊN TRUNG BÌNH

### Scripting (Engine + JSON DSL) — giảm build APK
- [ ] Phase 0: tách “engine” primitive (touch-first): `acquireRoot`, `ensureForeground`, `findText/findId/exists`, `tap/tapCenter`, `swipe`, `scroll`, `back`, `inputText`, `wait`
- [ ] Phase 1: JSON DSL tối thiểu (`steps[]`, `assign/$var`, `ifExists`, `repeat`, `timeout`) + log step-by-step (`stepId/result/elapsedMs`)
- [ ] Phase 2: loader tải script từ URL (GitHub raw) + cache local + màn hình “Script” (nhập URL/chọn file/Test run/Log)
- [ ] Phase 3: Zalo primitives để script ngắn: `likeCurrentPost` (anti-unlike + verify), `commentCurrentPost`, `openChat/sendChat`, `createPost`, `createStory`
- [ ] Phase 4: migrate auto-like feed → DSL trước, rồi chuyển dần comment/chat/post/story; thiếu capability thì thêm primitive, không sửa core loop nhiều
- [ ] Phase 5 (tuỳ): nếu DSL thiếu linh hoạt mới cân nhắc JS runtime nhỏ, sandbox chỉ gọi `zp.*`

### UI cải thiện
- [ ] Tab Nhật ký trong app — log tự refresh realtime (hiện phải bấm tab mới update)
- [ ] Màn hình chính hiện trạng thái ZaloUIScanner — đã học được bao nhiêu ID
- [ ] Floating button có thể kéo thả (đã có code nhưng chưa test)
- [ ] Notification persistent hiện "Đã like X/100 hôm nay"

### Cài đặt
- [ ] Reset tiến độ — nút xóa dữ liệu hôm nay
- [ ] Export log — share file log qua Zalo/email

---

## 🟢 GIAI ĐOẠN 2 — Visit Mode

- [ ] Implement Visit Mode:
  - [ ] Vào tab Danh bạ
  - [ ] Đi vào từng trang cá nhân theo visitIndex
  - [ ] Like bài mới nhất
  - [ ] Lưu visitIndex sau mỗi người
  - [ ] Hôm sau tiếp tục từ chỗ dừng
- [ ] UI toggle Feed/Visit trong Settings

---

## 🟢 GIAI ĐOẠN 3 — Auto Chat

- [ ] NotificationListener — bắt tin nhắn Zalo
- [ ] AI Client — gọi Gemini Flash API
- [ ] ChatContextManager — lưu lịch sử per-customer
- [ ] Auto reply — điền text + click gửi
- [ ] Training Module UI — sale paste chat mẫu

---

## ⚙️ KỸ THUẬT CẦN LÀM BẤT CỨ LÚC NÀO

- [ ] ProGuard rules cho release build
- [ ] Build release APK (signed)
- [ ] README hướng dẫn cài đặt cho sale
- [ ] Tối ưu kích thước APK (hiện 9MB debug)

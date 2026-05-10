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
- [x] GitHub Actions build APK
- [x] App icon

---

## 🔴 ƯU TIÊN CAO — Cần làm ngay

### Debug & Test
- [x] `NodeFinder.debugDump(root)` — in toàn bộ node tree ra log để debug
- [ ] Màn hình debug trong app — hiện ID đã học được từ ZaloIDStore
- [ ] Test thực tế trên máy Samsung — xác nhận tìm được nút Thích
- [ ] Xử lý trường hợp Zalo đang ở sai tab → tự navigate sang tab Nhật ký

### Ổn định
- [x] `isAlreadyLiked` — reaction_info qua id+bounds (không `===` sibling), quét cây con parent
- [x] Cuộn feed — `ACTION_SCROLL_FORWARD` trên RecyclerView đã học + fallback vuốt có callback
- [x] Retry khi click fail — gesture fallback sau ACTION_CLICK (đã có trong service)
- [ ] Detect khi bot bị stuck (scroll nhưng không có nút Thích mãi) → dừng báo lỗi
- [ ] Handle trường hợp Zalo hiện popup quảng cáo / dialog → tự đóng

---

## 🟡 ƯU TIÊN TRUNG BÌNH

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

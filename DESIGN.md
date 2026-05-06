# ZaloPilot — Design Document

## Triết lý thiết kế

App chạy **khi và chỉ khi** sale đang cầm điện thoại mở Zalo lên.
Sale đặt máy xuống → app vẫn chạy âm thầm cho đến khi:
- Hết daily limit → tự dừng
- Sale bấm Stop → dừng

Không over-engineer. Không background service phức tạp. Không server.

---

## Tự học ID từ UI Zalo (ZaloUIScanner)

Vấn đề cốt lõi: Zalo update → resource ID đổi → app vỡ.

Giải pháp: **App tự quét UI Zalo và lưu ID vào SharedPreferences.**

```
Sale mở Zalo lên
→ Accessibility đọc UI tree (đang làm sẵn rồi)
→ ZaloUIScanner tìm tất cả element cần thiết bằng text/content-desc
→ Lưu resource-id thực tế vào SharedPreferences
→ Muốn like → lấy ID đó ra dùng luôn
→ Zalo update → ID cũ fail → tìm lại bằng text → lưu ID mới → tự heal
```

Elements cần scan:
- `like_button` — text "Thích" / content-desc "Thích"
- `tab_timeline` — text "Nhật ký"
- `feed_recycler` — class RecyclerView trong feed
- `author_name` — node tên người đăng bài (parent của like button)
- `contact_list` — RecyclerView trong tab Danh bạ
- `contact_item` — từng item trong danh bạ

---

## 2 Chế độ điều khiển

| Chế độ | Hành vi |
|--------|---------|
| **Auto** | Zalo lên màn hình → chạy like luôn |
| **Manual** | Phải bấm Start trên floating button |

---

## 2 Chế độ like

### Feed Mode
```
Lướt tab Nhật ký
→ Tìm nút "Thích" chưa like
→ Check tên tác giả — nếu đã like người này trong session → skip
→ Like → lưu tên tác giả vào likedAuthorsThisSession
→ Scroll xuống → lặp lại
```

### Visit Mode (build sau)
```
Vào tab Danh bạ
→ Đi vào từng trang cá nhân
→ Like bài mới nhất
→ Lưu index đã đi tới đâu
→ Hôm sau tiếp tục từ đó
```

---

## Xử lý màn hình tắt / Zalo ngủ

```
rootInActiveWindow == null → Zalo có thể đang ngủ
→ Đợi 10 giây → thử lại
→ Accessibility chạm vào → Zalo tự thức
→ Chạy tiếp bình thường
```

Không dùng WakeLock trước. Nếu thực tế Zalo ngủ quá nhiều → thêm sau.

---

## Floating Button Menu

```
[ZP] bấm vào →
  ▶ Bắt đầu / ■ Dừng
  🔄 Feed Mode / Visit Mode
  ⚙ Auto / Thủ công
  📊 Hôm nay: 45/100
  ✕ Đóng
```

---

## Giới hạn an toàn (giữ nguyên)

- Daily limit (default 100)
- Delay ngẫu nhiên giữa các like
- Session rest sau N like liên tiếp
- Quiet hour (không chạy ban đêm)

---

## Files thay đổi

| File | Thay đổi |
|------|----------|
| `ZaloUIScanner.kt` | **MỚI** — tự quét lưu ID |
| `ZaloIDStore.kt` | **MỚI** — lưu/đọc ID từ SharedPreferences |
| `NodeFinder.kt` | Dùng ID từ ZaloIDStore thay vì selector_config.json |
| `ZaloPilotAccessibilityService.kt` | Thêm likedAuthorsThisSession, auto/manual mode, retry khi null |
| `LikeSettingsManager.kt` | Thêm autoStart, likeMode (FEED/VISIT) |
| `FloatingMenuService.kt` | Thêm menu mode + auto/manual toggle |
| `selector_config.json` | Giữ làm seed data lần đầu, không còn là source of truth |

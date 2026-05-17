# Visit — like trên profile timeline

**Code:** `ZPEngine.runProfileLikeLoop`, `prepareProfileTimelineForLikes`, `NodeFinder.findProfileLikeButtons`, script step `likeProfilePosts`.

**Toast:** mỗi bước quan trọng gọi `profileToast` / `showToast` — user nhìn màn hình biết bot đang làm gì / lỗi gì.

---

## Chuẩn bị (trước vòng like)

1. Nếu **chat** → tap thẻ giữa / tên action bar → chờ `isProfileTimelineReady`.
2. Nếu **chưa timeline** → tab **Bài viết** (`ACTION_CLICK`, không gesture) + **cuộn** timeline (không chỉ `delay`).
3. Có `feedItemFooterBarModule` hoặc nút Thích → bắt đầu quét.

---

## Một vòng like (`runProfileLikeLoop`)

| Bước | Hành động | Toast ví dụ |
|------|-----------|-------------|
| Chat | `tryOpenProfileFromChat` | `💬 Đang chat — thử mở profile…` |
| Chưa timeline | tab Bài viết + cuộn (tối đa 6 lần) | `⏳ Timeline chưa sẵn sàng…` |
| Quét | `findProfileLikeButtons` + `shouldLike` | `🔍 Quét: ✅ Thấy N nút…` / `❌ Không footer…` |
| Rỗng, đã thích hết vùng | cuộn (streak 5) | `⏩ Bài trên màn đã thích — cuộn…` |
| Rỗng, chưa có Thích | cuộn (tối đa 9) | `📜 Chưa thấy Thích — cuộn (k/9)…` |
| Có nút | tap Thích + verify | `👍 Tap…` → `✅ Like OK` / `⚠️ chưa xác nhận` |
| Hết 9 cuộn | | `❌ Cuộn 9 lần vẫn không có nút Thích` |

**Tìm nút like:** footer `feedItemFooterBarModule` → con `btn_like` / text «Thích»; fallback footer text gộp (không dùng ID học từ feed).

**Không** dùng ô bình luận feed để skip like trên profile (chỉ `isAlreadyLiked` / text footer).

---

## Script JSON

`visit_contacts_v1.json` chỉ gọi `tapProfileEntry` → `wait` → `likeProfilePosts`. Logic nằm trong Kotlin.

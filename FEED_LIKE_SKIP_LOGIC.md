# Feed like (Nhật ký) — logic product + quét ô bình luận

**Nguồn truth:** ô bình luận trên **cùng item** bài đang xử lý. Không dùng «Đã thích» / `isAlreadyLiked` trên feed. **Mỗi lần check ô → toast.**

**Lưu bài (`feedItemSavedKeys`):** khi sắp tap (chưa có ô BL). Vòng sau **trùng bài đã lưu** → **chỉ cuộn**, không tap lại. Bài **mới** → cập nhật lưu rồi tap. Xóa khi DỪNG / Start mới.

---

## Một bài / một vòng

### Đọc bài trên cùng màn

| Tình huống | Hành động |
|------------|-----------|
| **Có ô bình luận** | Skip, **cuộn** (không lưu, không tap) |
| **Chưa có ô** + **trùng** `feedItemSavedKeys` | **Chỉ cuộn** (kẹt bài cũ sau tap) |
| **Chưa có ô** + **chưa lưu** hoặc **bài mới** | **Lưu bài** → tap Thích lần 1 → bước 2 → cuộn |

### Bước 2 (sau tap 1, ~1,2s) — quét ô **giống bước 1**

| Kết quả | Hành động |
|---------|-----------|
| **Có ô** | +1 Đã like, **cuộn** |
| **Không ô** | Tap Thích **lần 2** → **cuộn** (không quét lại, không +1) |

**Một dòng:** Có ô → cuộn \| (không ô: lưu → like₁ → đọc lại → có ô: +1 cuộn \| không: like₂ cuộn) \| trùng bài đã lưu → chỉ cuộn.

---

## Cách quét ô bình luận

- API: `hasCommentBoxOnFeedItemNearLike` (bước 1), `hasCommentBoxOnFeedItemNearLikeAt` (bước 2, cùng item theo rect/id).
- **Chính:** `hasInlineCommentComposerNearLikeAnchor` — leo **6 cấp parent** từ Thích, quét subtree («nhập bình luận», `cmtinput`, editable…).
- **Thêm:** `cmtinput` trong footer, hàng dưới `feedItemFooterBarModule`.
- **Không** nhầm chỉ nút «Bình luận» / «2 bình luận».

---

## Lưu bài (RAM)

| Sự kiện | `feedItemSavedKeys` |
|---------|---------------------|
| Start bot / DỪNG | Xóa |
| Sắp tap (không có ô BL) | `rememberFeedItemSaved` — ghi key `CONTENT\|…`, `ANCHOR\|…` |
| Vòng sau, bài trên trùng key | Chỉ cuộn |
| Bài trên khác key | Lưu mới → tap |

**Code:** `rememberFeedItemSaved`, `isSameAsFeedItemSaved`, `pickFirstEligibleFeedLikeNode`, `runFeedMode`, `feedLikeTapAndVerifyOnItem`.

---

## Hiển thị khi bot chạy (một chỗ)

Chỉ **thanh trạng thái overlay** trên Zalo (`updateStatus`) — **không** Toast Android chồng. DỪNG → hủy message chờ; Toast chỉ cho «■ Đã dừng» / «đã kết nối».

| Overlay | Nghĩa |
|---------|--------|
| `⏭ Có ô BL — skip, cuộn` | Bước 1 |
| `⏭ Trùng bài đã lưu — chỉ cuộn` | Kẹt bài cũ |
| `💾 Lưu bài — tap Thích lần 1` | Trước tap |
| `✅ Có ô BL → +1 like` / `👍 Like lần 2…` | Bước 2 |

**Khi đổi logic → cập nhật file này.**

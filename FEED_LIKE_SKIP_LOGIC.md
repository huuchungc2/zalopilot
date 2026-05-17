# Feed like (Nhật ký) — logic product

**Nguồn truth:** ô bình luận trên **cùng item**. Không lưu danh sách đã like. Không «Đã thích» / `isAlreadyLiked`.

**Mỗi lần check ô → toast.**

---

## Một bài / một vòng (2 bước)

**1. Check ô** (trước tap)  
- **Có** → skip, **cuộn** (bài khác).  
- **Không** → tap **Thích** lần 1.

**2. Dừng (~1,2s), đọc lại cùng item** (sau tap 1)  
- **Có ô** → **+1 Đã like** → **cuộn** (bài khác).  
- **Không ô** → tap **Thích lần 2** (tap 1 có thể đã unlike nhầm) → **cuộn** luôn (không đọc lại sau lần 2, không +1 nếu chưa thấy ô).

---

## Một dòng

Check ô → skip | like → đọc lại → (có ô: +1, cuộn) | (không: like lần 2, cuộn).

**Code:** `runFeedMode`, `feedShouldAttemptLike`, `feedLikeTapAndVerifyOnItem`.

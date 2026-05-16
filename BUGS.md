# ZaloPilot — Bug Fix Tasks

---

## Task 1 — Profile không tìm được nút like (CRITICAL)

**File:** `app/src/main/java/com/zalopilot/app/accessibility/NodeFinder.kt`
**Hàm:** `findProfileLikeButtons`

**Vấn đề:**
`findProfileLikeButtons` đang gọi `findLikeButtons` — hàm này luôn thử `savedId = idStore.getLikeButtonID()` (ví dụ `com.zing.zalo:id/btn_like_text`) trước tiên. ID này được học từ feed nhật ký, không tồn tại trên layout profile bạn bè → miss → fallback cũng không tìm được → `liked=0 noPosts=true` với mọi người dù tab bài viết đã mở đúng.

**Bằng chứng từ log:**
```
ID_MISS_FALLBACK (btn_like_text)
findLikeButtons → EMPTY   (lặp streak 1..6)
PROFILE_NO_POSTS
liked=0 noPosts=true
```

**Yêu cầu fix:**
Tách `findProfileLikeButtons` thành hàm scan riêng, **bỏ qua bước lookup `idStore.getLikeButtonID()`**, chỉ dùng text scan + traversal (giống phần fallback hiện tại của `findLikeButtons`). savedId chỉ hợp lệ trên feed nhật ký, không dùng trên profile.

---

## Task 2 — Gesture tap nhầm SystemUI làm bot dừng (CRITICAL)

**File:** `app/src/main/java/com/zalopilot/app/accessibility/engine/ZPEngine.kt`
**Hàm:** `tapProfilePostsTabIfNeeded`

**Vấn đề:**
Hàm đang dùng `tap()` (gesture tọa độ màn hình) để click tab bài viết trên profile. Gesture bị Android dispatch nhầm vào `com.android.systemui` overlay (status bar / notification shade đang đè lên vùng tap) → window state thay đổi → service nhận `BACKGROUND_EVENT` → coi là thoát Zalo → cancel toàn bộ visit job.

**Bằng chứng từ log:**
```
09:42:32.138  CLICK profile_tab → POSTS_TAB → pkg=com.android.systemui
09:42:32.455  BACKGROUND_EVENT pkg=com.zalopilot.app → reason=window_state_non_zalo
09:42:33.252  STOPPED
09:42:33.260  visitScriptLoop → StandaloneCoroutine was cancelled
```

**Yêu cầu fix:**
Trong `tapProfilePostsTabIfNeeded`, đổi từ `tap()` (gesture) sang `tab.performAction(AccessibilityNodeInfo.ACTION_CLICK)` trực tiếp trên node. `ACTION_CLICK` không phụ thuộc tọa độ màn hình nên không bị overlay chặn.

---

## Task 3 — `goto` đếm profile kể cả khi step trước fail

**File:** `app/src/main/java/com/zalopilot/app/accessibility/engine/ZPScriptRunner.kt`
**Hàm:** `run()`

**Vấn đề:**
`profilesDone++` trong nhánh `"goto"` chạy bất kể `ok` của các step trước (tapContactAt, likeProfilePosts...) có thành công hay không. Bot đếm profile "đã xong" dù thực ra chưa thăm được ai.

**Yêu cầu fix:**
Chỉ tăng `profilesDone` khi tất cả step trước `goto` trong vòng đó thành công. Có thể dùng một flag `roundSuccess` set `false` khi bất kỳ step critical nào fail, rồi check flag đó trước khi `profilesDone++`.

---

## Task 4 — `visitIdx % size` có thể tap lặp cùng 1 người

**File:** `app/src/main/java/com/zalopilot/app/accessibility/engine/ZPScriptRunner.kt`
**Hàm:** `runTapContactAt()`

**Vấn đề:**
`visitIdx` là giá trị cộng dồn lưu persistent qua các session. Sau mỗi lần scroll, danh sách contact trên màn hình thay đổi (số lượng, thứ tự). `visitIdx % targets.size` có thể trỏ về cùng 1 index nhiều vòng liên tiếp nếu `targets.size` không thay đổi → bot tap lặp cùng 1 người.

**Yêu cầu fix:**
Dùng một counter tương đối trong session (reset về 0 mỗi lần `findContactItems` chạy lại) thay vì dùng trực tiếp `visitIdx % size`. `visitIdx` chỉ dùng để track tiến độ lưu storage, không dùng làm index trực tiếp vào list hiện tại.

---

## Task 5 — `profilePostKey` bucket quá thô, skip nhầm bài

**File:** `app/src/main/java/com/zalopilot/app/accessibility/engine/ZPEngine.kt`
**Hàm:** `profilePostKey()`

**Vấn đề:**
```kotlin
return "PROFILE|${r.top / 24}_${idTail}"
```
Chia `r.top` cho 24 nhóm các node cách nhau dưới 24px vào cùng bucket. Trên màn hình high-DPI hoặc sau khi cuộn nhẹ, nút like của hai bài khác nhau có thể sinh cùng key → bài thứ 2 bị skip sai.

**Yêu cầu fix:**
Kết hợp thêm content snippet vào key (tương tự `makePostKey` ở feed), hoặc tăng granularity (chia cho giá trị lớn hơn, ví dụ chiều cao trung bình 1 bài ~200-300px) để tránh collision giữa các bài liền kề.

---

## Task 6 — Docstring `isContactListScreen` mô tả sai hàm

**File:** `app/src/main/java/com/zalopilot/app/accessibility/NodeFinder.kt`
**Hàm:** `isContactListScreen`

**Vấn đề:**
Docstring hiện tại là copy-paste từ `isFullScreenCommentScreen`, mô tả màn "Bình luận" full-screen — hoàn toàn sai chức năng của hàm.

**Yêu cầu fix:**
Thay docstring bằng mô tả đúng: hàm kiểm tra màn hình đang là danh sách bạn bè (tab Bạn bè trong Danh bạ), dùng để xác nhận bot đã về đúng màn sau `backToContacts`.

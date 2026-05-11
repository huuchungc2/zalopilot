# Changelog

## [Unreleased]

- feat(ui/accessibility): thêm nút "Quét UI" (force scan) và export map id ra `ui_map.json` (internal storage); `ZaloUIScanner` bỏ whitelist khi scan (chỉ reject blacklist class/id, package != `com.zing.zalo`, bounds > 20% màn hình). `NodeFinder.findLikeButtons`/`hasVisibleSelfAlreadyLikedLikeControl` bỏ filter theo whitelist (chỉ giữ `shouldRejectNodeForLike`).
- fix(accessibility): màn hình toàn bài **mình đã like** (`isAlreadyLiked`) — `findLikeButtons` rỗng nhưng vẫn có vùng like; `hasVisibleSelfAlreadyLikedLikeControl` → trả `ALL_SKIPPED` (cuộn tiếp, reset empty streak) thay vì `NO_BUTTONS`.
- fix(accessibility/like): `LikeViewIdRules` — chỉ whitelist `btn_like_text` / `btn_like_icon` / `btn_like` / `like_component`; blacklist `vpager`, `layoutSocialFeed`, `feedItemGroupHorizontal` + class ViewPager/RecyclerView/FrameLayout; xóa prefs like id nếu đã lưu blacklist (vd. vpager). `ZaloUIScanner` chỉ lưu id whitelist. `NodeFinder` resolve target theo ưu tiên id, không `resolveClickable` lên container; `isLikelyZaloImageViewer` + sau click `BACK` + skip bài. Like click: `performClickLikeTargetNoParent` (không chain parent).
- fix(NodeFinder): `isAlreadyLiked` — không dùng text "Thích" làm chưa like; ưu tiên `isChecked`/`isSelected`/`stateDescription` (API 30+) và "Đã thích" trên vùng id like / `my_reaction`.
- feat(accessibility): `scrollDownByGesture` — tọa độ X ngẫu nhiên trong dải an toàn, điểm cuối lệch X, đường `quadTo` hơi cong, jitter dọc + thời lượng; giả lập vuốt tay gần người hơn (trước đây luôn một đường thẳng giữa màn hình).
- fix(accessibility): cuộn feed sau like — tăng quãng vuốt màn hình (SMALL/NORMAL/LARGE) và gọi `ACTION_SCROLL_FORWARD` thêm một lần khi RecyclerView đã học cuộn được lần đầu (Zalo thường chỉ dịch một đoạn ngắn mỗi lần).
- fix(NodeFinder): `getPostSnippetForKey` bỏ qua placeholder ô bình luận (ví dụ "Nhập bình luận") + `hintText`; tránh postKey `CONTENT||…` trùng giữa nhiều bài.
- fix(accessibility): sau like — verify đợi 1500ms (thay 900ms); nếu vẫn `CLICK_UNCONFIRMED` thì không `processedPosts`/không tăng tiến độ/không `SUCCESS`, `continue` thử nút khác.
- fix(NodeFinder): bỏ `isChecked`/`isSelected` ở node like gốc — chỉ giữ trên child id `btn_like`/`like_btn`; thêm `reResolveLikeNodeForClick` trước khi click.
- fix(accessibility): streak **empty scan** (`consecutiveEmptyLikeScanStreak`) tách khỏi **scroll không dịch**; `NO_BUTTONS` chỉ tăng streak empty; `awaitFeedLikeScanRoot` chờ có nút + root mới có giới hạn trước `runFeedMode`; `combinedStuckLevel` cho gesture khi empty dài.
- fix(accessibility): ổn định feed — sau neo layout + 1–2s lấy root mới rồi mới scan; `delayFeedSettleAfterScroll()` 800–1500ms sau mỗi cuộn bot trước vòng quét kế; poll không gọi `ZaloUIScanner.scan` khi bot chạy; `acquireRootOrNull(quietLog)` + throttle log `EVENT_HINT` khi bot chạy; `runFeedMode` retry 4 lần + delay dài hơn; recycle root sau scroll verify; gộp log `SKIP_SHOULD_LIKE`.
- fix(NodeFinder): `isAlreadyLiked` chỉ nhận diện like **của user hiện tại** (btn_like_text, nhãn Đã thích/Liked không có số, `isChecked`/`isSelected`) — bỏ heuristic `reaction_info`/`my_reaction` (dễ nhầm với bài đã có like từ người khác).
- fix(accessibility): `NO_BUTTONS` — ngưỡng dừng riêng cao hơn (`NO_BUTTONS_END_STOP_STREAK`), thêm nudge vuốt nhẹ khi scroll chính không dịch; profile vuốt SMALL/NORMAL/LARGE theo mức kẹt; một lần `debugDump` ngắn khi scan fail (ngoài chế độ verbose).
- feat(accessibility): `scrollFeedWithVerification` nhận `GestureScrollProfile` — cuộn ngắn mượt mặc định, vuốt dài hơn khi feed kẹt nhiều lần.

- feat(accessibility): khi bot chạy, overlay trạng thái dùng `FLAG_KEEP_SCREEN_ON` + `View.keepScreenOn` — màn hình không tự tắt theo timeout (dừng bot / gỡ overlay → bình thường lại).
- feat(settings): **Tiết kiệm pin (ngủ trưa)** — `LikeSettings.ecoMode`: poll chậm hơn (Eco / màn tắt+bot chạy), `ZaloUIScanner` gap 3.4s, delay vòng like ×~1.5 + delay giữa like theo setting ×1.5, verify/scroll settle +15%; poll khi màn tắt+bot chạy 4–7s (Eco 5.2–9s); `POLL_ROOT_ASSUME_BACKGROUND_SCREEN_OFF=22` để ít dừng nhầm khi khóa màn.
- fix(accessibility): dừng khi cuối feed — đếm `consecutiveScrollNoProgress` khi `scrollFeedWithVerification` báo không dịch anchor (ALL_SKIPPED / sau LIKE); `scrollFeedWithVerification` trả về `Boolean`; hằng `FEED_END_STOP_STREAK` (NO_BUTTONS: ngưỡng riêng — xem Unreleased).
- fix(accessibility): cuộn feed — ưu tiên `ACTION_SCROLL_FORWARD` trên RecyclerView đã học (`feed_recycler_id`); fallback vuốt màn hình có `GestureResultCallback` + log reject/cancel/timeout; nếu anchor không đổi sau lần 1 thì retry scroll API + vuốt trên root mới; xác nhận sau like dùng `NodeFinder.isAlreadyLiked` (tránh đọc chỉ text node). `NodeFinder`: bỏ candidate đã thích khi `findAccessibilityNodeInfosByText("Thích")` khớp nhầm "Đã thích", và lọc trong `addResolved`.
- fix: `boundsDedupeKey` làm tròn left/top theo 16px + `viewIdResourceName`; `authorTextOrNull` bỏ qua chuỗi chứa thích/bình luận/nhập/chia sẻ/comment/like; `shouldLike` lọc text/desc node (đã thích/liked/bình luận/nhập/chia sẻ), không chặn chỉ "Thích".
- feat(settings): `FeedMode` (SCROLL / MANUAL / MIX) lưu prefs `feed_mode`, mặc định SCROLL; UI Cài đặt (Cuộn tự động / Đẩy tay / Kết hợp); `autoLikeLoop` — MANUAL không scroll, MIX 50% `scrollDown` hoặc chờ, SCROLL giữ hành vi cũ theo `InteractMode`.
- fix(accessibility): `GestureResultCallback` dùng `AccessibilityService.GestureResultCallback` (sửa unresolved reference khi build).
- fix(NodeFinder): `getAuthorName` bỏ qua nhãn hành động (Thích, Đã thích, Bình luận, Chia sẻ); `shouldLike` — `btn_like` không text/desc vẫn cho thử click; lọc `isEnabled`.
- feat(accessibility): độ tin cậy click — log candidate (`CLICK_CANDIDATE`), `ACTION_CLICK` self + parent tới 6 cấp (thử cả parent không `clickable`), `GESTURE_FALLBACK`, chặn trùng bounds ngắn hạn, `SKIP_NOT_VISIBLE`, gesture tap không block main (`suspendCancellableCoroutine` + timeout), stroke tap 150ms.
- feat: overlay debug nhẹ — viền bounds các nút Thích `findLikeButtons` + viền dày node sắp click; bật/tắt Cài đặt (`DebugHighlightPrefs`); `FLAG_NOT_TOUCHABLE`, không đổi scanner.
- feat(logging): `LogTag` (POLL, EVENT_HINT, SCAN, FOUND, CLICK, SCROLL, ERROR, STATE), `Logger.log(tag,…)` + `pkg`/`ms` JSON, `lastForegroundPackage` + `logError()`; tab Nhật ký hiển thị tag màu, timestamp ms, pkg, thời gian scan; làm nổi bật lỗi click/scroll; đọc log cũ map tag tương thích.
- refactor(accessibility): vòng poll 1–2s làm nguồn chính (root + package + `ZaloUIScanner`); event chỉ gợi ý + log `EVENT_HINT`; `acquireRootOrNull()` retry 5 lần; nhận diện app `contains("zalo")` qua `isZaloRelatedPackage()`; log chi tiết click/tap/scroll/root; `NodeFinder` thêm traversal text+contentDescription + bounds trong debug dump.
- fix: `exportLog()` dùng `logger.logFile` (`log.json`) thay cho đường dẫn cứng `log.txt`; file xuất Downloads đổi đuôi `.json`.
- fix: `dumpZaloUI()` gọi `nodeFinder.debugDump` (Hilt) thay vì tạo `NodeFinder` thủ công.
- fix: `ZaloUIScanner.scanAuthorName` lấy resource-id qua `findIdFromNodeOrParent` giống `scanLikeButton`.
- feat: thêm `NodeFinder.debugDump(root)` để dump node tree ra `Logger` với action `DEBUG_DUMP` phục vụ debug UI Zalo.
- fix(ci): khôi phục Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) để GitHub Actions build APK ổn định.
- fix: luôn toast/log lý do khi bấm Start; log rõ SCANNER START/NO_MATCH và sửa trạng thái botRunning của menu nổi.
- fix: chuyển log sang internal storage để tránh trường hợp log “trống” do external storage fail; parseLine chịu được target có `] [` bên trong.


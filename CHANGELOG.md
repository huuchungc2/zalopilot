# Changelog

## [Unreleased]

- fix(NodeFinder): `isAlreadyLiked` — bỏ so khớp sibling bằng `===` (dùng id+bounds); `reaction_info`/`my_reaction` qua `contains`; quét cây con nông dưới parent footer để bắt Zalo vẫn để text "Thích" khi đã có reaction (tránh like rồi unlike).
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


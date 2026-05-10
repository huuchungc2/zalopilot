# Changelog

## [Unreleased]

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


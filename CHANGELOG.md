# Changelog

## [Unreleased]

- feat: thêm `NodeFinder.debugDump(root)` để dump node tree ra `Logger` với action `DEBUG_DUMP` phục vụ debug UI Zalo.
- fix(ci): khôi phục Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) để GitHub Actions build APK ổn định.
- fix: luôn toast/log lý do khi bấm Start; log rõ SCANNER START/NO_MATCH và sửa trạng thái botRunning của menu nổi.
- fix: chuyển log sang internal storage để tránh trường hợp log “trống” do external storage fail; parseLine chịu được target có `] [` bên trong.


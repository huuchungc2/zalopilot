# ZaloPilot

Android app tự động like bài đăng trên Zalo thông qua Accessibility Service.

## Tính năng

- Auto like bài đăng trên tab Nhật ký Zalo
- Giới hạn like theo ngày (tránh bị Zalo flag)
- Nghỉ tự động giữa session
- Không chạy trong giờ nghỉ (cài đặt được)
- Floating button nổi trên Zalo để điều khiển
- Nhật ký hoạt động đầy đủ
- Tiến độ theo ngày và tổng

## Cài đặt

### Cách 1: Download APK từ GitHub Actions
1. Vào tab **Actions** trên GitHub
2. Chọn build mới nhất
3. Download file `ZaloPilot-debug.apk`
4. Cài lên điện thoại (bật "Cài từ nguồn không rõ")

### Cách 2: Build thủ công
```bash
git clone https://github.com/your-username/zalopilot
cd zalopilot
./gradlew assembleDebug
```

## Cấu hình sau khi cài

1. Mở app ZaloPilot
2. Bấm **Mở Cài đặt** → bật Accessibility Service cho ZaloPilot
3. Cấp quyền **Hiển thị trên ứng dụng khác** (cho floating button)
4. Vào tab **Cài đặt** → chỉnh limit, delay theo ý
5. Mở Zalo → floating button [ZP] sẽ hiện lên tự động

## Cập nhật selector khi Zalo update

Nếu Zalo update và app không like được nữa:
1. Sửa file `app/src/main/res/raw/selector_config.json`
2. Cập nhật resource-id mới
3. Push lên GitHub → GitHub Actions tự build APK mới

## Lưu ý

App này dùng cho mục đích tương tác với khách hàng của sale.
Không dùng để spam. Cài limit hợp lý (100 like/ngày là an toàn).

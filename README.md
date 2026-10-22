# MotoNav

App Android cá nhân cho xe máy: hiện chỉ dẫn rẽ của Google Maps, tốc độ giới hạn, cảnh báo vượt tốc và camera ngay trên màn hình điện thoại. Không cần Bluetooth hay màn TFT.

## Tính năng

- **Dẫn đường**: đọc thông báo dẫn đường của Google Maps, hiện mũi tên rẽ, khoảng cách và tên đường cỡ lớn.
- **Tốc độ giới hạn**: lấy biển tốc độ từ OpenStreetMap theo đoạn đường đang chạy (có xét chiều đi).
- **Ước tính khi thiếu biển**: theo Thông tư 38/2024/TT-BGTVT cho xe mô tô — trong khu đông dân cư 60/50 km/h, ngoài khu đông dân cư 70/60 km/h (đường đôi / hai chiều). Xe gắn máy tối đa 40 km/h. Khu vực được đoán theo phường/xã trên OSM, chỉnh tay được trong Cài đặt. **Chỉ là ước tính, luôn đi theo biển báo thực tế.**
- **Đọc bằng giọng nói**: "Giới hạn 50", "Quá tốc độ", "Phía trước 300 mét có camera phạt nguội". Tự giảm nhạc khi đang đọc.
- **Camera**: camera tốc độ / đèn đỏ / giám sát giao thông có trên OSM, cộng camera phạt nguội anh tự đánh dấu bằng nút "+ Camera" (lưu trong máy, có nhớ hướng đi).

## Cách dùng

1. Mở MotoNav, cho phép Vị trí và Thông báo.
2. Cấp quyền đọc thông báo (app có hướng dẫn). Android 13 trở lên: Thông tin ứng dụng → ⋮ → "Cho phép cài đặt bị hạn chế" trước.
3. Bấm "Google Maps", bắt đầu dẫn đường, rồi quay lại MotoNav.

## Build

Mỗi lần đẩy code lên nhánh `main`, GitHub Actions tự build và đăng file APK ở mục **Releases**.

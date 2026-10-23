package vn.hiep.motonav

/**
 * Vài mốc/địa danh nổi tiếng ở TP.HCM, dùng để mô tả tuyến đường dễ hình dung hơn là chỉ nêu
 * tên đường thuần tuý (ví dụ "Qua: Landmark 81" thay vì "Qua: Nguyễn Hữu Cảnh"). Danh sách
 * chọn thủ công, toạ độ áng chừng — chắc chắn còn thiếu nhiều, thấy chỗ hay đi qua mà app
 * chưa nhận ra thì bổ sung thêm vào đây.
 */
object Landmarks {
    class Spot(val name: String, val lat: Double, val lon: Double)

    val ALL = listOf(
        Spot("Landmark 81", 10.7949, 106.7218),
        Spot("cầu Sài Gòn", 10.8023, 106.7217),
        Spot("cầu Phú Mỹ", 10.7490, 106.7452),
        Spot("hầm Thủ Thiêm", 10.7794, 106.7217),
        Spot("cầu Bình Lợi", 10.8206, 106.7187),
        Spot("cầu Rạch Chiếc", 10.8028, 106.7515),
        Spot("Suối Tiên", 10.8703, 106.8032),
        Spot("ngã tư Thủ Đức", 10.8496, 106.7715),
        Spot("Khu Công nghệ cao", 10.8411, 106.8087),
        Spot("Bến xe Miền Đông mới", 10.8792, 106.8028),
        Spot("chợ Bến Thành", 10.7720, 106.6980),
        Spot("Dinh Độc Lập", 10.7772, 106.6953),
        Spot("Nhà thờ Đức Bà", 10.7798, 106.6990),
        Spot("Bitexco", 10.7715, 106.7041),
        Spot("sân bay Tân Sơn Nhất", 10.8188, 106.6520),
        Spot("Phú Mỹ Hưng", 10.7290, 106.7196),
        Spot("Thảo Cầm Viên", 10.7873, 106.7054),
        Spot("AEON Mall Tân Phú", 10.8005, 106.6280),
    )
}

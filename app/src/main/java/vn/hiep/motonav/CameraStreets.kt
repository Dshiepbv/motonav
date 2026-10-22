package vn.hiep.motonav

/**
 * Các tuyến đường ở TP.HCM được báo chí đưa tin là có lắp camera phạt nguội
 * (danh sách tổng hợp công khai, chưa có toạ độ từng camera).
 * Khi xe đi vào một tuyến trong danh sách, app nhắc chú ý.
 */
object CameraStreets {
    private val list = listOf(
        "Điện Biên Phủ", "Nguyễn Thị Minh Khai", "Võ Thị Sáu", "Nguyễn Hữu Cảnh", "Xô Viết Nghệ Tĩnh",
        "Nguyễn Thị Định", "Đặng Thúc Vịnh", "Xa lộ Hà Nội", "Mai Chí Thọ", "Võ Văn Kiệt",
        "Lê Duẩn", "Hai Bà Trưng", "Phạm Ngọc Thạch", "Lý Tự Trọng", "Nam Kỳ Khởi Nghĩa",
        "Nguyễn Thái Học", "Pasteur", "Hùng Vương", "Nguyễn Chí Thanh", "Nguyễn Thị Nghĩa",
        "Lê Lợi", "Đinh Tiên Hoàng", "Tôn Đức Thắng", "Trường Sơn", "An Dương Vương",
        "Trần Phú", "Lê Thánh Tôn", "Nguyễn Huệ", "Đồng Khởi", "Trương Định",
        "Cộng Hòa", "Hoàng Văn Thụ", "Hồng Hà", "Bạch Đằng", "Kinh Dương Vương",
        "Nguyễn Thị Tú", "Lê Trọng Tấn", "Đinh Bộ Lĩnh", "Đồng Văn Cống", "Nguyễn Duy Trinh",
        "Nguyễn Xiển", "Cống Quỳnh", "Quốc lộ 1", "Quốc lộ 22",
        // Bà Rịa – Vũng Tàu (nay thuộc TP.HCM): camera giám sát trên 3 quốc lộ (Công an tỉnh BR-VT)
        "Quốc lộ 51", "Quốc lộ 55", "Quốc lộ 56",
    )

    private fun norm(s: String): String = s.lowercase()
        .replace("hoà", "hòa").replace("thuỵ", "thụy")
        .replace(Regex("""^(đường|đ\.|duong|đại lộ|xa lộ)\s+"""), "")
        .replace(Regex("""\s+"""), " ").trim()

    private val normed = list.map { norm(it) }

    /** Trả về tên tuyến nếu đường đang đi nằm trong danh sách. Chỉ áp dụng trong TP.HCM (vĩ độ/kinh độ). */
    fun match(name: String?, ref: String?, lat: Double, lon: Double): String? {
        // Vùng TP.HCM mở rộng (gồm Bình Dương, Bà Rịa – Vũng Tàu)
        if (lat < 10.3 || lat > 11.6 || lon < 106.3 || lon > 107.7) return null
        // Các tên đường nội thành chỉ áp dụng trong TP.HCM cũ (tránh trùng tên đường ở Vũng Tàu, Bình Dương)
        val oldHcm = lat in 10.35..11.2 && lon in 106.35..107.05
        val cands = listOfNotNull(name, ref?.let { r ->
            // "QL1A", "QL.22" → "Quốc lộ 1", "Quốc lộ 22"
            Regex("""(?i)QL\.?\s*(\d+)""").find(r)?.let { "Quốc lộ " + it.groupValues[1] }
        })
        for (c in cands) {
            val n = norm(c)
            val i = normed.indexOfFirst { it == n || (it.length >= 6 && n.startsWith(it + " ")) || n == "xa lộ $it" }
            if (i >= 0 && (oldHcm || list[i].startsWith("Quốc lộ 5"))) return list[i]
            if (n.startsWith("quốc lộ 1a") || n == "ql1a") return "Quốc lộ 1"
        }
        return null
    }
}

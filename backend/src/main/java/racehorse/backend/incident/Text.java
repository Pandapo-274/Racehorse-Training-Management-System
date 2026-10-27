package racehorse.backend.incident;

/** Tiện ích nhỏ dùng chung trong package incident. */
final class Text {

    private Text() {
    }

    /** Cắt cho vừa cột: NOTIFICATION.message chỉ chứa 500 ký tự, vượt là SQL báo lỗi cắt chuỗi. */
    static String clip(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}

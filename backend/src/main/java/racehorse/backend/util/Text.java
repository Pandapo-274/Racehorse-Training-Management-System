package racehorse.backend.util;

/** Tiện ích nhỏ dùng chung trong package incident. */
public final class Text {

    private Text() {
    }

    /** Cắt cho vừa cột: NOTIFICATION.message chỉ chứa 500 ký tự, vượt là SQL báo lỗi cắt chuỗi. */
    public static String clip(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}

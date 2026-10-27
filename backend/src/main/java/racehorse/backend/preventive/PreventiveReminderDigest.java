package racehorse.backend.preventive;

import java.util.List;
import java.util.Map;

/**
 * Soạn nội dung MỘT thông báo tóm tắt cho bác sĩ thú y.
 *
 * Vì sao tóm tắt chứ không gửi mỗi ô một thông báo: với dữ liệu demo, hơn một trăm
 * con ngựa chưa từng tẩy giun, tức vài trăm ô NEVER trong lần chạy đầu tiên. Vài
 * trăm dòng trên cái chuông là cách nhanh nhất để bác sĩ bỏ không đọc nữa. Một tin
 * nói rõ "có bao nhiêu việc, mấy việc gấp nhất" và dẫn sang bảng UC17 là đủ.
 *
 * Lớp thuần Java, không phụ thuộc Spring và database, để kiểm riêng được.
 */
final class PreventiveReminderDigest {

    /** NVARCHAR(500) trong bảng NOTIFICATION. */
    static final int MAX_MESSAGE = 500;
    /** Số ô gấp nhất được nêu đích danh trong nội dung. */
    static final int NAMED = 3;

    static final String TITLE = "Preventive care reminder";

    record Summary(String title, String message) {}

    private static final Map<String, String> LABELS = Map.of(
            "VACCINATION", "vaccination",
            "DEWORMING", "deworming",
            "FARRIER", "farrier");

    private PreventiveReminderDigest() {}

    /**
     * @param items các ô cần nhắc, đã xếp theo mức cấp thiết (PreventiveCareService
     *              làm việc đó), nên N ô đầu chính là N ô gấp nhất
     * @param window cửa sổ ngày của DUE_SOON, để nói đúng "trong 14 ngày"
     */
    static Summary build(List<PreventiveCareItem> items, int window) {
        long overdue = count(items, "OVERDUE");
        long dueSoon = count(items, "DUE_SOON");
        long never = count(items, "NEVER");

        StringBuilder sb = new StringBuilder();
        append(sb, overdue, "overdue");
        append(sb, dueSoon, "due within " + days(window));
        append(sb, never, "never recorded");
        sb.append('.');

        sb.append(" Most urgent: ");
        int named = Math.min(NAMED, items.size());
        for (int i = 0; i < named; i++) {
            if (i > 0) sb.append("; ");
            sb.append(describe(items.get(i)));
        }
        int rest = items.size() - named;
        if (rest > 0) sb.append("; and ").append(rest).append(" more");
        sb.append('.');

        return new Summary(TITLE, truncate(sb.toString()));
    }

    static String describe(PreventiveCareItem i) {
        String what = LABELS.getOrDefault(i.careType(), i.careType().toLowerCase());
        String when = switch (i.careStatus()) {
            case "OVERDUE" -> {
                int late = i.daysUntilDue() == null ? 0 : -i.daysUntilDue();
                yield days(late) + " overdue";
            }
            case "DUE_SOON" -> {
                int left = i.daysUntilDue() == null ? 0 : i.daysUntilDue();
                yield left == 0 ? "due today" : "due in " + days(left);
            }
            default -> "never recorded";
        };
        return i.horseName() + " (" + what + ", " + when + ")";
    }

    private static long count(List<PreventiveCareItem> items, String status) {
        return items.stream().filter(i -> status.equals(i.careStatus())).count();
    }

    private static void append(StringBuilder sb, long n, String text) {
        if (n == 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(n).append(' ').append(text);
    }

    private static String days(int n) {
        return n + (n == 1 ? " day" : " days");
    }

    private static String truncate(String s) {
        return s.length() <= MAX_MESSAGE ? s : s.substring(0, MAX_MESSAGE - 3) + "...";
    }
}

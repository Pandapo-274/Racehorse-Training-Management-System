package racehorse.backend.notification;

/**
 * Nội dung một thông báo, trước khi biết gửi cho ai.
 *
 * Tách khỏi người nhận để cùng một nội dung gửi được cho một người
 * (NotificationService.notifyUser) hoặc cho cả một vai trò (notifyRole) mà
 * không phải lặp lại năm tham số ở mọi chỗ gọi.
 *
 * targetEntity / targetId: thứ thông báo nói đến, để frontend mở đúng trang.
 * Để cả hai null nếu thông báo không trỏ tới đâu.
 */
public record Notice(String type, String title, String message,
                     String targetEntity, Integer targetId) {

    public static final String HORSE = "HORSE";
    public static final String HORSE_REQUEST = "HORSE_REQUEST";
    public static final String TRAINING_SESSION = "TRAINING_SESSION";
    public static final String THRESHOLD_ALERT = "THRESHOLD_ALERT";
}

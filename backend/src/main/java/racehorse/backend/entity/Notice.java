package racehorse.backend.entity;

import racehorse.backend.service.NotificationService;

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

    public static final String SUPPLY = "SUPPLY";
    public static final String HORSE = "HORSE";
    public static final String HORSE_REQUEST = "HORSE_REQUEST";
    public static final String TRAINING_SESSION = "TRAINING_SESSION";
    public static final String THRESHOLD_ALERT = "THRESHOLD_ALERT";
    /** Từ V15: yêu cầu rút ngựa, mở hàng chờ duyệt của Club Manager. */
    public static final String HORSE_WITHDRAWAL = "HORSE_WITHDRAWAL";
    /** Từ V27: một lần HLV gọi bác sĩ từ cảnh báo buổi tập, mở đúng ca gọi đó. */
    public static final String VET_CALL = "VET_CALL";
    /** Từ V34: giáo án chờ chủ ngựa duyệt, hoặc chủ ngựa vừa duyệt / từ chối. */
    public static final String TRAINING_PLAN = "TRAINING_PLAN";
    /** Từ V40: ca chuyển viện ngoài cho ngựa cấp cứu / chấn thương nặng. */
    public static final String EXTERNAL_REFERRAL = "EXTERNAL_REFERRAL";
    /** Từ V40: báo cáo sự cố y tế hoặc bàn giao ca trực. */
    public static final String INCIDENT_REPORT = "INCIDENT_REPORT";
}

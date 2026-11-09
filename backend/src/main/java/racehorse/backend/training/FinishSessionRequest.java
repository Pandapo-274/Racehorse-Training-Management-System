package racehorse.backend.training;

/**
 * V37: Tuỳ chọn khi kết thúc buổi tập (PUT /api/training-sessions/{id}/finish).
 * Nếu kết thúc bình thường đúng giờ: body có thể rỗng hoặc earlyStop = false.
 * Nếu Groom kết thúc sớm hơn thời gian đặt:
 *  - earlyStop = true
 *  - earlyStopReason bắt buộc chọn từ danh sách lý do (vd: TARGET_REACHED, HORSE_FATIGUED,
 *    WEATHER_CONDITION, EQUIPMENT_ISSUE, INJURY hoặc chuỗi mô tả)
 *  - Bắt buộc Groom đã báo cáo cả 2 chỉ số Heart Rate và Speed ít nhất 1 lần trong buổi tập.
 */
public record FinishSessionRequest(
        Boolean earlyStop,
        String earlyStopReason
) {}

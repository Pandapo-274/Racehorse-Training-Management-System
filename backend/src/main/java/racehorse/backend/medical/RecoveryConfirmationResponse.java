package racehorse.backend.medical;

import java.util.List;

/**
 * UC18 - kết quả một lần xác nhận hồi phục.
 *
 * Trả về cả ba thứ vừa đổi, không chỉ bản ghi khám: màn hình cần nói được
 * "đã khép 2 chấn thương và mở 1 lệnh khoá" chứ không chỉ "đã lưu". Lệnh khoá
 * được mở là thứ huấn luyện viên quan tâm nhất, vì đó là lúc con ngựa quay
 * lại được lịch tập.
 */
public record RecoveryConfirmationResponse(
        MedicalRecordRow record,
        List<Integer> injuriesClosed,
        List<Integer> locksReleased
) {}

package racehorse.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * Một con ngựa như frontend nhìn thấy: GET /api/horses, GET /api/horses/{id} và nhiều chỗ
 * khác (service dùng lại record này khi cần thông tin ngựa, vd. kiểm quyền chủ ngựa).
 *   status         - sức khoẻ do bác sĩ đặt: ELIGIBLE | MONITOR | INJURED | QUARANTINE
 *   activityStatus - còn ở CLB hay đã rút: ACTIVE | WITHDRAWN
 *   recordType     - ACADEMY (ngựa của CLB) | PEDIGREE (chỉ có trong sổ phả hệ)
 */
public record HorseResponse(
        int horseId, int ownerId, String ownerName,
        Integer sireId, Integer damId,
        String horseName, String registrationCode, String breed, String horseGender,
        LocalDate dateOfBirth, String color, BigDecimal weight, String stallNo,
        String status, LocalDateTime createdAt, LocalDateTime updatedAt,
        String recordType,
        String photoUrl,                // null nếu chưa có ảnh
        String activityStatus,          // ACTIVE | WITHDRAWN (V15), khác với status sức khoẻ
        LocalDateTime withdrawnAt,      // null nếu ngựa còn ở học viện
        Integer trainerId,              // V37: Head Trainer được Club Manager giao phụ trách ngựa này
        String trainerName
) {
    // Constructor phụ (không có trainerId/trainerName) cho các câu SQL viết trước V37.
    public HorseResponse(
            int horseId, int ownerId, String ownerName,
            Integer sireId, Integer damId,
            String horseName, String registrationCode, String breed, String horseGender,
            LocalDate dateOfBirth, String color, BigDecimal weight, String stallNo,
            String status, LocalDateTime createdAt, LocalDateTime updatedAt,
            String recordType, String photoUrl, String activityStatus, LocalDateTime withdrawnAt) {
        this(horseId, ownerId, ownerName, sireId, damId, horseName, registrationCode,
                breed, horseGender, dateOfBirth, color, weight, stallNo, status,
                createdAt, updatedAt, recordType, photoUrl, activityStatus, withdrawnAt,
                null, null);
    }

    // Tiện ích cho service: ngựa đã rút thì bị loại khỏi giáo án, buổi tập, bảng sức khoẻ...
    public boolean isWithdrawn() {
        return "WITHDRAWN".equals(activityStatus);
    }
}

package racehorse.backend.horse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

    public boolean isWithdrawn() {
        return "WITHDRAWN".equals(activityStatus);
    }
}

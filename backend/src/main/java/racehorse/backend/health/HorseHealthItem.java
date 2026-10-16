package racehorse.backend.health;

import java.time.LocalDateTime;

/** Một "ô chuồng" trên bản đồ: ngựa nào, ở đâu, màu gì (status), có đang bị khoá huấn luyện không. */
public record HorseHealthItem(
        int horseId,
        String horseName,
        String registrationCode,
        String stallNo,
        String status,
        String ownerName,
        LocalDateTime lastExamDate,
        String lastDiagnosis,
        boolean trainingLocked,
        String lockLevel
) {}

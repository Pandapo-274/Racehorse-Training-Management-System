package racehorse.backend.dto;

import java.time.LocalDateTime;

/** Một "ô chuồng" trên bản đồ: ngựa nào, ở đâu, màu gì (status), có đang bị khoá huấn luyện không. */
// status: ELIGIBLE | MONITOR | INJURED | QUARANTINE - frontend đổi ra nhãn và tông màu bằng
// bảng STATUS_TEXT trong horseApi.js (good / warning / critical / serious). lockLevel null
// khi không bị khoá.
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

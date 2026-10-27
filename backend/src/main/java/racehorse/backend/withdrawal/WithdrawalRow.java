package racehorse.backend.withdrawal;

import java.time.LocalDateTime;

/**
 * Một yêu cầu rút ngựa, như frontend nhìn thấy.
 *
 * scheduledSessions và openPlans tính lúc đọc: với yêu cầu đang chờ, Club
 * Manager biết trước bấm duyệt sẽ huỷ bao nhiêu buổi tập và kết thúc bao nhiêu
 * giáo án. Với yêu cầu đã duyệt, con số thật nằm ở cancelledSessions và
 * endedPlans (ghi lúc duyệt).
 */
public record WithdrawalRow(
        int withdrawalId,
        int horseId, String horseName, String registrationCode, String stallNo,
        String horseActivityStatus,
        int requestedBy, String requesterName,
        String reason,
        String status, LocalDateTime createdAt,
        Integer reviewedBy, String reviewerName, LocalDateTime reviewedAt, String reviewNote,
        String releasedStall, Integer cancelledSessions, Integer endedPlans,
        int scheduledSessions, int openPlans
) {}

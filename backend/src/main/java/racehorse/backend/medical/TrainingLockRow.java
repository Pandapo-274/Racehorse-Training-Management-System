package racehorse.backend.medical;

import java.time.LocalDateTime;

/**
 * Một lệnh khoá tập, như màn Training Lock Management nhìn thấy.
 *
 * Ghép sẵn tên người ra lệnh và người mở lệnh: trên màn quản lý, câu hỏi đầu
 * tiên về một lệnh khoá là "ai ra lệnh này" - một id thì không trả lời được.
 * releasedByName null khi lệnh còn ACTIVE.
 *
 * injuryBodyLocation kèm theo để màn hình nói được "khoá vì gân chi trước
 * trái" thay vì "khoá vì chấn thương #7".
 */
public record TrainingLockRow(
        int lockId,
        int horseId,
        String horseName,
        Integer injuryId,
        String injuryBodyLocation,
        String injuryStatus,
        int issuedBy,
        String issuedByName,
        String reason,
        String lockLevel,
        LocalDateTime issuedAt,
        Integer releasedBy,
        String releasedByName,
        LocalDateTime releasedAt,
        String status
) {}

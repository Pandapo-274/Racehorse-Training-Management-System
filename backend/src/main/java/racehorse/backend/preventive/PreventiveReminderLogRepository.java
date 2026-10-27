package racehorse.backend.preventive;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sổ "ô nào đã được nhắc rồi" của tác vụ nhắc lịch phòng ngừa (V17).
 *
 * Bảng nhỏ: tối đa số ngựa x 3 loại x 3 giai đoạn dòng, và có dọn theo ngày, nên
 * đọc cả bảng một lần mỗi lần chạy rẻ hơn hỏi từng ô một.
 */
@Repository
public class PreventiveReminderLogRepository {

    private final JdbcClient jdbc;

    public PreventiveReminderLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Một ô ở một giai đoạn. recordRef = 0 khi ô chưa từng được làm.
     * Là record nên equals/hashCode đúng sẵn để dùng làm khoá của Map.
     */
    public record ReminderKey(int horseId, String careType, int recordRef, String stage) {}

    /** Ngày nhắc gần nhất của từng ô đã nhắc. */
    public Map<ReminderKey, LocalDate> findAll() {
        Map<ReminderKey, LocalDate> result = new HashMap<>();
        jdbc.sql("SELECT horse_id, care_type, record_ref, stage, sent_on FROM PREVENTIVE_REMINDER_LOG")
                .query((rs, i) -> Map.entry(
                        new ReminderKey(rs.getInt("horse_id"), rs.getString("care_type"),
                                rs.getInt("record_ref"), rs.getString("stage")),
                        rs.getObject("sent_on", LocalDate.class)))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /**
     * Ghi nhận đã nhắc ô này hôm nay: cập nhật ngày nếu đã có dòng, chèn mới nếu chưa.
     *
     * UPDATE rồi mới INSERT thay vì MERGE: MERGE của SQL Server có những lỗi đồng
     * thời nổi tiếng, trong khi hai câu này đủ rõ, và khoá UNIQUE của V17 vẫn chặn
     * nếu hai giao dịch cùng chèn một ô.
     */
    public void markSent(ReminderKey k, LocalDate sentOn) {
        int updated = jdbc.sql("""
                UPDATE PREVENTIVE_REMINDER_LOG
                SET sent_on = :sentOn
                WHERE horse_id = :horseId AND care_type = :careType
                  AND record_ref = :recordRef AND stage = :stage
                """)
                .param("sentOn", sentOn)
                .param("horseId", k.horseId())
                .param("careType", k.careType())
                .param("recordRef", k.recordRef())
                .param("stage", k.stage())
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO PREVENTIVE_REMINDER_LOG (horse_id, care_type, record_ref, stage, sent_on)
                    VALUES (:horseId, :careType, :recordRef, :stage, :sentOn)
                    """)
                    .param("horseId", k.horseId())
                    .param("careType", k.careType())
                    .param("recordRef", k.recordRef())
                    .param("stage", k.stage())
                    .param("sentOn", sentOn)
                    .update();
        }
    }

    /** Dọn dòng có ngày nhắc cũ hơn mốc này. Trả về số dòng đã xoá. */
    public int deleteOlderThan(LocalDate cutoff) {
        return jdbc.sql("DELETE FROM PREVENTIVE_REMINDER_LOG WHERE sent_on < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }
}

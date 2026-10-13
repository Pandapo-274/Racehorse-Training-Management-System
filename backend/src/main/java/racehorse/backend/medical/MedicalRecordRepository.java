package racehorse.backend.medical;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MedicalRecordRepository {

    private final JdbcClient jdbc;

    public MedicalRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_RECORD = """
            SELECT m.record_id, m.horse_id, h.horse_name, m.vet_id, u.full_name AS vet_name,
                   m.report_id, m.record_type, m.exam_date, m.diagnosis, m.health_status,
                   m.injury_confirmed, m.expected_rest_days, m.next_due_date, m.notes
            FROM MEDICAL_RECORD m
            JOIN HORSE h    ON h.horse_id = m.horse_id
            JOIN APP_USER u ON u.user_id  = m.vet_id
            """;

    private static MedicalRecordRow map(ResultSet rs, int rowNum) throws SQLException {
        return new MedicalRecordRow(
                rs.getInt("record_id"),
                rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getInt("vet_id"), rs.getString("vet_name"),
                rs.getObject("report_id", Integer.class),
                rs.getString("record_type"),
                rs.getObject("exam_date", LocalDateTime.class),
                rs.getString("diagnosis"),
                rs.getString("health_status"),
                rs.getBoolean("injury_confirmed"),
                rs.getObject("expected_rest_days", Integer.class),
                rs.getObject("next_due_date", LocalDate.class),
                rs.getString("notes"));
    }

    public Optional<MedicalRecordRow> findById(int recordId) {
        return jdbc.sql(SELECT_RECORD + " WHERE m.record_id = :id")
                .param("id", recordId)
                .query(MedicalRecordRepository::map)
                .optional();
    }

    /** Mới nhất lên trước: màn hình luôn mở ra ở lần khám gần nhất. */
    public List<MedicalRecordRow> findByHorse(int horseId) {
        return jdbc.sql(SELECT_RECORD + " WHERE m.horse_id = :horseId ORDER BY m.exam_date DESC")
                .param("horseId", horseId)
                .query(MedicalRecordRepository::map)
                .list();
    }

    /**
     * Chèn và lấy luôn id vừa sinh bằng OUTPUT INSERTED, không phải chèn rồi
     * SELECT SCOPE_IDENTITY() ở câu thứ hai. Một câu thì không có khe nào để
     * một kết nối khác chen vào giữa.
     */
    public int insert(MedicalExamRequest r, int vetId, String recordType) {
        return jdbc.sql("""
                INSERT INTO MEDICAL_RECORD
                    (horse_id, vet_id, report_id, record_type, exam_date, diagnosis,
                     health_status, injury_confirmed, expected_rest_days, next_due_date, notes)
                OUTPUT INSERTED.record_id
                VALUES
                    (:horseId, :vetId, :reportId, :recordType, :examDate, :diagnosis,
                     :healthStatus, :injuryConfirmed, :restDays, :nextDue, :notes)
                """)
                .param("horseId", r.horseId())
                .param("vetId", vetId)
                .param("reportId", r.reportId())
                .param("recordType", recordType)
                .param("examDate", r.examDate())
                .param("diagnosis", blankToNull(r.diagnosis()))
                .param("healthStatus", r.healthStatus())
                .param("injuryConfirmed", r.injuryConfirmed())
                .param("restDays", r.expectedRestDays())
                .param("nextDue", r.nextDueDate())
                .param("notes", blankToNull(r.notes()))
                .query(Integer.class)
                .single();
    }

    /**
     * Đổi tình trạng sức khoẻ của con ngựa.
     *
     * updated_at được chạm cùng lúc, nếu không thì màn hồ sơ ngựa vẫn hiện
     * ngày sửa cũ trong khi trạng thái đã khác - người đọc sẽ tưởng trạng thái
     * đó có từ lâu rồi.
     */
    public void updateHorseStatus(int horseId, String status) {
        jdbc.sql("UPDATE HORSE SET status = :status, updated_at = SYSUTCDATETIME() WHERE horse_id = :id")
                .param("status", status)
                .param("id", horseId)
                .update();
    }

    /* ---------- Báo cáo sự cố ---------- */

    /** Con ngựa và trạng thái của một báo cáo, để service kiểm trước khi gắn. */
    public record ReportHead(int horseId, String status) {}

    public Optional<ReportHead> findReportHead(int reportId) {
        return jdbc.sql("SELECT horse_id, status FROM INCIDENT_REPORT WHERE report_id = :id")
                .param("id", reportId)
                .query((rs, n) -> new ReportHead(rs.getInt("horse_id"), rs.getString("status")))
                .optional();
    }

    /**
     * Đánh dấu báo cáo đã được khám.
     *
     * Điều kiện status <> 'EXAMINED' nằm ngay trong câu UPDATE chứ không phải
     * ở một câu SELECT trước đó: hai bác sĩ cùng mở một báo cáo thì chỉ người
     * chạy trước đổi được, người sau nhận @@ROWCOUNT = 0 và service biết mà
     * từ chối. Tách ra hai câu thì cả hai đều thấy "chưa khám" rồi cùng ghi.
     */
    public int markReportExamined(int reportId) {
        return jdbc.sql("""
                UPDATE INCIDENT_REPORT SET status = 'EXAMINED'
                WHERE report_id = :id AND status <> 'EXAMINED'
                """)
                .param("id", reportId)
                .update();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}

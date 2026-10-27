package racehorse.backend.preventive;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * UC17 - đọc lịch chăm sóc định kỳ.
 *
 * Toàn bộ cái khó của use case này nằm trong một câu, và nằm ở chỗ KHÔNG hiển
 * nhiên: câu hỏi "con nào sắp đến hạn tiêm" KHÔNG trả lời được bằng cách duyệt
 * MEDICAL_RECORD.
 *
 * Duyệt MEDICAL_RECORD chỉ thấy những con ĐÃ từng được làm. Con chưa bao giờ
 * tiêm thì không có dòng nào trong bảng đó, nên nó lặng lẽ biến mất khỏi kết
 * quả - đúng những con đáng lo nhất lại là những con không hiện ra. Với dữ
 * liệu demo hiện tại, duyệt MEDICAL_RECORD trả về 4 dòng và trông rất ổn,
 * trong khi hơn một trăm con ngựa chưa từng được tẩy giun.
 *
 * Nên câu này đi theo chiều ngược lại: lấy HORSE làm gốc, CROSS JOIN với ba
 * loại chăm sóc để sinh đủ ô cho mọi con ngựa, rồi OUTER APPLY tìm bản ghi
 * gần nhất của từng ô. Không có bản ghi thì các cột để NULL, và cái ô ấy vẫn
 * nằm trong kết quả.
 */
@Repository
public class PreventiveCareRepository {

    private final JdbcClient jdbc;

    public PreventiveCareRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * OUTER APPLY lấy ĐÚNG một bản ghi gần nhất cho mỗi ô. TOP 1 ORDER BY
     * exam_date DESC chứ không phải MAX(next_due_date): nếu một con được tiêm
     * hai lần, lần sau mới là lần quyết định hạn kế tiếp, kể cả khi lần trước
     * lỡ nhập một ngày hẹn xa hơn.
     *
     * LEFT JOIN APP_USER vì m.vet_id là NULL ở mọi ô chưa từng làm - nối
     * trong sẽ xoá sạch đúng những ô ấy, lặp lại chính cái lỗi mà CROSS JOIN
     * ở trên sinh ra để tránh.
     *
     * WHERE record_type = 'ACADEMY': hồ sơ PEDIGREE không sống trong chuồng nên
     * không có gì để tiêm hay tẩy giun. Không lọc thì mỗi hồ sơ tổ tiên sinh ra
     * ba ô NEVER và chúng luôn nằm ở đầu danh sách (hồ sơ càng cũ càng "cấp"),
     * che mất đúng những con ngựa thật đang cần việc.
     */
    private static final String SELECT_BOARD = """
            SELECT h.horse_id, h.horse_name, h.registration_code, h.stall_no, h.created_at,
                   t.care_type,
                   m.record_id, m.exam_date, m.next_due_date,
                   u.full_name AS vet_name
            FROM HORSE h
            CROSS JOIN (VALUES ('VACCINATION'), ('DEWORMING'), ('FARRIER')) AS t(care_type)
            OUTER APPLY (
                SELECT TOP 1 r.record_id, r.exam_date, r.next_due_date, r.vet_id
                FROM MEDICAL_RECORD r
                WHERE r.horse_id = h.horse_id AND r.record_type = t.care_type
                ORDER BY r.exam_date DESC
            ) m
            LEFT JOIN APP_USER u ON u.user_id = m.vet_id
            WHERE h.record_type = 'ACADEMY'
            """;

    private static PreventiveCareRow map(ResultSet rs, int rowNum) throws SQLException {
        return new PreventiveCareRow(
                rs.getInt("horse_id"),
                rs.getString("horse_name"),
                rs.getString("registration_code"),
                rs.getString("stall_no"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getString("care_type"),
                rs.getObject("record_id", Integer.class),
                rs.getObject("exam_date", LocalDateTime.class),
                rs.getObject("next_due_date", LocalDate.class),
                rs.getString("vet_name"));
    }

    /** Dòng thô từ database, chưa phân loại. Service quyết OVERDUE hay DUE_SOON. */
    public record PreventiveCareRow(
            int horseId, String horseName, String registrationCode, String stallNo,
            LocalDateTime horseCreatedAt, String careType,
            Integer lastRecordId, LocalDateTime lastExamDate, LocalDate nextDueDate,
            String vetName) {}

    /** Toàn đàn, ba ô mỗi con. Thứ tự cuối cùng do service xếp theo mức cấp thiết. */
    public List<PreventiveCareRow> findAll() {
        // Ngựa đã rút (V15) không còn ở học viện nên không còn lịch tiêm, tẩy giun, đóng móng.
        return jdbc.sql(SELECT_BOARD + " WHERE h.activity_status = 'ACTIVE'")
                .query(PreventiveCareRepository::map)
                .list();
    }

    /** Ba ô của một con ngựa. */
    public List<PreventiveCareRow> findByHorse(int horseId) {
        return jdbc.sql(SELECT_BOARD + " AND h.horse_id = :id")
                .param("id", horseId)
                .query(PreventiveCareRepository::map)
                .list();
    }
}

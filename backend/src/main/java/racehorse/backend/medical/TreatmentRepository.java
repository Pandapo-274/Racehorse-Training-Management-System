package racehorse.backend.medical;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * UC14 - truy cập bảng TREATMENT.
 *
 * Cùng một lưu ý về lược đồ như INJURY: TREATMENT không có horse_id. Nó trỏ
 * vào MEDICAL_RECORD qua record_id, và con ngựa nằm ở đó. Nên mọi câu hỏi
 * "thuốc của con ngựa này" đều phải đi qua phép nối ấy.
 */
@Repository
public class TreatmentRepository {

    private final JdbcClient jdbc;

    public TreatmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * active tính trong SQL bằng CAST(GETDATE() AS DATE) chứ không để Java tự
     * so sánh sau: start_date và end_date là DATE, nên so với giờ của database
     * giữ cho "đang điều trị" cùng một mốc với mọi câu khác trong hệ thống.
     *
     * end_date NULL nghĩa là chưa định ngày kết thúc, nên vẫn còn hiệu lực -
     * đó là lý do có nhánh IS NULL thay vì chỉ so <=.
     */
    private static final String SELECT_TREATMENT = """
            SELECT t.treatment_id, t.record_id, m.horse_id, h.horse_name,
                   t.medicine_name, t.dosage, t.frequency, t.instruction,
                   t.start_date, t.end_date,
                   CASE WHEN t.start_date <= CAST(GETDATE() AS DATE)
                             AND (t.end_date IS NULL OR t.end_date >= CAST(GETDATE() AS DATE))
                        THEN 1 ELSE 0 END AS is_active
            FROM TREATMENT t
            JOIN MEDICAL_RECORD m ON m.record_id = t.record_id
            JOIN HORSE h          ON h.horse_id  = m.horse_id
            """;

    private static TreatmentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new TreatmentRow(
                rs.getInt("treatment_id"),
                rs.getInt("record_id"),
                rs.getInt("horse_id"),
                rs.getString("horse_name"),
                rs.getString("medicine_name"),
                rs.getString("dosage"),
                rs.getString("frequency"),
                rs.getString("instruction"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBoolean("is_active"));
    }

    public Optional<TreatmentRow> findById(int treatmentId) {
        return jdbc.sql(SELECT_TREATMENT + " WHERE t.treatment_id = :id")
                .param("id", treatmentId)
                .query(TreatmentRepository::map)
                .optional();
    }

    /** Thuốc kê theo một lần khám cụ thể. */
    public List<TreatmentRow> findByRecord(int recordId) {
        return jdbc.sql(SELECT_TREATMENT + " WHERE t.record_id = :recordId ORDER BY t.start_date DESC")
                .param("recordId", recordId)
                .query(TreatmentRepository::map)
                .list();
    }

    /**
     * Toàn bộ đơn thuốc của một con ngựa, mới nhất lên trước.
     *
     * Giữ cả mục đã hết hạn: màn hình cần đọc được bệnh sử dùng thuốc, và
     * "con này đã dùng Phenylbutazone ba lần trong năm" chỉ thấy được khi
     * mục cũ còn đó.
     */
    public List<TreatmentRow> findByHorse(int horseId) {
        return jdbc.sql(SELECT_TREATMENT + " WHERE m.horse_id = :horseId ORDER BY t.start_date DESC")
                .param("horseId", horseId)
                .query(TreatmentRepository::map)
                .list();
    }

    /**
     * Mọi mục điều trị đang có hiệu lực hôm nay trên toàn đàn, xếp theo tên ngựa.
     *
     * Cùng điều kiện "đang điều trị" với cột is_active của SELECT_TREATMENT (kể cả
     * end_date NULL = chưa định ngày kết thúc). Mục kê trước cho đợt sau
     * (start_date > hôm nay) chưa tính là đang dùng nên không có mặt ở đây.
     *
     * Chỉ ngựa ACADEMY: hồ sơ PEDIGREE không nằm trong chuồng nên không ai cho
     * thuốc, cùng lý do với bảng sức khoẻ và bảng nhắc lịch.
     */
    public List<TreatmentRow> findActive() {
        return jdbc.sql(SELECT_TREATMENT + """
                 WHERE h.record_type = 'ACADEMY'
                   AND h.activity_status = 'ACTIVE'
                   AND t.start_date <= CAST(GETDATE() AS DATE)
                   AND (t.end_date IS NULL OR t.end_date >= CAST(GETDATE() AS DATE))
                 ORDER BY h.horse_name, t.start_date DESC, t.treatment_id DESC
                """)
                .query(TreatmentRepository::map)
                .list();
    }

    public int insert(TreatmentRequest r) {
        return jdbc.sql("""
                INSERT INTO TREATMENT
                    (record_id, medicine_name, dosage, frequency, instruction, start_date, end_date)
                OUTPUT INSERTED.treatment_id
                VALUES
                    (:recordId, :medicine, :dosage, :frequency, :instruction, :startDate, :endDate)
                """)
                .param("recordId", r.recordId())
                .param("medicine", blankToNull(r.medicineName()))
                .param("dosage", blankToNull(r.dosage()))
                .param("frequency", blankToNull(r.frequency()))
                .param("instruction", blankToNull(r.instruction()))
                .param("startDate", r.startDate())
                .param("endDate", r.endDate())
                .query(Integer.class)
                .single();
    }

    /**
     * Kết thúc một mục điều trị sớm hơn dự kiến.
     *
     * Đặt end_date thành hôm nay thay vì xoá dòng: con ngựa đã thật sự dùng
     * thuốc trong khoảng đó, và xoá đi là viết lại bệnh sử. Điều kiện
     * end_date IS NULL OR end_date > hôm nay để không "kết thúc" lại một mục
     * đã kết thúc từ trước, vì làm thế sẽ đẩy ngày kết thúc cũ thành hôm nay.
     */
    public int endToday(int treatmentId) {
        return jdbc.sql("""
                UPDATE TREATMENT
                SET end_date = CAST(GETDATE() AS DATE)
                WHERE treatment_id = :id
                  AND (end_date IS NULL OR end_date > CAST(GETDATE() AS DATE))
                """)
                .param("id", treatmentId)
                .update();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}

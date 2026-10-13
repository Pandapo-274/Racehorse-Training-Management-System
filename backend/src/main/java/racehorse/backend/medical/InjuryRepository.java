package racehorse.backend.medical;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Mọi truy cập vào bảng INJURY, cho cả UC15 (ghim dấu) và UC18 (khép dấu).
 *
 * Gom về một lớp chứ không để mỗi use case tự viết câu của mình, vì một sự
 * thật về lược đồ phải được nhắc lại trong MỌI câu: INJURY không có horse_id.
 * Nó trỏ vào MEDICAL_RECORD qua record_id, và con ngựa nằm ở đó. Mỗi câu
 * "chấn thương của con ngựa này" viết rời là một cơ hội quên phép nối ấy, và
 * quên thì lấy nhầm chấn thương của con khác mà không có lỗi nào báo.
 */
@Repository
public class InjuryRepository {

    private final JdbcClient jdbc;

    public InjuryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Một chấn thương còn mở, đủ chi tiết để nói vì sao chưa clear được (UC18). */
    public record OpenInjury(int injuryId, String bodyLocation, String severity, String status) {}

    private static final String SELECT_MARKER = """
            SELECT i.injury_id, i.record_id, m.horse_id, h.horse_name,
                   i.body_location, i.coordinate_x, i.coordinate_y,
                   i.severity, i.status, i.marked_at, i.recovered_at,
                   u.full_name AS vet_name, m.exam_date
            FROM INJURY i
            JOIN MEDICAL_RECORD m ON m.record_id = i.record_id
            JOIN HORSE h          ON h.horse_id  = m.horse_id
            JOIN APP_USER u       ON u.user_id   = m.vet_id
            """;

    private static InjuryMarkerRow map(ResultSet rs, int rowNum) throws SQLException {
        return new InjuryMarkerRow(
                rs.getInt("injury_id"),
                rs.getInt("record_id"),
                rs.getInt("horse_id"),
                rs.getString("horse_name"),
                rs.getString("body_location"),
                rs.getBigDecimal("coordinate_x"),
                rs.getBigDecimal("coordinate_y"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getObject("marked_at", LocalDateTime.class),
                rs.getObject("recovered_at", LocalDateTime.class),
                rs.getString("vet_name"),
                rs.getObject("exam_date", LocalDateTime.class));
    }

    public Optional<InjuryMarkerRow> findById(int injuryId) {
        return jdbc.sql(SELECT_MARKER + " WHERE i.injury_id = :id")
                .param("id", injuryId)
                .query(InjuryRepository::map)
                .optional();
    }

    /**
     * Mọi dấu của một con ngựa, kể cả dấu đã RECOVERED.
     *
     * Dấu cũ KHÔNG bị lọc ra: mô hình giải phẫu cần vẽ được bệnh sử, và chỗ
     * nào chấn thương lặp lại chỉ nhìn thấy khi cả dấu cũ còn trên hình.
     * Mới nhất lên trước để màn hình vẽ dấu mới nằm trên dấu cũ khi trùng chỗ.
     */
    public List<InjuryMarkerRow> findByHorse(int horseId) {
        return jdbc.sql(SELECT_MARKER + " WHERE m.horse_id = :horseId ORDER BY i.marked_at DESC")
                .param("horseId", horseId)
                .query(InjuryRepository::map)
                .list();
    }

    /** Dấu thuộc một hồ sơ khám cụ thể. */
    public List<InjuryMarkerRow> findByRecord(int recordId) {
        return jdbc.sql(SELECT_MARKER + " WHERE i.record_id = :recordId ORDER BY i.marked_at DESC")
                .param("recordId", recordId)
                .query(InjuryRepository::map)
                .list();
    }

    /**
     * UC15 - ghim một dấu.
     *
     * status và marked_at không nằm trong câu INSERT: DF_INJ_status đặt
     * 'TREATING' và DF_INJ_marked đặt SYSUTCDATETIME(). Để database tự điền
     * thì thời điểm ghim là thời điểm của database, không phải của máy chạy
     * ứng dụng - hai máy lệch giờ là bệnh sử xếp sai thứ tự.
     */
    public int insertMarker(InjuryMarkerRequest r) {
        return jdbc.sql("""
                INSERT INTO INJURY
                    (record_id, body_location, coordinate_x, coordinate_y, severity)
                OUTPUT INSERTED.injury_id
                VALUES
                    (:recordId, :bodyLocation, :x, :y, :severity)
                """)
                .param("recordId", r.recordId())
                .param("bodyLocation", r.bodyLocation().trim())
                .param("x", r.coordinateX())
                .param("y", r.coordinateY())
                .param("severity", r.severity())
                .query(Integer.class)
                .single();
    }

    /**
     * Đã có dấu nào CÒN MỞ ở đúng vị trí ấy trên cùng hồ sơ chưa.
     *
     * Chặn cú bấm đôi trên mô hình, chuyện rất dễ xảy ra khi ghim bằng chuột.
     * Chỉ tính dấu chưa RECOVERED: cùng một chỗ bị chấn thương lại sau khi đã
     * lành là bệnh sử thật, phải ghim được.
     */
    public boolean hasOpenMarkerAt(int recordId, String bodyLocation) {
        Integer n = jdbc.sql("""
                SELECT COUNT(*) FROM INJURY
                WHERE record_id = :recordId AND body_location = :loc AND status <> 'RECOVERED'
                """)
                .param("recordId", recordId)
                .param("loc", bodyLocation.trim())
                .query(Integer.class)
                .single();
        return n != null && n > 0;
    }

    /** Con ngựa mà một injury_id thuộc về, để chặn việc chạm vào chấn thương con khác. */
    public Optional<Integer> findHorseOfInjury(int injuryId) {
        return jdbc.sql("""
                SELECT m.horse_id
                FROM INJURY i
                JOIN MEDICAL_RECORD m ON m.record_id = i.record_id
                WHERE i.injury_id = :id
                """)
                .param("id", injuryId)
                .query(Integer.class)
                .optional();
    }

    /**
     * Những chấn thương của con ngựa chưa ở trạng thái RECOVERED (UC18).
     *
     * PARTIAL cũng tính là còn mở: hồi phục một phần nghĩa là chưa hồi phục.
     */
    public List<OpenInjury> findOpenInjuries(int horseId) {
        return jdbc.sql("""
                SELECT i.injury_id, i.body_location, i.severity, i.status
                FROM INJURY i
                JOIN MEDICAL_RECORD m ON m.record_id = i.record_id
                WHERE m.horse_id = :horseId AND i.status <> 'RECOVERED'
                ORDER BY i.marked_at
                """)
                .param("horseId", horseId)
                .query((rs, n) -> new OpenInjury(
                        rs.getInt("injury_id"),
                        rs.getString("body_location"),
                        rs.getString("severity"),
                        rs.getString("status")))
                .list();
    }

    /**
     * UC18 - khép một chấn thương.
     *
     * recovered_at phải được đặt cùng lúc, không phải để NULL rồi điền sau:
     * CK_INJ_recovered yêu cầu status <> 'RECOVERED' OR recovered_at IS NOT
     * NULL, nên bỏ nó ra là database tự chối câu lệnh.
     *
     * Điều kiện status <> 'RECOVERED' nằm trong chính câu UPDATE: hai bác sĩ
     * cùng khép một chấn thương thì người sau nhận 0 dòng và service biết là
     * mình đến muộn.
     */
    public int markRecovered(int injuryId) {
        return jdbc.sql("""
                UPDATE INJURY
                SET status = 'RECOVERED', recovered_at = SYSUTCDATETIME()
                WHERE injury_id = :id AND status <> 'RECOVERED'
                """)
                .param("id", injuryId)
                .update();
    }

    /**
     * UC15 - xoá một dấu ghim sai chỗ.
     *
     * Chỉ xoá được dấu còn TREATING. Dấu đã PARTIAL hoặc RECOVERED nghĩa là
     * đã có người theo dõi và kết luận trên nó, lúc đó nó là bệnh sử chứ
     * không còn là một cú bấm sai nữa.
     */
    public int deleteMarker(int injuryId) {
        return jdbc.sql("DELETE FROM INJURY WHERE injury_id = :id AND status = 'TREATING'")
                .param("id", injuryId)
                .update();
    }

    /**
     * Có lệnh khoá nào trỏ vào dấu này không.
     *
     * Phải hỏi trước khi xoá, vì FK_LOCK_injury sẽ chặn câu DELETE và lỗi
     * ràng buộc khoá ngoại nổi lên thành 500 - người dùng nhận "lỗi hệ thống"
     * cho một việc thật ra có lý do rõ ràng và nói được thành câu.
     */
    public boolean isReferencedByLock(int injuryId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM TRAINING_LOCK WHERE injury_id = :id")
                .param("id", injuryId)
                .query(Integer.class)
                .single();
        return n != null && n > 0;
    }
}

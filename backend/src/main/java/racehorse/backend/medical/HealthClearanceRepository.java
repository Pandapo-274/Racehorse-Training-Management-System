package racehorse.backend.medical;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * UC18 - truy vấn trên INJURY và TRAINING_LOCK.
 *
 * Hai bảng này chưa có package nào sở hữu (dashboard chỉ đếm, không ghi), nên
 * đường ghi để khép chấn thương và mở lệnh khoá đặt ở đây, cạnh UC14, vì cùng
 * một vai trò và cùng một luồng nghiệp vụ.
 *
 * Lưu ý về lược đồ: INJURY KHÔNG có horse_id. Nó trỏ vào MEDICAL_RECORD qua
 * record_id, và con ngựa nằm ở đó. Nên mọi câu hỏi "chấn thương của con ngựa
 * này" đều phải đi qua phép nối đó - quên là lấy nhầm chấn thương con khác.
 */
@Repository
public class HealthClearanceRepository {

    private final JdbcClient jdbc;

    public HealthClearanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Một chấn thương còn mở, đủ chi tiết để nói cho bác sĩ biết vì sao chưa clear được. */
    public record OpenInjury(int injuryId, String bodyLocation, String severity, String status) {}

    /**
     * Những chấn thương của con ngựa chưa ở trạng thái RECOVERED.
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

    /** Con ngựa mà một injury_id thuộc về, để chặn việc khép chấn thương của con khác. */
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
     * Khép một chấn thương.
     *
     * recovered_at phải được đặt cùng lúc, không phải để NULL rồi điền sau:
     * CK_INJ_recovered yêu cầu status <> 'RECOVERED' OR recovered_at IS NOT
     * NULL, nên bỏ nó ra là database tự chối câu lệnh.
     *
     * Điều kiện status <> 'RECOVERED' nằm trong chính câu UPDATE, cùng lý do
     * như markReportExamined ở UC14: hai bác sĩ cùng khép một chấn thương thì
     * người sau nhận 0 dòng và service biết là mình đến muộn.
     */
    public int markInjuryRecovered(int injuryId) {
        return jdbc.sql("""
                UPDATE INJURY
                SET status = 'RECOVERED', recovered_at = SYSUTCDATETIME()
                WHERE injury_id = :id AND status <> 'RECOVERED'
                """)
                .param("id", injuryId)
                .update();
    }

    /* ---------- Lệnh khoá tập ---------- */

    public List<Integer> findActiveLockIds(int horseId) {
        return jdbc.sql("SELECT lock_id FROM TRAINING_LOCK WHERE horse_id = :id AND status = 'ACTIVE'")
                .param("id", horseId)
                .query(Integer.class)
                .list();
    }

    /**
     * Mở mọi lệnh khoá đang ACTIVE của con ngựa.
     *
     * released_by và released_at đi cùng status, vì CK_LOCK_released bắt đúng
     * như vậy. Một câu UPDATE cho cả nhóm thay vì vòng lặp từng dòng: ở đây
     * UX_LOCK_active đã bảo đảm mỗi con ngựa tối đa một lệnh ACTIVE, nhưng
     * viết theo nhóm thì vẫn đúng nếu sau này chỉ mục đó được nới ra.
     */
    public int releaseActiveLocks(int horseId, int releasedBy) {
        return jdbc.sql("""
                UPDATE TRAINING_LOCK
                SET status = 'RELEASED', released_by = :vet, released_at = SYSUTCDATETIME()
                WHERE horse_id = :id AND status = 'ACTIVE'
                """)
                .param("vet", releasedBy)
                .param("id", horseId)
                .update();
    }

    /**
     * Đặt con ngựa về ELIGIBLE, với điều kiện nó chưa ở ELIGIBLE.
     *
     * Đây là điểm xếp hàng của cả UC18. Hai bác sĩ cùng mở màn xác nhận hồi
     * phục của một con ngựa thì cả hai đều thấy "chưa eligible" ở bước kiểm;
     * chỉ câu UPDATE này phân định được ai thật sự đổi. Người nhận 0 dòng bị
     * huỷ toàn bộ giao dịch, nên không sinh ra hai bản ghi xác nhận cho cùng
     * một lần hồi phục.
     */
    public int clearHorse(int horseId) {
        return jdbc.sql("""
                UPDATE HORSE
                SET status = 'ELIGIBLE', updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id AND status <> 'ELIGIBLE'
                """)
                .param("id", horseId)
                .update();
    }
}

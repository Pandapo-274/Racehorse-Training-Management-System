package racehorse.backend.medical;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Mọi truy cập vào bảng TRAINING_LOCK, cho cả UC16 (ra lệnh, mở lệnh) và UC18
 * (mở hàng loạt khi xác nhận hồi phục).
 *
 * Gom về một lớp cùng lý do như InjuryRepository: từ lúc UC16 ra đời, bảng
 * này có hai use case cùng ghi, và cái bẫy của nó - chỉ mục UX_LOCK_active -
 * phải được nhớ ở mọi đường ghi, chứ không phải chỗ nhớ chỗ quên.
 */
@Repository
public class TrainingLockRepository {

    private final JdbcClient jdbc;

    public TrainingLockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * LEFT JOIN cho injury và người mở lệnh, INNER JOIN cho horse và người ra
     * lệnh. Lý do: injury_id và released_by đều NULL được (lệnh khẩn cấp chưa
     * có chẩn đoán, lệnh còn đang hiệu lực), nên nối trong sẽ làm những dòng
     * ấy biến mất khỏi kết quả - đúng những dòng mà màn quản lý cần nhất.
     */
    private static final String SELECT_LOCK = """
            SELECT l.lock_id, l.horse_id, h.horse_name,
                   l.injury_id, i.body_location AS injury_body_location,
                   i.status AS injury_status,
                   l.issued_by, iu.full_name AS issued_by_name,
                   l.reason, l.lock_level, l.issued_at,
                   l.released_by, ru.full_name AS released_by_name,
                   l.released_at, l.status
            FROM TRAINING_LOCK l
            JOIN HORSE h           ON h.horse_id = l.horse_id
            JOIN APP_USER iu       ON iu.user_id = l.issued_by
            LEFT JOIN INJURY i     ON i.injury_id = l.injury_id
            LEFT JOIN APP_USER ru  ON ru.user_id = l.released_by
            """;

    private static TrainingLockRow map(ResultSet rs, int rowNum) throws SQLException {
        return new TrainingLockRow(
                rs.getInt("lock_id"),
                rs.getInt("horse_id"),
                rs.getString("horse_name"),
                rs.getObject("injury_id", Integer.class),
                rs.getString("injury_body_location"),
                rs.getString("injury_status"),
                rs.getInt("issued_by"),
                rs.getString("issued_by_name"),
                rs.getString("reason"),
                rs.getString("lock_level"),
                rs.getObject("issued_at", LocalDateTime.class),
                rs.getObject("released_by", Integer.class),
                rs.getString("released_by_name"),
                rs.getObject("released_at", LocalDateTime.class),
                rs.getString("status"));
    }

    public Optional<TrainingLockRow> findById(int lockId) {
        return jdbc.sql(SELECT_LOCK + " WHERE l.lock_id = :id")
                .param("id", lockId)
                .query(TrainingLockRepository::map)
                .optional();
    }

    /** Lệnh đang hiệu lực của một con ngựa. UX_LOCK_active bảo đảm tối đa một. */
    public Optional<TrainingLockRow> findActiveByHorse(int horseId) {
        return jdbc.sql(SELECT_LOCK + " WHERE l.horse_id = :id AND l.status = 'ACTIVE'")
                .param("id", horseId)
                .query(TrainingLockRepository::map)
                .optional();
    }

    /** Toàn bộ bệnh sử khoá tập của một con ngựa, mới nhất lên trước. */
    public List<TrainingLockRow> findByHorse(int horseId) {
        return jdbc.sql(SELECT_LOCK + " WHERE l.horse_id = :id ORDER BY l.issued_at DESC")
                .param("id", horseId)
                .query(TrainingLockRepository::map)
                .list();
    }

    /**
     * Mọi lệnh đang hiệu lực trong toàn chuồng - đây là màn Training Lock
     * Management. FULL lên trước HEAVY_WORK_ONLY, rồi lệnh cũ lên trước: lệnh
     * nghiêm trọng nhất và tồn lâu nhất là lệnh cần xử lý trước.
     */
    public List<TrainingLockRow> findAllActive() {
        return jdbc.sql(SELECT_LOCK + """
                 WHERE l.status = 'ACTIVE'
                 ORDER BY CASE l.lock_level WHEN 'FULL' THEN 0 ELSE 1 END, l.issued_at
                """)
                .query(TrainingLockRepository::map)
                .list();
    }

    /**
     * Ra lệnh khoá.
     *
     * status và issued_at không nằm trong câu INSERT: DF_LOCK_status đặt
     * 'ACTIVE', DF_LOCK_issued đặt SYSUTCDATETIME(). Giờ của database, không
     * phải giờ của máy ứng dụng.
     *
     * Câu này có thể ném DuplicateKeyException vì UX_LOCK_active. Service bắt
     * và dịch thành 409 - xem ghi chú ở TrainingLockService.issue.
     */
    public int insert(TrainingLockRequest r, int issuedBy) {
        return jdbc.sql("""
                INSERT INTO TRAINING_LOCK
                    (horse_id, injury_id, issued_by, reason, lock_level)
                OUTPUT INSERTED.lock_id
                VALUES
                    (:horseId, :injuryId, :issuedBy, :reason, :lockLevel)
                """)
                .param("horseId", r.horseId())
                .param("injuryId", r.injuryId())
                .param("issuedBy", issuedBy)
                .param("reason", r.reason().trim())
                .param("lockLevel", r.lockLevel())
                .query(Integer.class)
                .single();
    }

    /**
     * Mở một lệnh cụ thể.
     *
     * released_by và released_at đi cùng status vì CK_LOCK_released bắt đúng
     * thế. Điều kiện status = 'ACTIVE' nằm trong chính câu UPDATE: hai người
     * cùng mở một lệnh thì người sau nhận 0 dòng, và service phân biệt được
     * "đã mở rồi" với "vừa mở xong" - nếu không thì người sau nhận 200 cho
     * một việc mình không làm.
     */
    public int release(int lockId, int releasedBy) {
        return jdbc.sql("""
                UPDATE TRAINING_LOCK
                SET status = 'RELEASED', released_by = :vet, released_at = SYSUTCDATETIME()
                WHERE lock_id = :id AND status = 'ACTIVE'
                """)
                .param("vet", releasedBy)
                .param("id", lockId)
                .update();
    }

    /* ---------- UC18 dùng ---------- */

    public List<Integer> findActiveLockIds(int horseId) {
        return jdbc.sql("SELECT lock_id FROM TRAINING_LOCK WHERE horse_id = :id AND status = 'ACTIVE'")
                .param("id", horseId)
                .query(Integer.class)
                .list();
    }

    /**
     * Mở mọi lệnh đang ACTIVE của con ngựa, một câu cho cả nhóm.
     *
     * Hiện UX_LOCK_active đã bảo đảm tối đa một lệnh ACTIVE mỗi con, nhưng
     * viết theo nhóm thì vẫn đúng nếu sau này chỉ mục ấy được nới ra.
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
}

package racehorse.backend.horserequest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HorseRequestRepository {

    private final JdbcClient jdbc;

    public HorseRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /* LEFT JOIN cho người duyệt: yêu cầu đang chờ thì chưa có ai duyệt, dùng
       JOIN thường sẽ làm biến mất đúng những dòng cần xem nhất. */
    private static final String SELECT_ROW = """
            SELECT q.request_id, q.owner_id, o.full_name AS owner_name,
                   q.horse_name, q.breed, q.horse_gender, q.date_of_birth, q.color, q.note,
                   q.status, q.created_at,
                   q.reviewed_by, r.full_name AS reviewer_name, q.reviewed_at, q.review_note,
                   q.horse_id
            FROM HORSE_REQUEST q
            JOIN APP_USER o ON o.user_id = q.owner_id
            LEFT JOIN APP_USER r ON r.user_id = q.reviewed_by
            """;

    private static HorseRequestRow map(ResultSet rs, int rowNum) throws SQLException {
        return new HorseRequestRow(
                rs.getInt("request_id"),
                rs.getInt("owner_id"), rs.getString("owner_name"),
                rs.getString("horse_name"), rs.getString("breed"), rs.getString("horse_gender"),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("color"), rs.getString("note"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("reviewed_by", Integer.class), rs.getString("reviewer_name"),
                rs.getObject("reviewed_at", LocalDateTime.class), rs.getString("review_note"),
                rs.getObject("horse_id", Integer.class));
    }

    public Optional<HorseRequestRow> findById(int requestId) {
        return jdbc.sql(SELECT_ROW + " WHERE q.request_id = :id")
                .param("id", requestId)
                .query(HorseRequestRepository::map)
                .optional();
    }

    /** Yêu cầu của riêng một chủ ngựa, mới nhất trước. */
    public List<HorseRequestRow> findByOwner(int ownerId) {
        return jdbc.sql(SELECT_ROW + " WHERE q.owner_id = :id ORDER BY q.created_at DESC")
                .param("id", ownerId)
                .query(HorseRequestRepository::map)
                .list();
    }

    /** Hàng chờ của học viện: đang chờ lên trước, trong đó cũ nhất lên trước. */
    public List<HorseRequestRow> findAll() {
        return jdbc.sql(SELECT_ROW + """
                 ORDER BY CASE WHEN q.status = 'PENDING' THEN 0 ELSE 1 END,
                          q.created_at DESC
                """)
                .query(HorseRequestRepository::map)
                .list();
    }

    /** true nếu chủ ngựa này đang có một yêu cầu chờ duyệt cho đúng cái tên đó. */
    public boolean hasOpenRequest(int ownerId, String horseName) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM HORSE_REQUEST
                WHERE owner_id = :owner AND horse_name = :name AND status = 'PENDING'
                """)
                .param("owner", ownerId)
                .param("name", horseName)
                .query(Integer.class)
                .single() > 0;
    }

    public int insert(int ownerId, SubmitRequest r) {
        return jdbc.sql("""
                INSERT INTO HORSE_REQUEST (owner_id, horse_name, breed, horse_gender,
                                           date_of_birth, color, note)
                OUTPUT INSERTED.request_id
                VALUES (:owner, :name, :breed, :gender, :dob, :color, :note)
                """)
                .param("owner", ownerId)
                .param("name", r.horseName())
                .param("breed", r.breed())
                .param("gender", r.horseGender())
                .param("dob", r.dateOfBirth())
                .param("color", r.color())
                .param("note", r.note())
                .query(Integer.class)
                .single();
    }

    /**
     * Đánh dấu đã xử lý, nhưng CHỈ khi yêu cầu vẫn đang chờ.
     *
     * Điều kiện status = 'PENDING' nằm ngay trong câu UPDATE chứ không phải
     * kiểm trước rồi mới ghi: hai người duyệt mở cùng một hàng chờ và bấm gần
     * như cùng lúc là chuyện thường. Gộp vào một câu thì người thứ hai nhận
     * 0 dòng bị ảnh hưởng và service báo lại đúng là "ai đó vừa xử lý rồi",
     * thay vì tạo ra con ngựa thứ hai.
     *
     * @return số dòng bị ảnh hưởng: 1 nếu thắng, 0 nếu người khác đã xử lý.
     */
    public int markReviewed(int requestId, String status, int reviewerId,
                            Integer horseId, String note) {
        return jdbc.sql("""
                UPDATE HORSE_REQUEST
                SET status = :status,
                    reviewed_by = :reviewer,
                    reviewed_at = SYSUTCDATETIME(),
                    review_note = :note,
                    horse_id = :horseId
                WHERE request_id = :id AND status = 'PENDING'
                """)
                .param("status", status)
                .param("reviewer", reviewerId)
                .param("note", note)
                .param("horseId", horseId)
                .param("id", requestId)
                .update();
    }
}

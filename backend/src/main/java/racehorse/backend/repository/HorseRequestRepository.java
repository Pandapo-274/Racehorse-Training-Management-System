package racehorse.backend.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.dto.SubmitRequest;
import racehorse.backend.entity.HorseRequestRow;
import racehorse.backend.service.HorsePolicy;

// SQL cho bảng HORSE_REQUEST. SELECT_ROW JOIN thêm tên chủ, người duyệt, tên cha và mẹ khai báo.
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
                   q.horse_name, q.breed, q.horse_gender, q.record_type, q.date_of_birth, q.color, q.note,
                   q.status, q.created_at,
                   q.reviewed_by, r.full_name AS reviewer_name, q.reviewed_at, q.review_note,
                   q.horse_id,
                   q.sire_id, s.horse_name AS sire_name, q.dam_id, d.horse_name AS dam_name,
                   q.photo_url, q.weight, q.height_cm, q.surface, q.distance_pref, q.running_style,
                   q.preferred_start, q.training_weeks, q.contract_months,
                   q.preferred_trainer_id, pt.full_name AS preferred_trainer_name,
                   (SELECT COUNT(*) FROM HORSE th
                    WHERE th.trainer_id = q.preferred_trainer_id AND th.activity_status = 'ACTIVE') AS pt_load,
                   ISNULL(pt.max_horses, 10) AS pt_max,
                   (SELECT COUNT(*) FROM HORSE oh
                    WHERE oh.owner_id = q.owner_id AND oh.activity_status = 'ACTIVE'
                      AND oh.record_type = 'ACADEMY') AS owner_active_horses,
                   c.contract_id, c.status AS contract_status
            FROM HORSE_REQUEST q
            JOIN APP_USER o ON o.user_id = q.owner_id
            LEFT JOIN APP_USER r ON r.user_id = q.reviewed_by
            LEFT JOIN HORSE s ON s.horse_id = q.sire_id
            LEFT JOIN HORSE d ON d.horse_id = q.dam_id
            LEFT JOIN APP_USER pt ON pt.user_id = q.preferred_trainer_id
            OUTER APPLY (SELECT TOP 1 hc.contract_id, hc.status FROM HORSE_CONTRACT hc
                         WHERE hc.request_id = q.request_id
                         ORDER BY hc.contract_id DESC) c
            """;

    private static HorseRequestRow map(ResultSet rs, int rowNum) throws SQLException {
        String breed = rs.getString("breed");
        LocalDate dob = rs.getObject("date_of_birth", LocalDate.class);
        BigDecimal weight = rs.getBigDecimal("weight");
        BigDecimal height = rs.getBigDecimal("height_cm");
        Integer preferredTrainerId = rs.getObject("preferred_trainer_id", Integer.class);
        return new HorseRequestRow(
                rs.getInt("request_id"),
                rs.getInt("owner_id"), rs.getString("owner_name"),
                rs.getString("horse_name"), breed, rs.getString("horse_gender"),
                rs.getString("record_type"),
                dob,
                rs.getString("color"), rs.getString("note"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("reviewed_by", Integer.class), rs.getString("reviewer_name"),
                rs.getObject("reviewed_at", LocalDateTime.class), rs.getString("review_note"),
                rs.getObject("horse_id", Integer.class),
                rs.getObject("sire_id", Integer.class), rs.getString("sire_name"),
                rs.getObject("dam_id", Integer.class), rs.getString("dam_name"),
                rs.getString("photo_url"),
                weight, height,
                rs.getString("surface"), rs.getString("distance_pref"), rs.getString("running_style"),
                rs.getObject("preferred_start", LocalDate.class),
                rs.getObject("training_weeks", Integer.class),
                rs.getObject("contract_months", Integer.class),
                preferredTrainerId, rs.getString("preferred_trainer_name"),
                preferredTrainerId == null ? null : rs.getInt("pt_load"),
                preferredTrainerId == null ? null : rs.getInt("pt_max"),
                rs.getInt("owner_active_horses"),
                HorsePolicy.growthFor(breed, dob, weight, height, LocalDate.now()),
                rs.getObject("contract_id", Integer.class), rs.getString("contract_status"));
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
                                           date_of_birth, color, note, record_type, sire_id, dam_id,
                                           weight, height_cm, surface, distance_pref, running_style,
                                           preferred_start, training_weeks, contract_months,
                                           preferred_trainer_id)
                OUTPUT INSERTED.request_id
                VALUES (:owner, :name, :breed, :gender, :dob, :color, :note, :recordType, :sire, :dam,
                        :weight, :height, :surface, :distance, :style,
                        :start, :weeks, :months, :trainer)
                """)
                .param("owner", ownerId)
                .param("name", r.horseName())
                .param("breed", r.breed())
                .param("gender", r.horseGender())
                .param("dob", r.dateOfBirth())
                .param("color", r.color())
                .param("note", r.note())
                .param("recordType", r.recordType())
                .param("sire", r.sireId())
                .param("dam", r.damId())
                .param("weight", r.weight())
                .param("height", r.heightCm())
                .param("surface", r.surface())
                .param("distance", r.distancePref())
                .param("style", r.runningStyle())
                .param("start", r.preferredStart())
                .param("weeks", r.trainingWeeks())
                .param("months", r.contractMonths())
                .param("trainer", r.preferredTrainerId())
                .query(Integer.class)
                .single();
    }

    /** V48: ảnh của yêu cầu, chỉ đổi được khi yêu cầu còn chờ duyệt. */
    public int updatePhoto(int requestId, int ownerId, String photoUrl) {
        return jdbc.sql("""
                UPDATE HORSE_REQUEST SET photo_url = :url
                WHERE request_id = :id AND owner_id = :owner AND status = 'PENDING'
                """)
                .param("url", photoUrl)
                .param("id", requestId)
                .param("owner", ownerId)
                .update();
    }

    /** V49: ngựa vừa được nhận vào CLB, ghi lại id để yêu cầu trỏ tới đúng con ngựa. */
    public int setHorse(int requestId, int horseId) {
        return jdbc.sql("UPDATE HORSE_REQUEST SET horse_id = :horse WHERE request_id = :id AND horse_id IS NULL")
                .param("horse", horseId)
                .param("id", requestId)
                .update();
    }

    /**
     * Chủ ngựa huỷ yêu cầu của chính mình. Cùng điều kiện như markReviewed:
     * owner_id và status = 'PENDING' nằm ngay trong câu UPDATE, nên nếu
     * Club Manager vừa duyệt xong thì lệnh huỷ nhận 0 dòng chứ không đè lên.
     *
     * @return 1 nếu huỷ được, 0 nếu yêu cầu không còn chờ.
     */
    public int markCancelled(int requestId, int ownerId) {
        return jdbc.sql("""
                UPDATE HORSE_REQUEST
                SET status = 'CANCELLED',
                    reviewed_by = :owner,
                    reviewed_at = SYSUTCDATETIME()
                WHERE request_id = :id AND owner_id = :owner AND status = 'PENDING'
                """)
                .param("owner", ownerId)
                .param("id", requestId)
                .update();
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

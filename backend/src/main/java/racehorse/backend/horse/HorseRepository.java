package racehorse.backend.horse;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HorseRepository {

    private final JdbcClient jdbc;

    public HorseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_HORSE = """
            SELECT h.horse_id, h.owner_id, u.full_name AS owner_name, h.sire_id, h.dam_id,
                   h.horse_name, h.registration_code, h.breed, h.horse_gender, h.date_of_birth,
                   h.color, h.weight, h.stall_no, h.status, h.created_at, h.updated_at
            FROM HORSE h JOIN APP_USER u ON u.user_id = h.owner_id
            """;

    private static HorseResponse mapHorse(ResultSet rs, int rowNum) throws SQLException {
        return new HorseResponse(
                rs.getInt("horse_id"), rs.getInt("owner_id"), rs.getString("owner_name"),
                rs.getObject("sire_id", Integer.class), rs.getObject("dam_id", Integer.class),
                rs.getString("horse_name"), rs.getString("registration_code"),
                rs.getString("breed"), rs.getString("horse_gender"),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("color"), rs.getObject("weight", BigDecimal.class),
                rs.getString("stall_no"), rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    public Optional<HorseResponse> findById(int horseId) {
        return jdbc.sql(SELECT_HORSE + " WHERE h.horse_id = :id")
                .param("id", horseId)
                .query(HorseRepository::mapHorse)
                .optional();
    }

    public List<HorseResponse> findAll() {
        return jdbc.sql(SELECT_HORSE + " ORDER BY h.horse_name")
                .query(HorseRepository::mapHorse)
                .list();
    }

    public List<HorseResponse> findByOwner(int ownerId) {
        return jdbc.sql(SELECT_HORSE + " WHERE h.owner_id = :ownerId ORDER BY h.horse_name")
                .param("ownerId", ownerId)
                .query(HorseRepository::mapHorse)
                .list();
    }

    /** excludeId = id ngựa đang sửa (để không tự báo trùng với chính mình); khi tạo mới truyền 0. */
    public boolean existsByRegistrationCode(String code, int excludeId) {
        return jdbc.sql("SELECT COUNT(*) FROM HORSE WHERE registration_code = :v AND horse_id <> :id")
                .param("v", code).param("id", excludeId)
                .query(Integer.class).single() > 0;
    }

    public boolean existsByStallNo(String stallNo, int excludeId) {
        return jdbc.sql("SELECT COUNT(*) FROM HORSE WHERE stall_no = :v AND horse_id <> :id")
                .param("v", stallNo).param("id", excludeId)
                .query(Integer.class).single() > 0;
    }

    public boolean existsById(int horseId) {
        return jdbc.sql("SELECT COUNT(*) FROM HORSE WHERE horse_id = :id")
                .param("id", horseId).query(Integer.class).single() > 0;
    }

    public int insert(HorseRequest r) {
        return jdbc.sql("""
                INSERT INTO HORSE (owner_id, sire_id, dam_id, horse_name, registration_code,
                                   breed, horse_gender, date_of_birth, color, weight, stall_no)
                OUTPUT INSERTED.horse_id
                VALUES (:ownerId, :sireId, :damId, :name, :code,
                        :breed, :gender, :dob, :color, :weight, :stallNo)
                """)
                .param("ownerId", r.ownerId())
                .param("sireId", r.sireId())
                .param("damId", r.damId())
                .param("name", r.horseName())
                .param("code", r.registrationCode())
                .param("breed", r.breed())
                .param("gender", r.horseGender())
                .param("dob", r.dateOfBirth())
                .param("color", r.color())
                .param("weight", r.weight())
                .param("stallNo", r.stallNo())
                .query(Integer.class)
                .single();
    }

    /** Trả số dòng được cập nhật (0 = không có ngựa này). */
    public int update(int horseId, HorseRequest r) {
        return jdbc.sql("""
                UPDATE HORSE
                SET owner_id = :ownerId, sire_id = :sireId, dam_id = :damId,
                    horse_name = :name, registration_code = :code, breed = :breed,
                    horse_gender = :gender, date_of_birth = :dob, color = :color,
                    weight = :weight, stall_no = :stallNo, updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id
                """)
                .param("id", horseId)
                .param("ownerId", r.ownerId())
                .param("sireId", r.sireId())
                .param("damId", r.damId())
                .param("name", r.horseName())
                .param("code", r.registrationCode())
                .param("breed", r.breed())
                .param("gender", r.horseGender())
                .param("dob", r.dateOfBirth())
                .param("color", r.color())
                .param("weight", r.weight())
                .param("stallNo", r.stallNo())
                .update();
    }
        public boolean isHorseOwner(int userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
                WHERE u.user_id = :id AND r.role_name = 'HORSE_OWNER'
                """)
                .param("id", userId).query(Integer.class).single() > 0;
    }
        public record PedigreeRow(int horseId, String horseName, String registrationCode,
                              String horseGender, Integer sireId, Integer damId) {}

    /** Lấy tổ tiên bằng recursive CTE. maxDepth chặn luôn trường hợp dữ liệu vòng lặp. */
    public List<PedigreeRow> findAncestors(int horseId, int maxDepth) {
        return jdbc.sql("""
                WITH tree AS (
                    SELECT horse_id, horse_name, registration_code, horse_gender,
                           sire_id, dam_id, 0 AS depth
                    FROM HORSE WHERE horse_id = :id
                    UNION ALL
                    SELECT h.horse_id, h.horse_name, h.registration_code, h.horse_gender,
                           h.sire_id, h.dam_id, t.depth + 1
                    FROM HORSE h JOIN tree t ON h.horse_id = t.sire_id OR h.horse_id = t.dam_id
                    WHERE t.depth < :maxDepth
                )
                SELECT DISTINCT horse_id, horse_name, registration_code, horse_gender, sire_id, dam_id
                FROM tree
                """)
                .param("id", horseId)
                .param("maxDepth", maxDepth)
                .query((rs, n) -> new PedigreeRow(
                        rs.getInt("horse_id"), rs.getString("horse_name"),
                        rs.getString("registration_code"), rs.getString("horse_gender"),
                        rs.getObject("sire_id", Integer.class), rs.getObject("dam_id", Integer.class)))
                .list();
    }
}
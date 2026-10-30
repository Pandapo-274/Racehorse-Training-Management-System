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
                   h.color, h.weight, h.stall_no, h.status, h.created_at, h.updated_at,
                   h.record_type, h.photo_url, h.activity_status, h.withdrawn_at
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
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getString("record_type"),
                rs.getString("photo_url"),
                rs.getString("activity_status"),
                rs.getObject("withdrawn_at", LocalDateTime.class));
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

    /** Chỉ ngựa còn ở học viện. Dùng cho Groom: ngựa đã rút không còn trong chuồng. */
    public List<HorseResponse> findActive() {
        return jdbc.sql(SELECT_HORSE + " WHERE h.activity_status = 'ACTIVE' ORDER BY h.horse_name")
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
                                   breed, horse_gender, date_of_birth, color, weight, stall_no,
                                   record_type)
                OUTPUT INSERTED.horse_id
                VALUES (:ownerId, :sireId, :damId, :name, :code,
                        :breed, :gender, :dob, :color, :weight, :stallNo,
                        :recordType)
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
                .param("recordType", r.recordType())
                .query(Integer.class)
                .single();
    }

    /**
     * Chủ ngựa sửa phần "lặt vặt". UPDATE này KHÔNG chạm vào owner_id,
     * registration_code, stall_no, weight, record_type, status: dù service có
     * sai thì tầng SQL cũng không thể ghi đè những cột định danh đó.
     *
     * @return số dòng được cập nhật (0 = không có ngựa này).
     */
    public int updateBasics(int horseId, HorseUpdateRequest r) {
        return jdbc.sql("""
                UPDATE HORSE
                SET sire_id = :sireId, dam_id = :damId,
                    horse_name = :name, breed = :breed,
                    horse_gender = :gender, date_of_birth = :dob, color = :color,
                    updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id
                """)
                .param("id", horseId)
                .param("sireId", r.sireId())
                .param("damId", r.damId())
                .param("name", r.horseName())
                .param("breed", r.breed())
                .param("gender", r.horseGender())
                .param("dob", r.dateOfBirth())
                .param("color", r.color())
                .update();
    }
    /**
     * Cân nặng hiện tại, do bác sĩ thú y cân lúc khám (MedicalRecordService.log).
     * Tách khỏi updateBasics vì chủ ngựa không được tự khai cân nặng.
     */
    public int updateWeight(int horseId, BigDecimal weight) {
        return jdbc.sql("UPDATE HORSE SET weight = :weight, updated_at = SYSUTCDATETIME() WHERE horse_id = :id")
                .param("weight", weight)
                .param("id", horseId)
                .update();
    }

    /** Đổi ảnh (url = null là gỡ ảnh). Tách khỏi updateBasics vì ảnh tải lên riêng, không đi cùng biểu mẫu. */
    public int updatePhoto(int horseId, String url) {
        return jdbc.sql("UPDATE HORSE SET photo_url = :url, updated_at = SYSUTCDATETIME() WHERE horse_id = :id")
                .param("url", url)
                .param("id", horseId)
                .update();
    }

    /**
     * Đánh dấu ngựa đã rút và trả chuồng, CHỈ khi nó vẫn đang ACTIVE.
     *
     * Trả chuồng (stall_no = NULL) vì con ngựa đã rời học viện: giữ số chuồng
     * thì UX_HORSE_stall chặn không cho xếp ngựa khác vào ô đó.
     *
     * @return 1 nếu đổi được, 0 nếu ngựa đã rút từ trước.
     */
    public int markWithdrawn(int horseId) {
        return jdbc.sql("""
                UPDATE HORSE
                SET activity_status = 'WITHDRAWN',
                    withdrawn_at = SYSUTCDATETIME(),
                    stall_no = NULL,
                    updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id AND activity_status = 'ACTIVE'
                """)
                .param("id", horseId)
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
    /**
     * candidateId có nằm trong danh sách tổ tiên của horseId không.
     *
     * Dùng để chặn vòng lặp phả hệ: trước khi gán P làm cha/mẹ của H, phải
     * chắc H chưa là tổ tiên của P. Không kiểm thì tạo được A là cha của B
     * trong khi B đã là cha của A, và cây phả hệ hiện ra một con ngựa làm ông
     * của chính nó.
     *
     * Hai điểm trong câu này đều có lý do:
     *
     *  - depth < 50 chứ không phải < 5 như findAncestors. findAncestors chỉ
     *    cần đủ sâu cho màn hình (tối đa 5 đời); còn ở đây bỏ sót một vòng lặp
     *    nằm sâu hơn nghĩa là cho phép tạo ra nó. 50 đời là quá đủ cho ngựa
     *    thật, mà vẫn là một con số hữu hạn.
     *
     *  - Và nó phải chịu được dữ liệu ĐÃ có vòng lặp từ trước. Nếu database
     *    hiện tại đã lẫn một vòng (vì phép kiểm này ra đời muộn), một CTE
     *    không chặn độ sâu sẽ chạy đến khi SQL Server ném lỗi MAXRECURSION, và
     *    phép kiểm an toàn lại chính là chỗ làm sập. Mốc 50 khiến câu này luôn
     *    kết thúc.
     */
    public boolean isAncestorOf(int candidateId, int horseId) {
        Integer hit = jdbc.sql("""
                WITH tree AS (
                    SELECT sire_id, dam_id, 0 AS depth
                    FROM HORSE WHERE horse_id = :horseId
                    UNION ALL
                    SELECT h.sire_id, h.dam_id, t.depth + 1
                    FROM HORSE h
                    JOIN tree t ON h.horse_id = t.sire_id OR h.horse_id = t.dam_id
                    WHERE t.depth < 50
                )
                SELECT TOP 1 1
                FROM tree
                WHERE sire_id = :candidateId OR dam_id = :candidateId
                """)
                .param("horseId", horseId)
                .param("candidateId", candidateId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return hit != null;
    }

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

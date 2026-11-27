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

import racehorse.backend.dto.HorseRequest;
import racehorse.backend.dto.HorseResponse;
import racehorse.backend.dto.HorseUpdateRequest;
import racehorse.backend.service.HorseService;
import racehorse.backend.service.MedicalRecordService;

// Mọi câu SQL trên bảng HORSE. SELECT_HORSE JOIN APP_USER hai lần: một lần lấy tên chủ (u),
// một lần (LEFT JOIN, vì có thể NULL) lấy tên HLV phụ trách (tr).
@Repository
public class HorseRepository {

    private final JdbcClient jdbc;

    public HorseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Một HLV kèm số ngựa đang quản lý và hạn mức, cho ô chọn HLV ở trang Club records.
    public record TrainerSummary(
            int userId,
            String username,
            String fullName,
            int managedHorseCount,
            int maxHorses
    ) {}

    private static final String SELECT_HORSE = """
            SELECT h.horse_id, h.owner_id, u.full_name AS owner_name, h.sire_id, h.dam_id,
                   h.horse_name, h.registration_code, h.breed, h.horse_gender, h.date_of_birth,
                   h.color, h.weight, h.stall_no, h.status, h.created_at, h.updated_at,
                   h.record_type, h.photo_url, h.activity_status, h.withdrawn_at,
                   h.trainer_id, tr.full_name AS trainer_name
            FROM HORSE h
            JOIN APP_USER u ON u.user_id = h.owner_id
            LEFT JOIN APP_USER tr ON tr.user_id = h.trainer_id
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
                rs.getObject("withdrawn_at", LocalDateTime.class),
                rs.getObject("trainer_id", Integer.class),
                rs.getString("trainer_name"));
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

    /**
     * Ngựa thuộc đội của một Groom: ngựa của Head Trainer mà Groom đó được Club Manager phân vào
     * (APP_USER.trainer_id), cộng với ngựa Groom đang có buổi tập hoặc việc chăm sóc được giao
     * (trường hợp được nhờ làm tạm cho đội khác). Groom chưa thuộc đội nào chỉ thấy phần sau.
     */
    private static final String GROOM_SCOPE = """
            (
                (h.trainer_id IS NOT NULL
                 AND h.trainer_id = (SELECT g.trainer_id FROM APP_USER g WHERE g.user_id = :groomId))
                OR EXISTS (SELECT 1 FROM TRAINING_SESSION s
                           WHERE s.horse_id = h.horse_id AND s.assigned_to = :groomId
                             AND s.status IN ('SCHEDULED', 'RUNNING', 'RECOVERY'))
                OR EXISTS (SELECT 1 FROM DAILY_TASK t
                           WHERE t.horse_id = h.horse_id AND t.assigned_to = :groomId
                             AND t.task_date >= CAST(SYSDATETIME() AS DATE))
            )
            """;

    public List<HorseResponse> findForGroom(int groomId) {
        return jdbc.sql(SELECT_HORSE + " WHERE h.activity_status = 'ACTIVE' AND " + GROOM_SCOPE
                        + " ORDER BY h.horse_name")
                .param("groomId", groomId)
                .query(HorseRepository::mapHorse)
                .list();
    }

    public boolean isInGroomTeam(int horseId, int groomId) {
        return jdbc.sql("SELECT COUNT(*) FROM HORSE h WHERE h.horse_id = :horseId AND " + GROOM_SCOPE)
                .param("horseId", horseId)
                .param("groomId", groomId)
                .query(Integer.class)
                .single() > 0;
    }

    public List<HorseResponse> findByOwner(int ownerId) {
        return jdbc.sql(SELECT_HORSE + " WHERE h.owner_id = :ownerId ORDER BY h.horse_name")
                .param("ownerId", ownerId)
                .query(HorseRepository::mapHorse)
                .list();
    }

    /** V37: Chỉ những ngựa Club Manager đã giao cho Head Trainer này quản lý. */
    public List<HorseResponse> findByTrainer(int trainerId) {
        return jdbc.sql(SELECT_HORSE + " WHERE h.trainer_id = :trainerId ORDER BY h.horse_name")
                .param("trainerId", trainerId)
                .query(HorseRepository::mapHorse)
                .list();
    }

    /** V37: Id các ngựa Head Trainer này đang được giao quản lý. */
    public java.util.Set<Integer> findHorseIdsByTrainer(int trainerId) {
        return new java.util.HashSet<>(jdbc.sql("SELECT horse_id FROM HORSE WHERE trainer_id = :trainerId")
                .param("trainerId", trainerId)
                .query(Integer.class)
                .list());
    }

    /** excludeId = id ngựa đang sửa (để không tự báo trùng với chính mình); khi tạo mới truyền 0. */
    /** Hồ sơ phả hệ của cả câu lạc bộ cộng ngựa của chủ này (form chọn cha / mẹ). */
    /**
     * Pedigree records + the owner's own horses + the parents already on the
     * horse being edited (forHorseId, 0 = none): a parent set before the rule
     * may belong to someone else, and the form must still show it, or saving
     * would look like the link was lost.
     */
    public List<HorseResponse> findParentCandidates(int ownerId, int forHorseId) {
        return jdbc.sql(SELECT_HORSE + """
                 WHERE h.record_type = 'PEDIGREE' OR h.owner_id = :ownerId
                    OR h.horse_id IN (SELECT x.sire_id FROM HORSE x WHERE x.horse_id = :forId AND x.owner_id = :ownerId)
                    OR h.horse_id IN (SELECT x.dam_id  FROM HORSE x WHERE x.horse_id = :forId AND x.owner_id = :ownerId)
                 ORDER BY h.horse_name
                """)
                .param("ownerId", ownerId)
                .param("forId", forHorseId)
                .query(HorseRepository::mapHorse)
                .list();
    }

    /** Ngựa này đã là cha hoặc mẹ của ít nhất một ngựa khác. */
    public boolean hasOffspring(int horseId) {
        return jdbc.sql("SELECT COUNT(*) FROM HORSE WHERE sire_id = :id OR dam_id = :id")
                .param("id", horseId).query(Integer.class).single() > 0;
    }

    /** Những việc đang chờ CHỦ HIỆN TẠI của ngựa: chặn chuyển chủ khi còn. */
    public record PendingOnOwner(int plans, int raceEntries, int withdrawals) {
        public boolean any() { return plans + raceEntries + withdrawals > 0; }
    }

    /** Suất đua: chỉ đề xuất chưa hết hạn (RaceRepository.AWAITING_OWNER). */
    public PendingOnOwner pendingOnOwner(int horseId) {
        return jdbc.sql("""
                SELECT
                  (SELECT COUNT(*) FROM TRAINING_PLAN WHERE horse_id = :id AND status = 'PENDING_APPROVAL') AS plans,
                  (SELECT COUNT(*) FROM RACE_REGISTRATION r JOIN RACE rc ON rc.race_id = r.race_id
                   WHERE r.horse_id = :id AND
                """ + RaceRepository.AWAITING_OWNER + """
                  ) AS entries,
                  (SELECT COUNT(*) FROM HORSE_WITHDRAWAL WHERE horse_id = :id AND status = 'PENDING') AS withdrawals
                """).param("id", horseId).param("today", LocalDate.now())
                .query((rs, n) -> new PendingOnOwner(rs.getInt("plans"), rs.getInt("entries"), rs.getInt("withdrawals")))
                .single();
    }

    /** Tài khoản là HORSE_OWNER và đang ACTIVE. */
    public boolean isActiveHorseOwner(int userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
                WHERE u.user_id = :id AND r.role_name = 'HORSE_OWNER' AND u.status = 'ACTIVE'
                """).param("id", userId).query(Integer.class).single() > 0;
    }

    /** Chuyển chủ (Club Manager). */
    public int updateOwner(int horseId, int ownerId) {
        // V61: the contract does NOT move with the horse. The seller's contract is ended and
        // the buyer signs their own (ContractService.handOver), in the same transaction.
        return jdbc.sql("UPDATE HORSE SET owner_id = :o, updated_at = SYSUTCDATETIME() WHERE horse_id = :id")
                .param("o", ownerId).param("id", horseId).update();
    }

    /** Nhận lại ngựa đã rút: về ACTIVE, xếp chuồng. Điều kiện WITHDRAWN trong câu UPDATE. */
    public int readmit(int horseId, String stallNo) {
        return jdbc.sql("""
                UPDATE HORSE SET activity_status = 'ACTIVE', withdrawn_at = NULL, stall_no = :stall,
                       updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id AND activity_status = 'WITHDRAWN'
                """).param("stall", stallNo).param("id", horseId).update();
    }

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

    /** V60: a stall the stable plan does not list (a typo, or a barn the club does not have). */
    public boolean isStallOffPlan(String stallNo) {
        return jdbc.sql("SELECT COUNT(*) FROM STALL WHERE stall_no = :v")
                .param("v", stallNo)
                .query(Integer.class).single() == 0;
    }

    /** V60: stalls on the plan that no horse is in, barn by barn, in number order. */
    public List<String> findFreeStalls() {
        return jdbc.sql("""
                SELECT s.stall_no FROM STALL s
                WHERE NOT EXISTS (SELECT 1 FROM HORSE h WHERE h.stall_no = s.stall_no)
                ORDER BY s.barn, s.stall_number, s.stall_no
                """)
                .query(String.class)
                .list();
    }

    public Optional<String> findNameByStallNo(String stallNo) {
        return jdbc.sql("SELECT horse_name FROM HORSE WHERE stall_no = :v")
                .param("v", stallNo)
                .query(String.class)
                .optional();
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

    /** V41: the club manager corrects the parents on record (HorseService.correctParents). */
    public int updateParents(int horseId, Integer sireId, Integer damId) {
        return jdbc.sql("""
                UPDATE HORSE SET sire_id = :sireId, dam_id = :damId, updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id
                """)
                .param("id", horseId)
                .param("sireId", sireId)
                .param("damId", damId)
                .update();
    }

    /** Mã đăng ký và số chuồng, chỉ Club Manager đổi (HorseService.updateIdentity). */
    public int updateIdentity(int horseId, String registrationCode, String stallNo) {
        return jdbc.sql("""
                UPDATE HORSE SET registration_code = :code, stall_no = :stall, updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id
                """)
                .param("code", registrationCode)
                .param("stall", stallNo)
                .param("id", horseId)
                .update();
    }

    /** V37: Club Manager gán hoặc gỡ Head Trainer phụ trách ngựa. */
    public int updateTrainer(int horseId, Integer trainerId) {
        return jdbc.sql("""
                UPDATE HORSE SET trainer_id = :trainerId, updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id
                """)
                .param("trainerId", trainerId)
                .param("id", horseId)
                .update();
    }

    /** V37: Danh sách Head Trainer đang ACTIVE cùng số ngựa đang quản lý và hạn mức. */
    public List<TrainerSummary> listActiveTrainers() {
        return jdbc.sql("""
                SELECT u.user_id, u.username, u.full_name,
                       (SELECT COUNT(*) FROM HORSE h
                        WHERE h.trainer_id = u.user_id AND h.activity_status = 'ACTIVE') AS managed_horse_count,
                       ISNULL(u.max_horses, 10) AS max_horses
                FROM APP_USER u
                JOIN ROLE r ON r.role_id = u.role_id
                WHERE r.role_name = 'HEAD_TRAINER' AND u.status = 'ACTIVE'
                ORDER BY u.full_name
                """)
                .query((rs, n) -> new TrainerSummary(
                        rs.getInt("user_id"),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getInt("managed_horse_count"),
                        rs.getInt("max_horses")))
                .list();
    }

    /** V37: Tìm một Head Trainer đang ACTIVE theo id. */
    public Optional<TrainerSummary> findActiveTrainer(int trainerId) {
        return jdbc.sql("""
                SELECT u.user_id, u.username, u.full_name,
                       (SELECT COUNT(*) FROM HORSE h
                        WHERE h.trainer_id = u.user_id AND h.activity_status = 'ACTIVE') AS managed_horse_count,
                       ISNULL(u.max_horses, 10) AS max_horses
                FROM APP_USER u
                JOIN ROLE r ON r.role_id = u.role_id
                WHERE u.user_id = :id AND r.role_name = 'HEAD_TRAINER' AND u.status = 'ACTIVE'
                """)
                .param("id", trainerId)
                .query((rs, n) -> new TrainerSummary(
                        rs.getInt("user_id"),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getInt("managed_horse_count"),
                        rs.getInt("max_horses")))
                .optional();
    }

    /** V37: Đếm số ngựa đang ACTIVE thuộc quyền của trainerId (trừ excludeHorseId). */
    public int countManagedHorses(int trainerId, int excludeHorseId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM HORSE
                WHERE trainer_id = :trainerId
                  AND activity_status = 'ACTIVE'
                  AND horse_id <> :excludeId
                """)
                .param("trainerId", trainerId)
                .param("excludeId", excludeHorseId)
                .query(Integer.class)
                .single();
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
     */
    public int markWithdrawn(int horseId) {
        int changed = jdbc.sql("""
                UPDATE HORSE
                SET activity_status = 'WITHDRAWN',
                    withdrawn_at = SYSUTCDATETIME(),
                    stall_no = NULL,
                    trainer_id = NULL,
                    updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id AND activity_status = 'ACTIVE'
                """)
                .param("id", horseId)
                .update();
        if (changed > 0) {
            // V49: the horse left, so its stay is over, whether or not the term had run out.
            jdbc.sql("""
                    UPDATE HORSE_CONTRACT
                    SET status = 'ENDED', closed_note = N'Horse withdrawn from the club',
                        updated_at = SYSUTCDATETIME()
                    WHERE horse_id = :id AND status IN ('ACTIVE', 'EXPIRED')
                    """).param("id", horseId).update();
            // V61/V62: nothing still waiting may start later for a horse that left: a change-of-owner
            // contract nobody signed, or a renewal not signed or not paid yet.
            jdbc.sql("""
                    UPDATE HORSE_CONTRACT
                    SET status = 'VOID', closed_note = N'Horse withdrawn from the club',
                        updated_at = SYSUTCDATETIME()
                    WHERE horse_id = :id AND status IN ('SENT', 'SIGNED')
                      AND (takes_over_contract_id IS NOT NULL OR renews_contract_id IS NOT NULL)
                    """).param("id", horseId).update();
        }
        return changed;
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

    /*
     * CTE đệ quy (WITH tree AS (... UNION ALL ...)) của SQL Server: bắt đầu từ cha mẹ của horseId,
     * mỗi vòng đi lên một đời. Nếu candidateId xuất hiện trong cây tổ tiên của horseId thì gán
     * candidateId làm cha/mẹ sẽ tạo vòng lặp. depth < 50 là chốt an toàn chống lặp vô hạn.
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

    // Cùng kỹ thuật CTE đệ quy, dừng ở maxDepth đời; DISTINCT vì một tổ tiên có thể xuất hiện ở
    // nhiều nhánh (vd. Sunday Silence vừa là ông nội vừa là ông ngoại).
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

    /* ------------------------------------------------------------------
     * V48/V49: registration code suggestion, intake measures, contract gate
     * ------------------------------------------------------------------ */

    /** Largest number used after "PREFIX-" in a PREFIX-NNNN code, 0 when none. */
    public int maxCodeNumber(String prefix) {
        // MAX over no rows is one NULL row, so read it as a list and look at the value.
        List<Integer> max = jdbc.sql("""
                SELECT MAX(TRY_CAST(SUBSTRING(registration_code, LEN(:prefix) + 2, 10) AS INT))
                FROM HORSE
                WHERE registration_code LIKE :prefix + '-[0-9][0-9][0-9][0-9]%'
                """)
                .param("prefix", prefix)
                .query(Integer.class)
                .list();
        return max.isEmpty() || max.get(0) == null ? 0 : max.get(0);
    }

    /** Who already holds this code (for "Already used by ..."), empty when it is free. */
    public Optional<String> findNameByRegistrationCode(String code) {
        return jdbc.sql("SELECT horse_name FROM HORSE WHERE registration_code = :code")
                .param("code", code)
                .query(String.class)
                .optional();
    }

    /** What the club measured when the horse arrived, plus the owner's aptitude guess. */
    public int updateIntake(int horseId, java.math.BigDecimal heightCm, Integer bcs,
                            String surface, String distancePref, String runningStyle) {
        return jdbc.sql("""
                UPDATE HORSE SET height_cm = :height, bcs = :bcs, surface = :surface,
                       distance_pref = :distance, running_style = :style, updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id
                """)
                .param("height", heightCm)
                .param("bcs", bcs)
                .param("surface", surface)
                .param("distance", distancePref)
                .param("style", runningStyle)
                .param("id", horseId)
                .update();
    }

    public record Intake(java.math.BigDecimal heightCm, Integer bcs,
                         String surface, String distancePref, String runningStyle) {}

    public Optional<Intake> findIntake(int horseId) {
        return jdbc.sql("""
                SELECT height_cm, bcs, surface, distance_pref, running_style FROM HORSE WHERE horse_id = :id
                """)
                .param("id", horseId)
                .query((rs, n) -> new Intake(rs.getBigDecimal("height_cm"),
                        rs.getObject("bcs", Integer.class), rs.getString("surface"),
                        rs.getString("distance_pref"), rs.getString("running_style")))
                .optional();
    }

    /**
     * V49: the stay ran out and was not renewed. Horses registered before
     * contracts existed have no row at all and are never blocked.
     */
    /** V61: sold, and the new owner has not signed the change-of-owner contract yet. */
    public boolean isWaitingForTakeover(int horseId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM HORSE_CONTRACT
                WHERE horse_id = :id AND status = 'SENT' AND takes_over_contract_id IS NOT NULL
                """)
                .param("id", horseId)
                .query(Integer.class)
                .single() > 0;
    }

    public boolean hasExpiredContract(int horseId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM HORSE_CONTRACT WHERE horse_id = :id AND status = 'EXPIRED'
                """)
                .param("id", horseId)
                .query(Integer.class)
                .single() > 0;
    }
}

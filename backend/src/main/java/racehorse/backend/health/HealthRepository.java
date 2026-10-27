package racehorse.backend.health;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HealthRepository {

    private final JdbcClient jdbc;

    public HealthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Đếm theo trạng thái trên TOÀN đàn (không bị ảnh hưởng bởi bộ lọc).
     *
     * "Đàn" ở đây chỉ gồm ngựa ACADEMY còn ở lại câu lạc bộ. Ngựa đã rút (V15) không tính.
     * Hồ sơ PEDIGREE là bản ghi tổ tiên để làm cha mẹ trong phả hệ (V6): không chuồng,
     * không bao giờ ra sân, nên đếm chúng vào đây làm bảng sức khoẻ báo thừa hàng chục
     * con "ELIGIBLE" mà bác sĩ không bao giờ khám được.
     */
    public Map<String, Integer> countByStatus() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String s : List.of("ELIGIBLE", "MONITOR", "INJURED", "QUARANTINE")) result.put(s, 0);
        jdbc.sql("SELECT status, COUNT(*) AS cnt FROM HORSE "
                        + "WHERE activity_status = 'ACTIVE' AND record_type = 'ACADEMY' GROUP BY status")
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getInt("cnt")))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /**
     * OUTER APPLY lấy ĐÚNG 1 lần khám gần nhất của mỗi con ngựa (nếu chưa khám lần nào thì cột để NULL).
     * LEFT JOIN TRAINING_LOCK chỉ lấy lệnh khoá đang ACTIVE (schema đảm bảo tối đa 1 lệnh/ngựa).
     * Ngựa đã rút (activity_status = WITHDRAWN, V15) không còn trên bảng sức khoẻ.
     * Hồ sơ PEDIGREE (V6) cũng không lên bảng: chỉ ngựa ACADEMY mới có chuồng và được khám.
     */
    public List<HorseHealthItem> findHorses(String statusFilter) {
        StringBuilder sql = new StringBuilder("""
                SELECT h.horse_id, h.horse_name, h.registration_code, h.stall_no, h.status,
                       o.full_name AS owner_name, m.exam_date, m.diagnosis, l.lock_level
                FROM HORSE h
                JOIN APP_USER o ON o.user_id = h.owner_id
                OUTER APPLY (
                    SELECT TOP 1 exam_date, diagnosis FROM MEDICAL_RECORD
                    WHERE horse_id = h.horse_id AND record_type = 'EXAM'
                    ORDER BY exam_date DESC
                ) m
                LEFT JOIN TRAINING_LOCK l ON l.horse_id = h.horse_id AND l.status = 'ACTIVE'
                WHERE h.activity_status = 'ACTIVE' AND h.record_type = 'ACADEMY'
                """);
        Map<String, Object> params = new HashMap<>();
        if (statusFilter != null) {
            sql.append(" AND h.status = :status");
            params.put("status", statusFilter);
        }
        // Ngựa chưa xếp chuồng (stall_no NULL) dồn xuống cuối.
        sql.append(" ORDER BY CASE WHEN h.stall_no IS NULL THEN 1 ELSE 0 END, h.stall_no, h.horse_name");

        return jdbc.sql(sql.toString()).params(params).query((rs, i) -> {
            String lockLevel = rs.getString("lock_level");
            return new HorseHealthItem(
                    rs.getInt("horse_id"), rs.getString("horse_name"), rs.getString("registration_code"),
                    rs.getString("stall_no"), rs.getString("status"), rs.getString("owner_name"),
                    rs.getObject("exam_date", LocalDateTime.class), rs.getString("diagnosis"),
                    lockLevel != null, lockLevel);
        }).list();
    }
}

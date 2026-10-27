package racehorse.backend.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Chỉ nói chuyện với DB, không chứa logic nghiệp vụ. */
@Repository
public class IncidentRepository {

    /** Những gì cần biết về một buổi tập để quyết định có dừng được không. */
    public record SessionForStop(int sessionId, int horseId, String horseName, int ownerId,
                                 int assignedTo, String status) {
    }

    /** Một báo cáo chưa khám xong (PENDING hoặc RECEIVED). */
    public record OpenReport(int reportId, String urgency, String status) {
    }

    public record RunningSession(int sessionId, int assignedTo) {
    }

    private static final String SELECT_REPORT = """
            SELECT r.report_id, r.horse_id, h.horse_name, h.stall_no, h.status AS horse_status, h.owner_id,
                   r.session_id, r.reporter_id, u.full_name AS reporter_name,
                   r.symptoms, r.description, r.photo_url, r.urgency, r.reported_at, r.status,
                   r.received_by, rv.full_name AS receiver_name, r.received_at
            FROM INCIDENT_REPORT r
            JOIN HORSE h     ON h.horse_id = r.horse_id
            JOIN APP_USER u  ON u.user_id  = r.reporter_id
            LEFT JOIN APP_USER rv ON rv.user_id = r.received_by
            """;

    private final JdbcClient jdbc;

    public IncidentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static IncidentReportResponse map(ResultSet rs, int rowNum) throws SQLException {
        // reported_at / received_at lưu giờ UTC (SYSUTCDATETIME) nên gắn offset UTC để frontend hiểu đúng múi giờ.
        LocalDateTime reported = rs.getObject("reported_at", LocalDateTime.class);
        LocalDateTime received = rs.getObject("received_at", LocalDateTime.class);
        return new IncidentReportResponse(
                rs.getInt("report_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getString("stall_no"), rs.getString("horse_status"), rs.getInt("owner_id"),
                rs.getObject("session_id", Integer.class),
                rs.getInt("reporter_id"), rs.getString("reporter_name"),
                rs.getString("symptoms"), rs.getString("description"), rs.getString("photo_url"),
                rs.getString("urgency"),
                reported == null ? null : reported.toInstant(ZoneOffset.UTC),
                rs.getString("status"),
                rs.getObject("received_by", Integer.class), rs.getString("receiver_name"),
                received == null ? null : received.toInstant(ZoneOffset.UTC));
    }

    public Optional<SessionForStop> findSessionForStop(int sessionId) {
        return jdbc.sql("""
                SELECT s.session_id, s.horse_id, h.horse_name, h.owner_id, s.assigned_to, s.status
                FROM TRAINING_SESSION s JOIN HORSE h ON h.horse_id = s.horse_id
                WHERE s.session_id = :id
                """)
                .param("id", sessionId)
                .query((rs, n) -> new SessionForStop(rs.getInt("session_id"), rs.getInt("horse_id"),
                        rs.getString("horse_name"), rs.getInt("owner_id"), rs.getInt("assigned_to"),
                        rs.getString("status")))
                .optional();
    }

    /**
     * RUNNING -> STOPPED_EARLY. Điều kiện status nằm trong chính câu UPDATE: Groom và Head Trainer
     * cùng bấm dừng một lúc thì chỉ một người nhận 1 dòng, người còn lại nhận 0 và biết mình đến muộn.
     */
    public int markStopped(int sessionId) {
        return jdbc.sql("UPDATE TRAINING_SESSION SET status = 'STOPPED_EARLY' WHERE session_id = :id AND status = 'RUNNING'")
                .param("id", sessionId)
                .update();
    }

    public int insertReport(int horseId, int reporterId, int sessionId,
                            String symptoms, String description, String urgency) {
        return jdbc.sql("""
                INSERT INTO INCIDENT_REPORT (horse_id, reporter_id, session_id, symptoms, description, urgency)
                OUTPUT INSERTED.report_id
                VALUES (:horseId, :reporterId, :sessionId, :symptoms, :description, :urgency)
                """)
                .param("horseId", horseId)
                .param("reporterId", reporterId)
                .param("sessionId", sessionId)
                .param("symptoms", symptoms)
                .param("description", description)
                .param("urgency", urgency)
                .query(Integer.class)
                .single();
    }

    /**
     * Báo cáo không gắn buổi tập: Groom thấy ngựa bất thường tại chuồng (bỏ ăn,
     * đau bụng, sốt, móng xước...). session_id NULL nên không đụng UX_INC_session.
     */
    public int insertStandaloneReport(int horseId, int reporterId, String symptoms,
                                      String description, String urgency, String photoUrl) {
        return jdbc.sql("""
                INSERT INTO INCIDENT_REPORT (horse_id, reporter_id, symptoms, description, urgency, photo_url)
                OUTPUT INSERTED.report_id
                VALUES (:horseId, :reporterId, :symptoms, :description, :urgency, :photo)
                """)
                .param("horseId", horseId)
                .param("reporterId", reporterId)
                .param("symptoms", symptoms)
                .param("description", description)
                .param("urgency", urgency)
                .param("photo", photoUrl)
                .query(Integer.class)
                .single();
    }

    public Optional<IncidentReportResponse> findById(int reportId) {
        return jdbc.sql(SELECT_REPORT + " WHERE r.report_id = :id")
                .param("id", reportId)
                .query(IncidentRepository::map)
                .optional();
    }

    /**
     * statuses rỗng = mọi trạng thái. reporterId / ownerId lọc cho Groom (chỉ thấy báo cáo của mình)
     * và chủ ngựa (chỉ thấy ngựa mình): lọc trong SQL, không lọc ở Java.
     * Hàng chờ (urgentFirst) xếp khẩn cấp nhất lên đầu, trong cùng mức thì ca chờ lâu nhất lên trước.
     * Giá trị status chỉ lấy từ danh sách cố định ở service nên đưa vào tham số, không nối chuỗi.
     */
    public List<IncidentReportResponse> search(List<String> statuses, Integer horseId, Integer reporterId,
                                               Integer ownerId, boolean urgentFirst, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_REPORT).append(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (!statuses.isEmpty()) {
            sql.append(" AND r.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                sql.append(i == 0 ? ":s" : ", :s").append(i);
                params.put("s" + i, statuses.get(i));
            }
            sql.append(")");
        }
        if (horseId != null) {
            sql.append(" AND r.horse_id = :horseId");
            params.put("horseId", horseId);
        }
        if (reporterId != null) {
            sql.append(" AND r.reporter_id = :reporterId");
            params.put("reporterId", reporterId);
        }
        if (ownerId != null) {
            sql.append(" AND h.owner_id = :ownerId");
            params.put("ownerId", ownerId);
        }
        sql.append(urgentFirst
                ? " ORDER BY CASE r.urgency WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, r.reported_at ASC"
                : " ORDER BY r.reported_at DESC");
        // limit là int đã bị kẹp trong service nên nối thẳng vào chuỗi là an toàn.
        sql.append(" OFFSET 0 ROWS FETCH NEXT ").append(limit).append(" ROWS ONLY");
        return jdbc.sql(sql.toString()).params(params).query(IncidentRepository::map).list();
    }

    /** Báo cáo chưa khám xong của một con ngựa (nếu có nhiều thì lấy ca khẩn cấp nhất). */
    public Optional<OpenReport> findOpenReport(int horseId) {
        return jdbc.sql("""
                SELECT TOP 1 report_id, urgency, status FROM INCIDENT_REPORT
                WHERE horse_id = :h AND status <> 'EXAMINED'
                ORDER BY CASE urgency WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, report_id
                """)
                .param("h", horseId)
                .query((rs, n) -> new OpenReport(rs.getInt("report_id"), rs.getString("urgency"),
                        rs.getString("status")))
                .optional();
    }

    public List<RunningSession> findRunningSessions(int horseId) {
        return jdbc.sql("SELECT session_id, assigned_to FROM TRAINING_SESSION WHERE horse_id = :h AND status = 'RUNNING'")
                .param("h", horseId)
                .query((rs, n) -> new RunningSession(rs.getInt("session_id"), rs.getInt("assigned_to")))
                .list();
    }

    /** PENDING -> RECEIVED. Hai bác sĩ cùng bấm nhận thì chỉ một người thắng (người kia nhận 0 dòng). */
    public int markReceived(int reportId, int vetId) {
        return jdbc.sql("""
                UPDATE INCIDENT_REPORT
                SET status = 'RECEIVED', received_by = :vet, received_at = SYSUTCDATETIME()
                WHERE report_id = :id AND status = 'PENDING'
                """)
                .param("vet", vetId)
                .param("id", reportId)
                .update();
    }
}

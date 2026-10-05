package racehorse.backend.horse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HorseVitalsRepository {

    private final JdbcClient jdbc;

    public HorseVitalsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<HorseVitalsResponse.ActiveLock> findActiveLock(int horseId) {
        return jdbc.sql("""
                SELECT lock_level, reason, issued_at FROM TRAINING_LOCK
                WHERE horse_id = :id AND status = 'ACTIVE'
                """)
                .param("id", horseId)
                .query((rs, n) -> new HorseVitalsResponse.ActiveLock(
                        rs.getString("lock_level"), rs.getString("reason"),
                        rs.getObject("issued_at", LocalDateTime.class)))
                .optional();
    }

    public Optional<HorseVitalsResponse.LatestSession> findLatestSession(int horseId) {
        return jdbc.sql("""
                SELECT TOP 1 s.session_date, s.status, s.avg_heart_rate, s.peak_heart_rate,
                       s.recovery_heart_rate, s.peak_velocity, s.evaluation, ph.heart_rate_ceiling
                FROM TRAINING_SESSION s JOIN PLAN_PHASE ph ON ph.phase_id = s.phase_id
                WHERE s.horse_id = :id
                ORDER BY s.session_date DESC
                """)
                .param("id", horseId)
                .query((rs, n) -> new HorseVitalsResponse.LatestSession(
                        rs.getObject("session_date", LocalDateTime.class), rs.getString("status"),
                        rs.getObject("avg_heart_rate", Integer.class),
                        rs.getObject("peak_heart_rate", Integer.class),
                        rs.getObject("recovery_heart_rate", Integer.class),
                        rs.getObject("peak_velocity", BigDecimal.class),
                        rs.getString("evaluation"),
                        rs.getObject("heart_rate_ceiling", Integer.class)))
                .optional();
    }

    /** "Chưa xử lý" = chưa có resolved_by. */
    public List<HorseVitalsResponse.OpenAlert> findOpenAlerts(int horseId) {
        return jdbc.sql("""
                SELECT a.alert_id, a.metric_type, a.threshold_value, a.actual_value,
                       a.severity, a.triggered_at
                FROM THRESHOLD_ALERT a JOIN TRAINING_SESSION s ON s.session_id = a.session_id
                WHERE s.horse_id = :id AND a.resolved_by IS NULL
                ORDER BY a.triggered_at DESC
                """)
                .param("id", horseId)
                .query((rs, n) -> new HorseVitalsResponse.OpenAlert(
                        rs.getInt("alert_id"), rs.getString("metric_type"),
                        rs.getBigDecimal("threshold_value"), rs.getBigDecimal("actual_value"),
                        rs.getString("severity"),
                        rs.getObject("triggered_at", LocalDateTime.class)))
                .list();
    }

    public List<HorseVitalsResponse.OpenInjury> findOpenInjuries(int horseId) {
        return jdbc.sql("""
                SELECT i.body_location, i.severity, i.status, i.marked_at
                FROM INJURY i JOIN MEDICAL_RECORD m ON m.record_id = i.record_id
                WHERE m.horse_id = :id AND i.status <> 'RECOVERED'
                ORDER BY i.marked_at DESC
                """)
                .param("id", horseId)
                .query((rs, n) -> new HorseVitalsResponse.OpenInjury(
                        rs.getString("body_location"), rs.getString("severity"),
                        rs.getString("status"),
                        rs.getObject("marked_at", LocalDateTime.class)))
                .list();
    }
}
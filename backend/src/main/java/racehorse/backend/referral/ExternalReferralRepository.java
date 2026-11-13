package racehorse.backend.referral;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExternalReferralRepository {

    private static final String SELECT_REFERRAL = """
            SELECT r.referral_id, r.horse_id, h.horse_name, h.stall_no, h.status AS horse_status,
                   h.owner_id, ow.full_name AS owner_name,
                   r.report_id, r.medical_record_id,
                   r.referred_by, ru.full_name AS referred_by_name, r.referred_at,
                   r.hospital_name, r.referral_reason, r.urgency, r.expected_return,
                   r.status, r.discharge_notes, r.discharged_at,
                   r.discharged_by, du.full_name AS discharged_by_name
            FROM EXTERNAL_REFERRAL r
            JOIN HORSE h         ON h.horse_id = r.horse_id
            JOIN APP_USER ow     ON ow.user_id = h.owner_id
            JOIN APP_USER ru     ON ru.user_id = r.referred_by
            LEFT JOIN APP_USER du ON du.user_id = r.discharged_by
            """;

    private final JdbcClient jdbc;

    public ExternalReferralRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static ExternalReferralResponse map(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime refAt = rs.getObject("referred_at", LocalDateTime.class);
        LocalDateTime disAt = rs.getObject("discharged_at", LocalDateTime.class);
        java.sql.Date expRet = rs.getDate("expected_return");
        return new ExternalReferralResponse(
                rs.getInt("referral_id"),
                rs.getInt("horse_id"),
                rs.getString("horse_name"),
                rs.getString("stall_no"),
                rs.getString("horse_status"),
                rs.getInt("owner_id"),
                rs.getString("owner_name"),
                rs.getObject("report_id", Integer.class),
                rs.getObject("medical_record_id", Integer.class),
                rs.getInt("referred_by"),
                rs.getString("referred_by_name"),
                refAt == null ? null : refAt.toInstant(ZoneOffset.UTC),
                rs.getString("hospital_name"),
                rs.getString("referral_reason"),
                rs.getString("urgency"),
                expRet == null ? null : expRet.toLocalDate(),
                rs.getString("status"),
                rs.getString("discharge_notes"),
                disAt == null ? null : disAt.toInstant(ZoneOffset.UTC),
                rs.getObject("discharged_by", Integer.class),
                rs.getString("discharged_by_name")
        );
    }

    public int insert(ExternalReferralRequest req, int referredBy) {
        return jdbc.sql("""
                INSERT INTO EXTERNAL_REFERRAL (
                    horse_id, report_id, referred_by, hospital_name,
                    referral_reason, urgency, expected_return
                )
                OUTPUT INSERTED.referral_id
                VALUES (
                    :horseId, :reportId, :referredBy, :hospitalName,
                    :referralReason, :urgency, :expectedReturn
                )
                """)
                .param("horseId", req.horseId())
                .param("reportId", req.reportId())
                .param("referredBy", referredBy)
                .param("hospitalName", req.hospitalName().trim())
                .param("referralReason", req.referralReason().trim())
                .param("urgency", req.urgency())
                .param("expectedReturn", req.expectedReturn())
                .query(Integer.class)
                .single();
    }

    public Optional<ExternalReferralResponse> findById(int id) {
        return jdbc.sql(SELECT_REFERRAL + " WHERE r.referral_id = :id")
                .param("id", id)
                .query(ExternalReferralRepository::map)
                .optional();
    }

    public Optional<ExternalReferralResponse> findActiveByHorse(int horseId) {
        return jdbc.sql(SELECT_REFERRAL + " WHERE r.horse_id = :id AND r.status = 'HOSPITALIZED'")
                .param("id", horseId)
                .query(ExternalReferralRepository::map)
                .optional();
    }

    public List<ExternalReferralResponse> search(Integer horseId, String status, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_REFERRAL).append(" WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        if (horseId != null) {
            sql.append(" AND r.horse_id = :horseId");
            params.put("horseId", horseId);
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append(" AND r.status = :status");
            params.put("status", status.toUpperCase());
        }
        sql.append(" ORDER BY CASE r.status WHEN 'HOSPITALIZED' THEN 0 ELSE 1 END, r.referred_at DESC");
        sql.append(" OFFSET 0 ROWS FETCH NEXT ").append(Math.max(1, Math.min(limit, 200))).append(" ROWS ONLY");
        return jdbc.sql(sql.toString()).params(params).query(ExternalReferralRepository::map).list();
    }

    public int markDischarged(int referralId, int dischargeVetId, String dischargeNotes) {
        return jdbc.sql("""
                UPDATE EXTERNAL_REFERRAL
                SET status = 'DISCHARGED',
                    discharged_at = SYSUTCDATETIME(),
                    discharged_by = :vetId,
                    discharge_notes = :notes
                WHERE referral_id = :id AND status = 'HOSPITALIZED'
                """)
                .param("vetId", dischargeVetId)
                .param("notes", dischargeNotes.trim())
                .param("id", referralId)
                .update();
    }
}

package racehorse.backend.race;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** RACE_REGISTRATION and RACE (V22), không chứa luật nghiệp vụ. */
@Repository
public class RaceRepository {

    private final JdbcClient jdbc;

    public RaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT r.registration_id, r.horse_id, h.horse_name, h.stall_no, h.owner_id,
                   o.full_name AS owner_name, r.race_name, r.race_date, r.registered_by,
                   u.full_name AS registered_by_name, r.result, r.prize_money, r.note,
                   r.race_id, rc.distance_m, rc.venue, rc.status AS race_status,
                   r.finish_position, r.finish_time_ms, r.scratch_reason
            FROM RACE_REGISTRATION r
            JOIN RACE rc    ON rc.race_id = r.race_id
            JOIN HORSE h    ON h.horse_id = r.horse_id
            JOIN APP_USER o ON o.user_id  = h.owner_id
            JOIN APP_USER u ON u.user_id  = r.registered_by
            """;

    private static RaceModels.Entry map(ResultSet rs, int n) throws SQLException {
        LocalDate date = rs.getObject("race_date", LocalDate.class);
        String result = rs.getString("result");
        return new RaceModels.Entry(
                rs.getInt("registration_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getString("stall_no"), rs.getInt("owner_id"), rs.getString("owner_name"),
                rs.getString("race_name"), date, rs.getInt("registered_by"),
                rs.getString("registered_by_name"), result, rs.getBigDecimal("prize_money"),
                rs.getString("note"), stateOf(result, date),
                rs.getInt("race_id"), rs.getObject("distance_m", Integer.class), rs.getString("venue"),
                rs.getString("race_status"), rs.getObject("finish_position", Integer.class),
                rs.getObject("finish_time_ms", Integer.class), rs.getString("scratch_reason"));
    }

    static String stateOf(String result, LocalDate raceDate) {
        if ("SCRATCHED".equals(result)) return "SCRATCHED";
        if (result != null) return "FINISHED";
        return raceDate.isAfter(LocalDate.now()) ? "UPCOMING" : "AWAITING_RESULT";
    }

    /** year null = mọi năm; ownerId khác null = chỉ ngựa của chủ đó. */
    public List<RaceModels.Entry> find(Integer year, Integer horseId, Integer ownerId) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (year != null) {
            sql.append(" AND YEAR(r.race_date) = :year");
            p.put("year", year);
        }
        if (horseId != null) {
            sql.append(" AND r.horse_id = :horseId");
            p.put("horseId", horseId);
        }
        if (ownerId != null) {
            sql.append(" AND h.owner_id = :ownerId");
            p.put("ownerId", ownerId);
        }
        sql.append(" ORDER BY r.race_date DESC, r.race_name, h.horse_name");
        return jdbc.sql(sql.toString()).params(p).query(RaceRepository::map).list();
    }

    public Optional<RaceModels.Entry> findById(int id) {
        return jdbc.sql(SELECT + " WHERE r.registration_id = :id").param("id", id)
                .query(RaceRepository::map).optional();
    }

    /** The existing entry for (horse, race, date), if any, with its result. */
    public record ExistingEntry(int id, String result) {
        public boolean scratched() { return "SCRATCHED".equals(result); }
    }

    /**
     * The one row UQ_REG_horse_race allows for (horse, race).
     *
     * A SCRATCHED row is returned too: the service reinstates it instead of
     * inserting, because the UNIQUE constraint would turn a second INSERT into a
     * 500 (see reinstate).
     */
    public Optional<ExistingEntry> findEntry(int horseId, int raceId) {
        return jdbc.sql("""
                SELECT registration_id, result FROM RACE_REGISTRATION
                WHERE horse_id = :h AND race_id = :r
                """).param("h", horseId).param("r", raceId)
                .query((rs, n) -> new ExistingEntry(rs.getInt("registration_id"), rs.getString("result")))
                .optional();
    }

    /**
     * Bring a SCRATCHED entry back to an open entry.
     *
     * Reusing the row rather than relaxing UQ_REG_entry into a filtered index
     * means no migration. A (horse, race, date) entry is one thing whose state
     * changes; the scratch and the reinstatement both stay in the audit log.
     *
     * `AND result = 'SCRATCHED'` is in the UPDATE itself, so two people
     * re-entering the same horse at once cannot both succeed.
     */
    public int reinstate(int id, int by, String note) {
        return jdbc.sql("""
                UPDATE RACE_REGISTRATION
                SET result = NULL, prize_money = NULL, registered_by = :by, note = :note,
                    scratch_reason = NULL, finish_position = NULL, finish_time_ms = NULL
                WHERE registration_id = :id AND result = 'SCRATCHED'
                """).param("id", id).param("by", by).param("note", note).update();
    }

    /** Ngựa đã có giải khác (chưa rút) trong cùng ngày: một con không chạy hai giải một ngày. */
    public Optional<String> otherRaceOn(int horseId, LocalDate date) {
        return jdbc.sql("""
                SELECT TOP 1 race_name FROM RACE_REGISTRATION
                WHERE horse_id = :h AND race_date = :d AND (result IS NULL OR result <> 'SCRATCHED')
                """).param("h", horseId).param("d", date).query(String.class).optional();
    }

    /** race_name and race_date are copies of the race's (V22); they never change after this. */
    public int insert(int horseId, RaceModels.RaceEvent race, int by, String note) {
        return jdbc.sql("""
                INSERT INTO RACE_REGISTRATION (horse_id, race_id, race_name, race_date, registered_by, note)
                OUTPUT INSERTED.registration_id
                VALUES (:h, :race, :n, :d, :by, :note)
                """).param("h", horseId).param("race", race.raceId()).param("n", race.raceName())
                .param("d", race.raceDate()).param("by", by).param("note", note)
                .query(Integer.class).single();
    }

    /** Ghi kết quả; điều kiện result IS NULL OR <> SCRATCHED để không ghi đè giải đã rút. */
    public int saveResult(int id, String result, BigDecimal prize, String note,
                          Integer position, Integer timeMs) {
        return jdbc.sql("""
                UPDATE RACE_REGISTRATION SET result = :res, prize_money = :prize, note = :note,
                       finish_position = :pos, finish_time_ms = :time
                WHERE registration_id = :id AND (result IS NULL OR result <> 'SCRATCHED')
                """).param("id", id).param("res", result).param("prize", prize).param("note", note)
                .param("pos", position).param("time", timeMs)
                .update();
    }

    /** V23: the reason goes to its own column; the note is left as it was. */
    public int scratch(int id, String reason) {
        return jdbc.sql("""
                UPDATE RACE_REGISTRATION SET result = 'SCRATCHED', prize_money = NULL, scratch_reason = :reason
                WHERE registration_id = :id AND result IS NULL
                """).param("id", id).param("reason", reason).update();
    }

    /** Entries (not scratched) within gapDays of raceDate, other than this race (#3). */
    public Optional<RaceModels.Entry> nearbyEntry(int horseId, LocalDate raceDate, int gapDays, Integer exceptRaceId) {
        StringBuilder sql = new StringBuilder(SELECT).append("""
                 WHERE r.horse_id = :h AND (r.result IS NULL OR r.result <> 'SCRATCHED')
                   AND ABS(DATEDIFF(DAY, r.race_date, :d)) < :gap
                """);
        Map<String, Object> p = new HashMap<>();
        p.put("h", horseId);
        p.put("d", raceDate);
        p.put("gap", gapDays);
        if (exceptRaceId != null) {
            sql.append(" AND r.race_id <> :except");
            p.put("except", exceptRaceId);
        }
        sql.append(" ORDER BY ABS(DATEDIFF(DAY, r.race_date, :d))");
        return jdbc.sql(sql.toString()).params(p).query(RaceRepository::map).list().stream().findFirst();
    }

    /* ---------------- RACE (V22) ---------------- */

    private static final String SELECT_RACE = """
            SELECT rc.race_id, rc.race_name, rc.race_date, rc.distance_m, rc.venue, rc.entry_deadline,
                   rc.entry_fee, rc.max_entries, rc.status, rc.cancel_reason,
                   (SELECT COUNT(*) FROM RACE_REGISTRATION x
                    WHERE x.race_id = rc.race_id AND (x.result IS NULL OR x.result <> 'SCRATCHED')) AS entries
            FROM RACE rc
            """;

    private static RaceModels.RaceEvent mapRace(ResultSet rs, int n) throws SQLException {
        return new RaceModels.RaceEvent(
                rs.getInt("race_id"), rs.getString("race_name"), rs.getObject("race_date", LocalDate.class),
                rs.getObject("distance_m", Integer.class), rs.getString("venue"),
                rs.getObject("entry_deadline", LocalDate.class), rs.getBigDecimal("entry_fee"),
                rs.getObject("max_entries", Integer.class), rs.getString("status"),
                rs.getString("cancel_reason"), rs.getInt("entries"));
    }

    public Optional<RaceModels.RaceEvent> findRace(int raceId) {
        return jdbc.sql(SELECT_RACE + " WHERE rc.race_id = :id").param("id", raceId)
                .query(RaceRepository::mapRace).optional();
    }

    /** fromDate null = every race; otherwise races on or after that day. */
    public List<RaceModels.RaceEvent> listRaces(LocalDate fromDate) {
        if (fromDate == null) {
            return jdbc.sql(SELECT_RACE + " ORDER BY rc.race_date DESC, rc.race_name")
                    .query(RaceRepository::mapRace).list();
        }
        return jdbc.sql(SELECT_RACE + " WHERE rc.race_date >= :from ORDER BY rc.race_date, rc.race_name")
                .param("from", fromDate).query(RaceRepository::mapRace).list();
    }

    public int insertRace(RaceModels.RaceEventRequest r, String name, int userId) {
        return jdbc.sql("""
                INSERT INTO RACE (race_name, race_date, distance_m, venue, entry_deadline, entry_fee,
                                  max_entries, created_by)
                OUTPUT INSERTED.race_id
                VALUES (:name, :date, :dist, :venue, :deadline, :fee, :max, :by)
                """)
                .param("name", name).param("date", r.raceDate()).param("dist", r.distanceM())
                .param("venue", r.venue() == null || r.venue().isBlank() ? null : r.venue().trim())
                .param("deadline", r.entryDeadline()).param("fee", r.entryFee())
                .param("max", r.maxEntries()).param("by", userId)
                .query(Integer.class).single();
    }

    /**
     * Locks the race row until the transaction ends, so two registrations for
     * the last place queue up instead of both counting 11 of 12.
     * (Not "UPDATE RACE SET race_id = race_id": an IDENTITY column cannot be
     * updated, not even to itself.)
     */
    public void lockRace(int raceId) {
        jdbc.sql("SELECT race_id FROM RACE WITH (UPDLOCK, ROWLOCK) WHERE race_id = :id")
                .param("id", raceId).query(Integer.class).optional();
    }

    public int cancelRace(int raceId, String reason) {
        return jdbc.sql("""
                UPDATE RACE SET status = 'CANCELLED', cancel_reason = :reason
                WHERE race_id = :id AND status = 'OPEN'
                """).param("id", raceId).param("reason", reason).update();
    }

    /** Entries of a race still waiting for the race (no result) - scratched when the race is cancelled. */
    public List<RaceModels.Entry> openEntries(int raceId) {
        return jdbc.sql(SELECT + " WHERE r.race_id = :id AND r.result IS NULL")
                .param("id", raceId).query(RaceRepository::map).list();
    }

    public int resultsRecorded(int raceId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM RACE_REGISTRATION
                WHERE race_id = :id AND result IN ('WON', 'PLACED', 'UNPLACED')
                """).param("id", raceId).query(Integer.class).single();
    }

    /** A completed trial run (V16) of this horse within the last `days` days (#3, warning only). */
    public boolean hasRecentTrial(int horseId, int days) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM TRAINING_SESSION
                WHERE horse_id = :h AND is_trial_run = 1 AND status = 'COMPLETED'
                  AND session_date >= DATEADD(DAY, -:days, SYSUTCDATETIME())
                """).param("h", horseId).param("days", days).query(Integer.class).single() > 0;
    }

    /** Năm có dữ liệu, mới nhất trước, cho bộ lọc năm ở frontend. */
    public List<Integer> years() {
        return jdbc.sql("SELECT DISTINCT YEAR(race_date) AS y FROM RACE_REGISTRATION ORDER BY y DESC")
                .query(Integer.class).list();
    }
}

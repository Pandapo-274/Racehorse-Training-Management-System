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

/** Chỉ nói chuyện với RACE_REGISTRATION, không chứa luật nghiệp vụ. */
@Repository
public class RaceRepository {

    private final JdbcClient jdbc;

    public RaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT r.registration_id, r.horse_id, h.horse_name, h.stall_no, h.owner_id,
                   o.full_name AS owner_name, r.race_name, r.race_date, r.registered_by,
                   u.full_name AS registered_by_name, r.result, r.prize_money, r.note
            FROM RACE_REGISTRATION r
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
                rs.getString("note"), stateOf(result, date));
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

    public boolean exists(int horseId, String raceName, LocalDate date) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM RACE_REGISTRATION
                WHERE horse_id = :h AND race_name = :n AND race_date = :d
                """).param("h", horseId).param("n", raceName).param("d", date)
                .query(Integer.class).single() > 0;
    }

    /** Ngựa đã có giải khác (chưa rút) trong cùng ngày: một con không chạy hai giải một ngày. */
    public Optional<String> otherRaceOn(int horseId, LocalDate date) {
        return jdbc.sql("""
                SELECT TOP 1 race_name FROM RACE_REGISTRATION
                WHERE horse_id = :h AND race_date = :d AND (result IS NULL OR result <> 'SCRATCHED')
                """).param("h", horseId).param("d", date).query(String.class).optional();
    }

    public int insert(int horseId, String raceName, LocalDate date, int by, String note) {
        return jdbc.sql("""
                INSERT INTO RACE_REGISTRATION (horse_id, race_name, race_date, registered_by, note)
                OUTPUT INSERTED.registration_id
                VALUES (:h, :n, :d, :by, :note)
                """).param("h", horseId).param("n", raceName).param("d", date)
                .param("by", by).param("note", note)
                .query(Integer.class).single();
    }

    /** Ghi kết quả; điều kiện result IS NULL OR <> SCRATCHED để không ghi đè giải đã rút. */
    public int saveResult(int id, String result, BigDecimal prize, String note) {
        return jdbc.sql("""
                UPDATE RACE_REGISTRATION SET result = :res, prize_money = :prize, note = :note
                WHERE registration_id = :id AND (result IS NULL OR result <> 'SCRATCHED')
                """).param("id", id).param("res", result).param("prize", prize).param("note", note)
                .update();
    }

    public int scratch(int id, String note) {
        return jdbc.sql("""
                UPDATE RACE_REGISTRATION SET result = 'SCRATCHED', prize_money = NULL, note = :note
                WHERE registration_id = :id AND result IS NULL
                """).param("id", id).param("note", note).update();
    }

    /** Năm có dữ liệu, mới nhất trước, cho bộ lọc năm ở frontend. */
    public List<Integer> years() {
        return jdbc.sql("SELECT DISTINCT YEAR(race_date) AS y FROM RACE_REGISTRATION ORDER BY y DESC")
                .query(Integer.class).list();
    }
}

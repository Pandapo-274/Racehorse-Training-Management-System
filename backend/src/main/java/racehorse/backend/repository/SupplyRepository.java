package racehorse.backend.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.entity.SupplyModels;

@Repository
public class SupplyRepository {

    private static final String SELECT = """
            SELECT s.supply_id, s.supply_name, s.category, s.unit, s.min_threshold, s.current_qty,
                   s.area, s.needs_restock, s.updated_by, u.full_name AS updater, s.updated_at,
                   s.expiry_date, s.unit_price
            FROM SUPPLY_STOCK s JOIN APP_USER u ON u.user_id = s.updated_by
            """;

    private final JdbcClient jdbc;

    public SupplyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static SupplyModels.Supply map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SupplyModels.Supply(rs.getInt("supply_id"), rs.getString("supply_name"), rs.getString("category"),
                rs.getString("unit"), rs.getBigDecimal("min_threshold"), rs.getBigDecimal("current_qty"),
                rs.getString("area"), rs.getInt("needs_restock") == 1, rs.getInt("updated_by"),
                rs.getString("updater"),
                rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("expiry_date", LocalDate.class),
                isExpired(rs.getObject("expiry_date", LocalDate.class)),
                rs.getBigDecimal("unit_price"));
    }

    /** Computed in Java, in the same zone as LocalDate.now() everywhere else. */
    private static boolean isExpired(LocalDate expiry) {
        return expiry != null && expiry.isBefore(LocalDate.now());
    }

    public List<SupplyModels.Supply> find(String area, String category, boolean lowOnly) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (area != null) {
            sql.append(" AND s.area = :area");
            params.put("area", area);
        }
        if (category != null) {
            sql.append(" AND s.category = :cat");
            params.put("cat", category);
        }
        if (lowOnly) sql.append(" AND s.needs_restock = 1");
        sql.append(" ORDER BY s.needs_restock DESC, s.area, s.category, s.supply_name");
        return jdbc.sql(sql.toString()).params(params).query(SupplyRepository::map).list();
    }

    public Optional<SupplyModels.Supply> findById(int id) {
        return jdbc.sql(SELECT + " WHERE s.supply_id = :id").param("id", id)
                .query(SupplyRepository::map).optional();
    }

    public int insert(SupplyModels.SupplyRequest r, int userId) {
        return jdbc.sql("""
                INSERT INTO SUPPLY_STOCK (supply_name, category, unit, min_threshold, current_qty, area,
                                          expiry_date, unit_price, updated_by)
                OUTPUT INSERTED.supply_id
                VALUES (:name, :cat, :unit, :min, :qty, :area, :expiry, :price, :by)
                """)
                .param("name", r.supplyName().trim()).param("cat", r.category()).param("unit", r.unit().trim())
                .param("min", r.minThreshold()).param("qty", r.currentQty())
                .param("area", blankToNull(r.area()))
                .param("expiry", "MEDICINE".equals(r.category()) ? r.expiryDate() : null)
                .param("price", r.unitPrice())
                .param("by", userId)
                .query(Integer.class).single();
    }

    /** Edits the catalogue entry. Does NOT touch current_qty: quantities move only through the ledger. */
    public void update(int id, SupplyModels.SupplyRequest r, int userId) {
        jdbc.sql("""
                UPDATE SUPPLY_STOCK SET supply_name = :name, category = :cat, unit = :unit,
                       min_threshold = :min, area = :area, expiry_date = :expiry, unit_price = :price,
                       updated_by = :by, updated_at = SYSUTCDATETIME()
                WHERE supply_id = :id
                """)
                .param("name", r.supplyName().trim()).param("cat", r.category()).param("unit", r.unit().trim())
                .param("min", r.minThreshold())
                .param("area", blankToNull(r.area()))
                .param("expiry", "MEDICINE".equals(r.category()) ? r.expiryDate() : null)
                .param("price", r.unitPrice())
                .param("by", userId).param("id", id)
                .update();
    }

    /**
     * Takes up to qty out of the store in ONE statement, never below 0
     * (CK_SUP_qty would turn a shortfall into a 500). Returns {before, after}.
     * One statement, not read-then-write, so two grooms finishing tasks at the
     * same moment cannot both read 10 and both write 7.
     */
    public Optional<BigDecimal[]> takeUpTo(int supplyId, BigDecimal qty, int userId) {
        return jdbc.sql("""
                UPDATE SUPPLY_STOCK
                SET current_qty = CASE WHEN current_qty >= :q THEN current_qty - :q ELSE 0 END,
                    updated_by = :by, updated_at = SYSUTCDATETIME()
                OUTPUT DELETED.current_qty AS before_qty, INSERTED.current_qty AS after_qty
                WHERE supply_id = :id
                """)
                .param("q", qty).param("by", userId).param("id", supplyId)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("before_qty"), rs.getBigDecimal("after_qty")})
                .optional();
    }

    /** Puts qty back into the store (delivery, reopened task). Returns {before, after}. */
    public Optional<BigDecimal[]> addWithDetails(int supplyId, BigDecimal qty, BigDecimal price, String unit, String area, int userId) {
        return jdbc.sql("""
                UPDATE SUPPLY_STOCK
                SET current_qty = current_qty + :q,
                    unit_price = COALESCE(:price, unit_price),
                    updated_by = :by,
                    updated_at = SYSUTCDATETIME()
                OUTPUT DELETED.current_qty AS before_qty, INSERTED.current_qty AS after_qty
                WHERE supply_id = :id
                """)
                .param("q", qty)
                .param("price", price)
                .param("by", userId)
                .param("id", supplyId)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("before_qty"), rs.getBigDecimal("after_qty")})
                .optional();
    }

    public Optional<BigDecimal[]> add(int supplyId, BigDecimal qty, int userId) {
        return jdbc.sql("""
                UPDATE SUPPLY_STOCK
                SET current_qty = current_qty + :q, updated_by = :by, updated_at = SYSUTCDATETIME()
                OUTPUT DELETED.current_qty AS before_qty, INSERTED.current_qty AS after_qty
                WHERE supply_id = :id
                """)
                .param("q", qty).param("by", userId).param("id", supplyId)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("before_qty"), rs.getBigDecimal("after_qty")})
                .optional();
    }

    /** Sets the counted quantity. Returns {before, after}. */
    public Optional<BigDecimal[]> setQty(int supplyId, BigDecimal qty, int userId) {
        return jdbc.sql("""
                UPDATE SUPPLY_STOCK
                SET current_qty = :q, updated_by = :by, updated_at = SYSUTCDATETIME()
                OUTPUT DELETED.current_qty AS before_qty, INSERTED.current_qty AS after_qty
                WHERE supply_id = :id
                """)
                .param("q", qty).param("by", userId).param("id", supplyId)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("before_qty"), rs.getBigDecimal("after_qty")})
                .optional();
    }

    public void insertMovement(int supplyId, String type, BigDecimal delta, BigDecimal after,
                               String refType, Integer refId, String reason, int userId) {
        jdbc.sql("""
                INSERT INTO SUPPLY_MOVEMENT (supply_id, movement_type, qty_delta, qty_after,
                                             ref_type, ref_id, reason, created_by)
                VALUES (:s, :t, :d, :a, :rt, :ri, :r, :u)
                """)
                .param("s", supplyId).param("t", type).param("d", delta).param("a", after)
                .param("rt", refType).param("ri", refId).param("r", clip(reason, 255)).param("u", userId)
                .update();
    }

    /**
     * Net quantity a task has taken, per stock item (positive = taken).
     * CONSUME rows are negative and REVERSAL rows positive, so done -> reopen ->
     * done -> reopen always nets back to zero.
     */
    public Map<Integer, BigDecimal> netConsumedBy(String refType, int refId) {
        Map<Integer, BigDecimal> out = new HashMap<>();
        jdbc.sql("""
                SELECT supply_id, -SUM(qty_delta) AS net
                FROM SUPPLY_MOVEMENT
                WHERE ref_type = :rt AND ref_id = :ri AND movement_type IN ('CONSUME', 'REVERSAL')
                GROUP BY supply_id
                """)
                .param("rt", refType).param("ri", refId)
                .query((rs, n) -> Map.entry(rs.getInt("supply_id"), rs.getBigDecimal("net")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public List<SupplyModels.Movement> movements(int supplyId, int limit) {
        return jdbc.sql("""
                SELECT TOP (:limit) m.movement_id, m.supply_id, m.movement_type, m.qty_delta, m.qty_after,
                       m.ref_type, m.ref_id, m.reason, m.created_by, u.full_name, m.created_at
                FROM SUPPLY_MOVEMENT m JOIN APP_USER u ON u.user_id = m.created_by
                WHERE m.supply_id = :id
                ORDER BY m.movement_id DESC
                """)
                .param("limit", limit).param("id", supplyId)
                .query((rs, n) -> new SupplyModels.Movement(
                        rs.getInt("movement_id"), rs.getInt("supply_id"), rs.getString("movement_type"),
                        rs.getBigDecimal("qty_delta"), rs.getBigDecimal("qty_after"),
                        rs.getString("ref_type"), rs.getObject("ref_id", Integer.class),
                        rs.getString("reason"), rs.getInt("created_by"), rs.getString("full_name"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .list();
    }

    /** Another item with the same name in the same area (V24 rule), excluding exceptId. */
    public boolean nameTaken(String name, String area, Integer exceptId) {
        // Conditions appended only when they have a value: "(:x IS NULL OR ...)" is
        // fragile with SQL Server parameter types (see SessionRepository).
        String a = blankToNull(area);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM SUPPLY_STOCK WHERE supply_name = :name");
        Map<String, Object> params = new HashMap<>();
        params.put("name", name.trim());
        if (a == null) {
            sql.append(" AND area IS NULL");
        } else {
            sql.append(" AND area = :area");
            params.put("area", a);
        }
        if (exceptId != null) {
            sql.append(" AND supply_id <> :except");
            params.put("except", exceptId);
        }
        return jdbc.sql(sql.toString()).params(params).query(Integer.class).single() > 0;
    }

    /** Ration lines that take from this item; their quantity is in the item's unit. */
    public int rationLinesUsing(int supplyId) {
        return jdbc.sql("SELECT COUNT(*) FROM FEEDING_SCHEDULE WHERE supply_id = :id")
                .param("id", supplyId).query(Integer.class).single();
    }

    /**
     * Items to put in today's digest (#12): below minimum, or medicine that has
     * expired or expires within soonDays - and not yet reminded today.
     */
    public List<SupplyModels.Supply> dueForReminder(LocalDate today, int soonDays) {
        return jdbc.sql(SELECT + """
                 WHERE (s.last_reminded_on IS NULL OR s.last_reminded_on < :today)
                   AND (s.needs_restock = 1
                        OR (s.category = 'MEDICINE' AND s.expiry_date IS NOT NULL
                            AND s.current_qty > 0
                            AND s.expiry_date <= DATEADD(DAY, :soon, :today)))
                 ORDER BY s.area, s.supply_name
                """)
                .param("today", today).param("soon", soonDays)
                .query(SupplyRepository::map).list();
    }

    /** Marks items as reminded today. The date guard makes a second run on the same day a no-op. */
    public int markReminded(List<Integer> ids, LocalDate today) {
        if (ids.isEmpty()) return 0;
        return jdbc.sql("""
                UPDATE SUPPLY_STOCK SET last_reminded_on = :today
                WHERE supply_id IN (:ids) AND (last_reminded_on IS NULL OR last_reminded_on < :today)
                """)
                .param("today", today).param("ids", ids).update();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}

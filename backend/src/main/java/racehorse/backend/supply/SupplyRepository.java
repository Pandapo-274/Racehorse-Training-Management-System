package racehorse.backend.supply;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SupplyRepository {

    private static final String SELECT = """
            SELECT s.supply_id, s.supply_name, s.category, s.unit, s.min_threshold, s.current_qty,
                   s.area, s.needs_restock, s.updated_by, u.full_name AS updater, s.updated_at
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
                rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
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
                INSERT INTO SUPPLY_STOCK (supply_name, category, unit, min_threshold, current_qty, area, updated_by)
                OUTPUT INSERTED.supply_id
                VALUES (:name, :cat, :unit, :min, :qty, :area, :by)
                """)
                .param("name", r.supplyName().trim()).param("cat", r.category()).param("unit", r.unit().trim())
                .param("min", r.minThreshold()).param("qty", r.currentQty())
                .param("area", r.area() == null || r.area().isBlank() ? null : r.area().trim())
                .param("by", userId)
                .query(Integer.class).single();
    }

    public void update(int id, SupplyModels.SupplyRequest r, int userId) {
        jdbc.sql("""
                UPDATE SUPPLY_STOCK SET supply_name = :name, category = :cat, unit = :unit,
                       min_threshold = :min, current_qty = :qty, area = :area,
                       updated_by = :by, updated_at = SYSUTCDATETIME()
                WHERE supply_id = :id
                """)
                .param("name", r.supplyName().trim()).param("cat", r.category()).param("unit", r.unit().trim())
                .param("min", r.minThreshold()).param("qty", r.currentQty())
                .param("area", r.area() == null || r.area().isBlank() ? null : r.area().trim())
                .param("by", userId).param("id", id)
                .update();
    }

    public void updateQty(int id, BigDecimal qty, int userId) {
        jdbc.sql("""
                UPDATE SUPPLY_STOCK SET current_qty = :qty, updated_by = :by, updated_at = SYSUTCDATETIME()
                WHERE supply_id = :id
                """)
                .param("qty", qty).param("by", userId).param("id", id).update();
    }
}

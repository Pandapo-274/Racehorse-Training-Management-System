package racehorse.backend.expense;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.expense.ExpenseModels.Expense;
import racehorse.backend.expense.ExpenseModels.ExpenseRequest;

/** Chỉ nói chuyện với bảng EXPENSE_LOG, không chứa logic nghiệp vụ. */
@Repository
public class ExpenseRepository {

    private final JdbcClient jdbc;

    public ExpenseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /* LEFT JOIN người ghi: dữ liệu mẫu cũ có thể để recorded_by trống. */
    private static final String FROM = """
            FROM EXPENSE_LOG e
            JOIN HORSE h ON h.horse_id = e.horse_id
            LEFT JOIN APP_USER u ON u.user_id = e.recorded_by
            """;

    private static final String COLUMNS = """
             e.expense_id, e.horse_id, h.horse_name, h.registration_code, e.category, e.amount,
                   e.expense_date, e.recorded_by, u.full_name AS recorded_by_name, e.note
            """;

    private static Expense map(ResultSet rs, int rowNum) throws SQLException {
        return new Expense(
                rs.getInt("expense_id"), rs.getInt("horse_id"),
                rs.getString("horse_name"), rs.getString("registration_code"),
                rs.getString("category"), rs.getBigDecimal("amount"),
                rs.getObject("expense_date", LocalDate.class),
                rs.getObject("recorded_by", Integer.class), rs.getString("recorded_by_name"),
                rs.getString("note"));
    }

    /** Điều kiện WHERE và tham số cho bốn bộ lọc tuỳ chọn. Dùng chung cho danh sách, tổng và đếm. */
    private record Filter(String where, Map<String, Object> params) {
    }

    private static Filter filter(Integer horseId, String category, LocalDate from, LocalDate to) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (horseId != null) {
            where.append(" AND e.horse_id = :horseId");
            params.put("horseId", horseId);
        }
        if (category != null) {
            where.append(" AND e.category = :category");
            params.put("category", category);
        }
        if (from != null) {
            where.append(" AND e.expense_date >= :from");
            params.put("from", from);
        }
        if (to != null) {
            where.append(" AND e.expense_date <= :to");
            params.put("to", to);
        }
        return new Filter(where.toString(), params);
    }

    /** Mới nhất trước, tối đa limit dòng. */
    public List<Expense> find(Integer horseId, String category, LocalDate from, LocalDate to, int limit) {
        Filter f = filter(horseId, category, from, to);
        return jdbc.sql("SELECT TOP (:limit)" + COLUMNS + FROM + f.where()
                        + " ORDER BY e.expense_date DESC, e.expense_id DESC")
                .params(f.params())
                .param("limit", limit)
                .query(ExpenseRepository::map)
                .list();
    }

    /** Tổng tiền theo hạng mục trên mọi dòng khớp bộ lọc. */
    public Map<String, BigDecimal> totals(Integer horseId, String category, LocalDate from, LocalDate to) {
        Filter f = filter(horseId, category, from, to);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.sql("SELECT e.category, SUM(e.amount) AS amount " + FROM + f.where() + " GROUP BY e.category")
                .params(f.params())
                .query((rs, n) -> Map.entry(rs.getString("category"), rs.getBigDecimal("amount")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public int count(Integer horseId, String category, LocalDate from, LocalDate to) {
        Filter f = filter(horseId, category, from, to);
        return jdbc.sql("SELECT COUNT(*) " + FROM + f.where())
                .params(f.params())
                .query(Integer.class)
                .single();
    }

    public Optional<Expense> findById(int expenseId) {
        return jdbc.sql("SELECT" + COLUMNS + FROM + " WHERE e.expense_id = :id")
                .param("id", expenseId)
                .query(ExpenseRepository::map)
                .optional();
    }

    public int insert(ExpenseRequest r, int recordedBy) {
        return jdbc.sql("""
                INSERT INTO EXPENSE_LOG (horse_id, category, amount, expense_date, recorded_by, note)
                OUTPUT INSERTED.expense_id
                VALUES (:horseId, :category, :amount, :expenseDate, :recordedBy, :note)
                """)
                .param("horseId", r.horseId())
                .param("category", r.category())
                .param("amount", r.amount())
                .param("expenseDate", r.expenseDate())
                .param("recordedBy", recordedBy)
                .param("note", r.note())
                .query(Integer.class)
                .single();
    }

    /** @return 1 nếu đã xoá, 0 nếu không có khoản chi này. */
    public int delete(int expenseId) {
        return jdbc.sql("DELETE FROM EXPENSE_LOG WHERE expense_id = :id")
                .param("id", expenseId)
                .update();
    }
}

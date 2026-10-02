package racehorse.backend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/db-test")
public class DbTestController {

    private final DataSource dataSource;

    public DbTestController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            result.put("connected", true);
            result.put("database", conn.getCatalog());

            List<Map<String, Object>> roles = new ArrayList<>();
            String sql = "SELECT role_id, role_name, description FROM ROLE ORDER BY role_id";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("role_id", rs.getInt("role_id"));
                    row.put("role_name", rs.getString("role_name"));
                    row.put("description", rs.getString("description"));
                    roles.add(row);
                }
            }
            result.put("roleCount", roles.size());
            result.put("roles", roles);
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
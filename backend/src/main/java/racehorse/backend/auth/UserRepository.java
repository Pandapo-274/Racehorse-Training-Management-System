package racehorse.backend.auth;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Chỉ nói chuyện với DB, không chứa logic nghiệp vụ. */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_USER = """
            SELECT u.user_id, u.username, u.password_hash, u.full_name, u.email, u.status, r.role_name
            FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
            """;

    private static UserRecord mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserRecord(rs.getInt("user_id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("status"), rs.getString("role_name"));
    }

    /** Đăng nhập bằng username HOẶC email. Username cấm ký tự @ nên hai loại không bao giờ đụng nhau. */
    public Optional<UserRecord> findByLogin(String login) {
        return jdbc.sql(SELECT_USER + " WHERE u.username = :v OR u.email = :v")
                .param("v", login)
                .query(UserRepository::mapUser)
                .optional();
    }

    public Optional<UserRecord> findById(int userId) {
        return jdbc.sql(SELECT_USER + " WHERE u.user_id = :id")
                .param("id", userId)
                .query(UserRepository::mapUser)
                .optional();
    }

    public Optional<Integer> findRoleId(String roleName) {
        return jdbc.sql("SELECT role_id FROM ROLE WHERE role_name = :name")
                .param("name", roleName)
                .query(Integer.class)
                .optional();
    }

    public boolean existsByUsername(String username) {
        return jdbc.sql("SELECT COUNT(*) FROM APP_USER WHERE username = :v")
                .param("v", username).query(Integer.class).single() > 0;
    }

    public boolean existsByEmail(String email) {
        return jdbc.sql("SELECT COUNT(*) FROM APP_USER WHERE email = :v")
                .param("v", email).query(Integer.class).single() > 0;
    }

    /** OUTPUT INSERTED là cách SQL Server trả luôn id vừa sinh ra (IDENTITY). */
    public int insert(int roleId, String username, String passwordHash,
                      String fullName, String email, String phone) {
        return jdbc.sql("""
                INSERT INTO APP_USER (role_id, username, password_hash, full_name, email, phone)
                OUTPUT INSERTED.user_id
                VALUES (:roleId, :username, :hash, :fullName, :email, :phone)
                """)
                .param("roleId", roleId)
                .param("username", username)
                .param("hash", passwordHash)
                .param("fullName", fullName)
                .param("email", email)
                .param("phone", phone)
                .query(Integer.class)
                .single();
    }

    public void writeAudit(Integer userId, String action, String targetEntity,
                           Integer targetId, String result, String ip) {
        jdbc.sql("""
                INSERT INTO AUDIT_LOG (user_id, action, target_entity, target_id, result, ip_address)
                VALUES (:userId, :action, :entity, :targetId, :result, :ip)
                """)
                .param("userId", userId)
                .param("action", action)
                .param("entity", targetEntity)
                .param("targetId", targetId)
                .param("result", result)
                .param("ip", ip)
                .update();
    }
}

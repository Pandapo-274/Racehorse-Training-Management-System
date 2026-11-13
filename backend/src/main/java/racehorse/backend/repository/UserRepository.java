package racehorse.backend.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.entity.UserRecord;
import racehorse.backend.security.AuthInterceptor;

/** Chỉ nói chuyện với DB, không chứa logic nghiệp vụ. */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_USER = """
            SELECT u.user_id, u.username, u.password_hash, u.full_name, u.email, u.status,
                   u.avatar_url, u.must_change_password, u.locked_until, r.role_name
            FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
            """;

    /** DATETIME2 columns written with SYSUTCDATETIME() are UTC with no zone. */
    private static java.time.Instant utc(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.time.LocalDateTime t = rs.getObject(col, java.time.LocalDateTime.class);
        return t == null ? null : t.toInstant(java.time.ZoneOffset.UTC);
    }

    private static UserRecord mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserRecord(rs.getInt("user_id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("status"), rs.getString("role_name"),
                rs.getString("avatar_url"),
                rs.getBoolean("must_change_password"),
                utc(rs, "locked_until"));
    }

    /**
     * Cờ "phải đổi mật khẩu" (V14). AuthInterceptor gọi ở mỗi request, nên chỉ
     * đọc đúng một cột theo khoá chính.
     */
    /** Trạng thái tài khoản mà AuthInterceptor kiểm ở mỗi request. */
    /**
     * tokensValidAfter (V39): tokens issued before this moment are dead (password
     * changed or reset, role changed, account suspended). Kept in the database so
     * a backend restart does not bring them back.
     */
    public record AccessState(String status, String roleName, boolean mustChangePassword,
                              java.time.Instant tokensValidAfter) {
    }

    public Optional<AccessState> findAccessState(int userId) {
        return jdbc.sql("""
                SELECT u.status, r.role_name, u.must_change_password, u.tokens_valid_after
                FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
                WHERE u.user_id = :id
                """)
                .param("id", userId)
                .query((rs, n) -> new AccessState(rs.getString("status"), rs.getString("role_name"),
                        rs.getBoolean("must_change_password"), utc(rs, "tokens_valid_after")))
                .optional();
    }

    /** V39: every token issued before cutoff (UTC, whole seconds like the JWT iat) stops working. */
    public void setTokensValidAfter(int userId, java.time.Instant cutoff) {
        jdbc.sql("UPDATE APP_USER SET tokens_valid_after = :t WHERE user_id = :id")
                .param("t", java.time.LocalDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
                .param("id", userId)
                .update();
    }

    /**
     * V39: one more wrong password. The max-th in a row locks sign-in for
     * lockMinutes and starts the count again. One statement, so two wrong tries
     * at the same moment both count. Returns the lock time (UTC) if this try
     * locked the account, else null.
     */
    public java.time.Instant recordFailedLogin(int userId, int max, int lockMinutes) {
        return jdbc.sql("""
                UPDATE APP_USER
                SET locked_until = CASE WHEN failed_login_count + 1 >= :max
                                        THEN DATEADD(MINUTE, :m, SYSUTCDATETIME()) ELSE locked_until END,
                    failed_login_count = CASE WHEN failed_login_count + 1 >= :max
                                              THEN 0 ELSE failed_login_count + 1 END
                OUTPUT INSERTED.locked_until AS locked_until, INSERTED.failed_login_count AS failed
                WHERE user_id = :id
                """)
                .param("max", max).param("m", lockMinutes).param("id", userId)
                .query((rs, n) -> rs.getInt("failed") == 0 ? utc(rs, "locked_until") : null)
                .optional().orElse(null);
    }

    /** V39: a correct sign-in, a reset or the manager clears the count and the lock. */
    public void clearLoginLock(int userId) {
        jdbc.sql("""
                UPDATE APP_USER SET failed_login_count = 0, locked_until = NULL
                WHERE user_id = :id AND (failed_login_count <> 0 OR locked_until IS NOT NULL)
                """)
                .param("id", userId)
                .update();
    }

    public boolean mustChangePassword(int userId) {
        return jdbc.sql("SELECT must_change_password FROM APP_USER WHERE user_id = :id")
                .param("id", userId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    public void setMustChangePassword(int userId, boolean value) {
        jdbc.sql("UPDATE APP_USER SET must_change_password = :v WHERE user_id = :id")
                .param("v", value)
                .param("id", userId)
                .update();
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
        writeAudit(userId, action, targetEntity, targetId, result, ip, null);
    }

    /**
     * Same as above, plus a short description of what changed (V20), e.g.
     * "WON, 100000000 -> PLACED, 40000000". Clipped to the column's 500 chars.
     */
    public void writeAudit(Integer userId, String action, String targetEntity,
                           Integer targetId, String result, String ip, String detail) {
        String d = detail == null || detail.isBlank() ? null
                : (detail.length() <= 500 ? detail : detail.substring(0, 497) + "...");
        jdbc.sql("""
                INSERT INTO AUDIT_LOG (user_id, action, target_entity, target_id, result, ip_address, detail)
                VALUES (:userId, :action, :entity, :targetId, :result, :ip, :detail)
                """)
                .param("userId", userId)
                .param("action", action)
                .param("entity", targetEntity)
                .param("targetId", targetId)
                .param("result", result)
                .param("ip", ip)
                .param("detail", d)
                .update();
    }
}

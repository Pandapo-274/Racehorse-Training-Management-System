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

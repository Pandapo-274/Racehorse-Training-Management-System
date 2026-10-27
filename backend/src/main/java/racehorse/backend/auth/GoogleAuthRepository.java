package racehorse.backend.auth;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GoogleAuthRepository {

    private final JdbcClient jdbc;

    public GoogleAuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Chỉ trả user_id. Việc đọc cả hồ sơ user giao cho UserRepository.findById (của team),
     * nhờ vậy khi team thêm field vào UserRecord thì code Google không phải sửa theo.
     */
    public Optional<Integer> findUserIdByGoogleSub(String sub) {
        return jdbc.sql("SELECT user_id FROM APP_USER WHERE google_sub = :sub")
                .param("sub", sub)
                .query(Integer.class)
                .optional();
    }

    /** google_sub hiện tại của một user (rỗng nếu chưa liên kết Google). */
    public Optional<String> findGoogleSub(int userId) {
        return jdbc.sql("SELECT google_sub FROM APP_USER WHERE user_id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    /** Gắn Google vào tài khoản có sẵn và đồng thời vô hiệu mật khẩu cũ (xem giải thích ở GoogleAuthService). */
    public void linkGoogle(int userId, String sub, String unusablePasswordHash) {
        // Mật khẩu bị thay bằng chuỗi ngẫu nhiên nên mật khẩu tạm (V14) cũng hết hiệu lực:
        // tắt cờ, nếu không người dùng bị bắt đổi một mật khẩu mà họ không thể biết.
        jdbc.sql("""
                UPDATE APP_USER SET google_sub = :sub, password_hash = :h, must_change_password = 0
                WHERE user_id = :id
                """)
                .param("sub", sub)
                .param("h", unusablePasswordHash)
                .param("id", userId)
                .update();
    }

    public int insertGoogleUser(int roleId, String username, String passwordHash,
                                String fullName, String email, String sub) {
        return jdbc.sql("""
                INSERT INTO APP_USER (role_id, username, password_hash, full_name, email, google_sub)
                OUTPUT INSERTED.user_id
                VALUES (:roleId, :username, :hash, :fullName, :email, :sub)
                """)
                .param("roleId", roleId)
                .param("username", username)
                .param("hash", passwordHash)
                .param("fullName", fullName)
                .param("email", email)
                .param("sub", sub)
                .query(Integer.class)
                .single();
    }
}

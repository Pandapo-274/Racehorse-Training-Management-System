package racehorse.backend.password;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordRepository {

    private final JdbcClient jdbc;

    public PasswordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void updatePasswordHash(int userId, String hash) {
        jdbc.sql("UPDATE APP_USER SET password_hash = :h WHERE user_id = :id")
                .param("h", hash)
                .param("id", userId)
                .update();
    }

    /** Mỗi lần xin link mới thì các link cũ chưa dùng của user này bị vô hiệu. */
    public void invalidateOpenTokens(int userId) {
        jdbc.sql("UPDATE PASSWORD_RESET_TOKEN SET used_at = SYSUTCDATETIME() WHERE user_id = :id AND used_at IS NULL")
                .param("id", userId)
                .update();
    }

    /** Hạn dùng tính bằng đồng hồ của SQL Server (UTC) để khỏi lệch múi giờ với Java. */
    public void insertToken(int userId, String tokenHash, int validMinutes) {
        jdbc.sql("""
                INSERT INTO PASSWORD_RESET_TOKEN (user_id, token_hash, expires_at)
                VALUES (:id, :h, DATEADD(MINUTE, :m, SYSUTCDATETIME()))
                """)
                .param("id", userId)
                .param("h", tokenHash)
                .param("m", validMinutes)
                .update();
    }

    /**
     * "Dùng" token trong MỘT câu lệnh duy nhất: chỉ thành công nếu chưa dùng và chưa hết hạn.
     * Gộp kiểm tra + đánh dấu vào một lệnh để 2 người bấm cùng lúc thì chỉ 1 người thắng.
     */
    public Optional<Integer> consumeValidToken(String tokenHash) {
        return jdbc.sql("""
                UPDATE PASSWORD_RESET_TOKEN
                SET used_at = SYSUTCDATETIME()
                OUTPUT INSERTED.user_id
                WHERE token_hash = :h AND used_at IS NULL AND expires_at > SYSUTCDATETIME()
                """)
                .param("h", tokenHash)
                .query(Integer.class)
                .optional();
    }
}

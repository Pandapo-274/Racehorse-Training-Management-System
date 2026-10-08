package racehorse.backend.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {

    private static final String SELECT_PROFILE = """
            SELECT u.user_id, u.username, u.full_name, u.email, u.phone, u.avatar_url,
                   u.status, u.created_at, r.role_name
            FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
            """;

    private final JdbcClient jdbc;

    public ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static ProfileResponse map(ResultSet rs, int rowNum) throws SQLException {
        // created_at lưu giờ UTC (SYSUTCDATETIME) nên gắn offset UTC để frontend hiểu đúng múi giờ.
        LocalDateTime created = rs.getObject("created_at", LocalDateTime.class);
        return new ProfileResponse(
                rs.getInt("user_id"), rs.getString("username"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("avatar_url"),
                rs.getString("role_name"), rs.getString("status"),
                created == null ? null : created.toInstant(ZoneOffset.UTC));
    }

    public Optional<ProfileResponse> findById(int userId) {
        return jdbc.sql(SELECT_PROFILE + " WHERE u.user_id = :id")
                .param("id", userId)
                .query(ProfileRepository::map)
                .optional();
    }

    /** Email đã thuộc về NGƯỜI KHÁC chưa? (giữ nguyên email của chính mình thì không tính là trùng) */
    public boolean emailTakenByOther(String email, int userId) {
        return jdbc.sql("SELECT COUNT(*) FROM APP_USER WHERE email = :e AND user_id <> :id")
                .param("e", email)
                .param("id", userId)
                .query(Integer.class).single() > 0;
    }

    public void updateBasic(int userId, String fullName, String email, String phone) {
        jdbc.sql("UPDATE APP_USER SET full_name = :n, email = :e, phone = :p WHERE user_id = :id")
                .param("n", fullName)
                .param("e", email)
                .param("p", phone)
                .param("id", userId)
                .update();
    }

    public Optional<String> findAvatarUrl(int userId) {
        return jdbc.sql("SELECT avatar_url FROM APP_USER WHERE user_id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    public void updateAvatar(int userId, String avatarUrl) {
        jdbc.sql("UPDATE APP_USER SET avatar_url = :a WHERE user_id = :id")
                .param("a", avatarUrl)
                .param("id", userId)
                .update();
    }
}

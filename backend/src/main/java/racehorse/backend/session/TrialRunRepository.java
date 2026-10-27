package racehorse.backend.session;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cố ý nằm riêng, không thêm vào SessionRepository: việc đánh dấu chạy thử chỉ cần vài cột
 * và không nên phụ thuộc vào hình dạng của SessionResponse (nhiều người cùng sửa file đó).
 */
@Repository
public class TrialRunRepository {

    public record State(int sessionId, String status, boolean trialRun, String videoUrl,
                        int horseId, String horseName, int ownerId) {
    }

    private final JdbcClient jdbc;

    public TrialRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<State> findState(int sessionId) {
        return jdbc.sql("""
                SELECT s.session_id, s.status, s.is_trial_run, s.video_url,
                       s.horse_id, h.horse_name, h.owner_id
                FROM TRAINING_SESSION s JOIN HORSE h ON h.horse_id = s.horse_id
                WHERE s.session_id = :id
                """)
                .param("id", sessionId)
                .query((rs, n) -> new State(rs.getInt("session_id"), rs.getString("status"),
                        rs.getBoolean("is_trial_run"), rs.getString("video_url"),
                        rs.getInt("horse_id"), rs.getString("horse_name"), rs.getInt("owner_id")))
                .optional();
    }

    public int update(int sessionId, boolean trialRun, String videoUrl) {
        return jdbc.sql("UPDATE TRAINING_SESSION SET is_trial_run = :t, video_url = :v WHERE session_id = :id")
                .param("t", trialRun)
                .param("v", videoUrl)
                .param("id", sessionId)
                .update();
    }
}

package racehorse.backend.session;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;

@Service
public class SessionService {

    private static final Set<String> STATUSES = Set.of("SCHEDULED", "RUNNING", "COMPLETED", "STOPPED_EARLY");

    private final SessionRepository sessions;
    private final UserRepository users; // dùng lại writeAudit

    public SessionService(SessionRepository sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    public List<SessionResponse> list(String status, Integer horseId, int limit) {
        String st = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        if (st != null && !STATUSES.contains(st)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status filter",
                    Map.of("status", "Must be one of " + STATUSES));
        }
        return sessions.search(st, horseId, Math.max(1, Math.min(limit, 200)));
    }

    public SessionResponse get(int sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));
    }

    /** @Transactional: lưu đánh giá + ghi audit cùng thành công hoặc cùng rollback. */
    @Transactional
    public SessionResponse evaluate(AuthenticatedUser me, int sessionId, EvaluateSessionRequest req, String ip) {
        SessionResponse current = get(sessionId);

        // Ca tập chưa bắt đầu thì chưa có gì để đánh giá.
        if ("SCHEDULED".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Session has not started yet, it cannot be evaluated");
        }
        // Kiểm tra chéo giữa các field mà annotation không làm được.
        if (req.peakHeartRate() < req.avgHeartRate()) {
            throw fieldError("peakHeartRate", "Peak heart rate cannot be lower than average heart rate");
        }
        if (req.recoveryHeartRate() != null && req.recoveryHeartRate() > req.peakHeartRate()) {
            throw fieldError("recoveryHeartRate", "Recovery heart rate cannot be higher than peak heart rate");
        }

        // Ca đã bị dừng sớm (vd. do cảnh báo UC11) thì giữ nguyên trạng thái đó, trừ khi trainer chủ động đổi.
        String finalStatus = req.status() != null ? req.status()
                : ("STOPPED_EARLY".equals(current.status()) ? "STOPPED_EARLY" : "COMPLETED");

        sessions.saveEvaluation(sessionId, req, finalStatus, me.userId());
        users.writeAudit(me.userId(), "EVALUATE_SESSION", "TRAINING_SESSION", sessionId, "SUCCESS", ip);
        return get(sessionId);
    }

    private static ApiException fieldError(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
    }
}

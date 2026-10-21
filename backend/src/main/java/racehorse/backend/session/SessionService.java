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

    /* CANCELLED do V8 them vao. Thieu no o day thi ?status=CANCELLED tra 400
       trong khi database hoan toan co trang thai do - mot buoi tap ton tai ma
       khong loc ra duoc. */
    private static final Set<String> STATUSES =
            Set.of("SCHEDULED", "RUNNING", "COMPLETED", "STOPPED_EARLY", "CANCELLED");

    /** Nhung trang thai da thuc su dien ra, nen moi co gi de nhan xet. */
    private static final Set<String> EVALUATABLE = Set.of("RUNNING", "COMPLETED", "STOPPED_EARLY");

    private final SessionRepository sessions;
    private final UserRepository users; // dùng lại writeAudit

    public SessionService(SessionRepository sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    public List<SessionResponse> list(String status, Integer horseId, int limit, AuthenticatedUser user) {
        String st = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        if (st != null && !STATUSES.contains(st)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status filter",
                    Map.of("status", "Must be one of " + STATUSES));
        }
        // Chủ ngựa chỉ thấy nhận xét của ngựa mình; lọc trong SQL, không ở Java.
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        return sessions.search(st, horseId, Math.max(1, Math.min(limit, 200)), ownerId);
    }

    /**
     * Đọc một buổi tập, có kiểm quyền.
     *
     * Nhận AuthenticatedUser chứ không chỉ sessionId: nếu không, mở
     * @RequireRole cho HORSE_OWNER là mọi chủ ngựa đọc được nhận xét của mọi
     * con ngựa trong chuồng, chỉ cần đoán số id.
     */
    public SessionResponse get(int sessionId, AuthenticatedUser user) {
        SessionResponse s = sessions.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));
        if ("HORSE_OWNER".equals(user.role())) {
            int ownerId = sessions.findOwnerOfSession(sessionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse of this session not found"));
            if (ownerId != user.userId()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This session belongs to another owner's horse");
            }
        }
        return s;
    }

    /** Dùng trong nội bộ service, nơi quyền đã được kiểm ở tầng trên. */
    private SessionResponse getOrThrow(int sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));
    }

    /** @Transactional: lưu đánh giá + ghi audit cùng thành công hoặc cùng rollback. */
    @Transactional
    public SessionResponse evaluate(AuthenticatedUser me, int sessionId, EvaluateSessionRequest req, String ip) {
        SessionResponse current = getOrThrow(sessionId);

        /* Chỉ buổi tập đã thực sự diễn ra mới có gì để nhận xét.
           Trước đây chỉ chặn SCHEDULED, nên từ V8 một buổi CANCELLED sẽ lọt
           qua tầng này rồi đụng CK_SESSION_cancelled_clean ở database, và
           người dùng nhận 500 cho một việc có lý do nói được thành câu. */
        if (!EVALUATABLE.contains(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "CANCELLED".equals(current.status())
                            ? "This session was cancelled, it cannot be evaluated"
                            : "Session has not started yet, it cannot be evaluated");
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
        return getOrThrow(sessionId);
    }

    private static ApiException fieldError(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of(field, msg));
    }
}

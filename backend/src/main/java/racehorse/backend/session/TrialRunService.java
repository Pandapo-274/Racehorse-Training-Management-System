package racehorse.backend.session;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

@Service
public class TrialRunService {

    /** Buổi đã bắt đầu thì mới có gì để quay. SCHEDULED chỉ được đánh dấu "chạy thử", chưa gắn video được. */
    private static final Set<String> HAS_RUN = Set.of("RUNNING", "COMPLETED", "STOPPED_EARLY");

    private final TrialRunRepository repo;
    private final UserRepository users;
    private final NotificationService notifications;

    public TrialRunService(TrialRunRepository repo, UserRepository users, NotificationService notifications) {
        this.repo = repo;
        this.users = users;
        this.notifications = notifications;
    }

    /** @Transactional: lưu + ghi audit + báo chủ ngựa cùng thành công hoặc cùng huỷ. */
    @Transactional
    public TrialRunResponse update(AuthenticatedUser trainer, int sessionId, TrialRunRequest req, String ip) {
        TrialRunRepository.State s = repo.findState(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Training session not found"));

        if ("CANCELLED".equals(s.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "A cancelled session cannot be a trial run");
        }

        boolean trialRun = req.trialRun();
        String video = normalizeVideoUrl(req.videoUrl());

        // Không phải chạy thử thì không có video: bỏ đánh dấu là xoá luôn video, nhưng gửi kèm link thì báo lỗi rõ.
        if (!trialRun && video != null) {
            throw fieldError(HttpStatus.BAD_REQUEST, "videoUrl", "A video can only be attached to a trial run");
        }
        if (video != null && !HAS_RUN.contains(s.status())) {
            throw fieldError(HttpStatus.CONFLICT, "videoUrl",
                    "Add the video after the session has started (currently " + s.status() + ")");
        }

        repo.update(sessionId, trialRun, video);
        users.writeAudit(trainer.userId(), "SESSION_TRIAL_RUN_UPDATE", "TRAINING_SESSION", sessionId, "SUCCESS", ip);

        // Chủ ngựa chỉ cần biết khi có video MỚI (hoặc đổi sang video khác), không báo khi gỡ hay sửa cờ.
        if (video != null && !Objects.equals(video, s.videoUrl())) {
            notifications.notifyUser(s.ownerId(), new Notice(
                    "TRIAL_VIDEO_ADDED", "Trial run video available",
                    s.horseName() + ": a trial run video was added to a training session.",
                    Notice.TRAINING_SESSION, sessionId));
        }
        return new TrialRunResponse(sessionId, trialRun, video);
    }

    /**
     * Chỉ nhận http/https có host. Link này sẽ được frontend hiện thành đường dẫn bấm được (hoặc khung
     * nhúng), nên chặn các kiểu như javascript: hay data: ngay ở cửa vào.
     */
    private static String normalizeVideoUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        try {
            URI u = new URI(v);
            String scheme = u.getScheme();
            boolean http = scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
            if (!http || u.getHost() == null) {
                throw fieldError(HttpStatus.BAD_REQUEST, "videoUrl", "Video link must start with http:// or https://");
            }
        } catch (URISyntaxException e) {
            throw fieldError(HttpStatus.BAD_REQUEST, "videoUrl", "Video link is not a valid URL");
        }
        return v;
    }

    private static ApiException fieldError(HttpStatus status, String field, String message) {
        return new ApiException(status, message, Map.of(field, message));
    }
}

package racehorse.backend.audit;

import java.time.Instant;
import java.util.List;

/**
 * Kiểu dữ liệu của trang Audit log (UC: Club Manager theo dõi nhật ký thao tác).
 *
 * Thời gian trả về dạng Instant (có "Z"): AUDIT_LOG.created_at lưu giờ UTC
 * (SYSUTCDATETIME), nên đổi sang Instant ở backend để frontend new Date() đọc
 * đúng múi giờ mà không phải tự thêm "Z".
 */
public final class AuditLog {

    private AuditLog() {
    }

    /** Một dòng nhật ký. userId null = đăng nhập thất bại chưa xác định được tài khoản. */
    public record Row(
            long logId,
            Instant createdAt,
            Integer userId,
            String username,
            String fullName,
            String role,
            String action,
            String targetEntity,
            Integer targetId,
            String result,
            String ipAddress) {
    }

    /** Ba con số an toàn trong 24 giờ qua, hiện ở đầu trang. */
    public record Summary(int failedLogins24h, int deniedAccess24h, int events24h) {
    }

    /**
     * Một trang nhật ký, mới nhất trước.
     * nextBeforeId: truyền lại làm beforeId để tải trang kế; null = hết.
     * summary: chỉ có ở trang đầu (khi không truyền beforeId).
     */
    public record Page(List<Row> items, Long nextBeforeId, Summary summary) {
    }
}

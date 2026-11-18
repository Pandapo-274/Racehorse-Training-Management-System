package racehorse.backend.entity;

import java.time.Instant;
import java.util.List;

/**
 * Kiểu dữ liệu của trang Audit log (UC: Club Manager theo dõi nhật ký thao tác).
 *
 * Thời gian trả về dạng Instant (có "Z"): AUDIT_LOG.created_at lưu giờ UTC
 * (SYSUTCDATETIME), nên đổi sang Instant ở backend để frontend new Date() đọc
 * đúng múi giờ mà không phải tự thêm "Z".
 */
/*
 * Mẫu "class chứa nhóm record": một class final, constructor private (không ai tạo
 * được đối tượng AuditLog), bên trong là các record liên quan đến cùng một chức năng.
 * Gọi từ chỗ khác bằng tên đầy đủ: AuditLog.Row, AuditLog.Page...
 *
 * Package "entity" trong dự án này KHÔNG phải entity JPA (dự án không dùng JPA/Hibernate).
 * Đây chỉ là các record dữ liệu: repository đọc ResultSet từ JDBC rồi dựng record, service
 * trả record ra controller, Jackson tự đổi record thành JSON (tên thành phần record = khoá
 * JSON, vd. logId -> "logId").
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
            String ipAddress,
            /* What changed, e.g. "WON, 100000000 -> PLACED, 40000000". Null for rows before V20. */
            String detail,
            /* Readable name of what the action was on (account name, horse name, plan name...),
               looked up when the log is read; null when the target has no name or was deleted. */
            String targetName) {
    }

    /** Ba con số an toàn trong 24 giờ qua, hiện ở đầu trang. */
    public record Summary(int failedLogins24h, int deniedAccess24h, int events24h) {
    }

    /**
     * Một trang nhật ký, mới nhất trước.
     * nextBeforeId: truyền lại làm beforeId để tải trang kế; null = hết.
     * summary: chỉ có ở trang đầu (khi không truyền beforeId).
     */
    // Phân trang kiểu "keyset" (theo id) thay vì OFFSET: trang sau lấy các dòng có
    // log_id < nextBeforeId, nên không bị trùng/sót dòng khi có nhật ký mới chèn vào giữa.
    public record Page(List<Row> items, Long nextBeforeId, Summary summary) {
    }
}

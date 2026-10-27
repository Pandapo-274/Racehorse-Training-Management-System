package racehorse.backend.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.auth.RequireRole;

/**
 * Club Manager theo dõi nhật ký thao tác hệ thống (Audit Log).
 *
 * CHỈ ĐỌC: không có API sửa hay xoá nhật ký, đó là điều làm nên tính minh bạch.
 * Nhật ký được ghi ở khắp nơi qua UserRepository.writeAudit; request bị chặn vì
 * sai quyền thì AuthInterceptor ghi dòng "ACCESS ..." với kết quả DENIED.
 *
 * GET /api/audit-logs?since=&until=&result=&action=&user=&target=&beforeId=&limit=
 *  - since / until: mốc thời gian ISO-8601 (vd. 2026-10-27T00:00:00Z)
 *  - result: SUCCESS | DENIED
 *  - action: tiền tố mã thao tác (vd. LOGIN, TRAINING_PLAN_, ACCESS)
 *  - user:   tìm trong username hoặc họ tên
 *  - target: loại đối tượng (HORSE, TRAINING_SESSION, APP_USER...)
 *  - beforeId: tải trang kế (lấy từ nextBeforeId của trang trước)
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"CLUB_MANAGER"})
    public AuditLog.Page search(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit) {
        return service.search(since, until, result, action, user, target, beforeId, limit);
    }
}

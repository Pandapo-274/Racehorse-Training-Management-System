package racehorse.backend.permission;

import java.util.List;
import java.util.Map;

/**
 * Kiểu dữ liệu của trang Permissions (UC19, phần xem).
 *
 * Mọi quyền ở đây được ĐỌC từ các @RequireRole đang gắn trên controller lúc
 * backend chạy, không gõ tay. Nhờ vậy trang luôn khớp với thứ hệ thống thực
 * sự chặn: ai sửa @RequireRole thì ma trận tự đổi theo.
 */
public final class PermissionMatrix {

    private PermissionMatrix() {
    }

    /** Năm vai trò, theo thứ tự cột của bảng. */
    public static final List<String> ROLES =
            List.of("CLUB_MANAGER", "HEAD_TRAINER", "VETERINARIAN", "GROOM", "HORSE_OWNER");

    public record Response(
            List<String> roles,
            List<FunctionRow> functions,
            List<EndpointRow> endpoints) {
    }

    /**
     * Một chức năng nghiệp vụ, như người dùng gọi tên nó.
     *
     * access: vai trò -> mức quyền. "ALL" = làm được với mọi dữ liệu;
     * "OWN" = chỉ với dữ liệu của chính mình (ngựa của mình, buổi tập được giao
     * cho mình...). Vai trò không có trong map thì không được làm.
     * endpoint: "METHOD /path" của API thực thi chức năng này.
     */
    public record FunctionRow(
            String area,
            String label,
            String endpoint,
            Map<String, String> access) {
    }

    /**
     * Một API, như hệ thống thấy nó.
     * access: PUBLIC (không cần đăng nhập), SIGNED_IN (đăng nhập là được, không
     * gắn @RequireRole), hoặc ROLES (chỉ các vai trò trong roles).
     */
    public record EndpointRow(
            String method,
            String path,
            String access,
            List<String> roles) {
    }
}

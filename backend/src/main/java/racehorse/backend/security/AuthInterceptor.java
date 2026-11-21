package racehorse.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import racehorse.backend.config.WebConfig;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.UserRepository;

/**
 * Bảo vệ cổng: mọi /api/** (trừ danh sách trong WebConfig) phải có JWT hợp lệ,
 * và nếu method gắn @RequireRole thì role phải nằm trong danh sách cho phép.
 */
/*
 * HandlerInterceptor là "móc" của Spring MVC chạy quanh controller:
 *   preHandle  -> TRƯỚC khi gọi controller (ở đây làm toàn bộ việc xác thực/phân quyền),
 *   postHandle / afterCompletion -> sau đó (dự án không dùng).
 * Trả true = cho đi tiếp; ném ApiException = chặn, GlobalExceptionHandler trả JSON lỗi.
 *
 * Thứ tự kiểm tra trong preHandle (dừng ở bước đầu tiên thất bại):
 *   1. OPTIONS (preflight CORS)              -> cho qua luôn.
 *   2. Có header "Authorization: Bearer ..."?  -> không có: 401.
 *   3. JWT đúng chữ ký, chưa hết hạn?          -> sai: 401.
 *   4. Token có bị thu hồi trong RAM không?    -> có: 401 (đăng xuất / đổi mật khẩu).
 *   5. Đọc tài khoản trong DB: còn tồn tại, ACTIVE, token cấp sau tokens_valid_after,
 *      vai trò trong DB trùng vai trò trong token -> sai: 401.
 *   6. Đang dùng mật khẩu tạm? chỉ được gọi 3 API đổi mật khẩu/me/logout -> sai: 428.
 *   7. @RequireRole trên method (ưu tiên) hoặc trên class: vai trò có trong danh sách?
 *      -> không: ghi audit "DENIED" rồi 403.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    // Tên attribute để controller lấy người đang đăng nhập: @RequestAttribute("authUser").
    public static final String USER_ATTR = "authUser";

    /** Những API vẫn gọi được khi tài khoản còn phải đổi mật khẩu tạm. */
    private static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of(
            "/api/auth/change-password", "/api/auth/me", "/api/auth/logout");

    private final JwtService jwt;
    private final UserRepository users;
    private final TokenBlacklist blacklist;

    public AuthInterceptor(JwtService jwt, UserRepository users, TokenBlacklist blacklist) {
        this.jwt = jwt;
        this.users = users;
        this.blacklist = blacklist;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        // Trình duyệt gửi OPTIONS (preflight) trước request cross-origin và không kèm token.
        if ("OPTIONS".equals(req.getMethod())) return true;

        // Frontend (authFetch trong authService.js) gửi header: "Authorization: Bearer <jwt>".
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        // substring(7) bỏ tiền tố "Bearer " (7 ký tự). parse() trả Optional rỗng nếu token
        // bị sửa, sai khoá ký, hết hạn hoặc không phải JWT.
        AuthenticatedUser user = jwt.parse(header.substring(7).trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "Invalid or expired token"));
        // Kiểm sổ đen trong RAM: token đã logout (theo jti) hoặc cấp trước mốc "đăng xuất
        // mọi thiết bị" của user này.
        if (blacklist.isRevoked(user.tokenId())
                || blacklist.isIssuedBeforeCutoff(user.userId(), user.issuedAt())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token has been revoked, please log in again");
        }
        /* Kiểm tài khoản ở MỖI request, không chỉ dựa vào TokenBlacklist: danh
           sách đó nằm trong bộ nhớ nên mất khi backend khởi động lại, và mốc thu
           hồi tính theo giây nên token cấp cùng giây với lúc bị khoá vẫn lọt. Nếu
           không, nhân viên bị khoá hoặc bị đổi vai trò vẫn dùng token cũ được tới
           8 tiếng. Câu query này thay câu đọc must_change_password cũ, nên mỗi
           request vẫn chỉ hỏi database một lần như trước. */
        // AccessState = (status, roleName, mustChangePassword, tokensValidAfter) đọc từ
        // APP_USER JOIN ROLE. Đây là câu SQL duy nhất mà mọi request đều chạy.
        UserRepository.AccessState account = users.findAccessState(user.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        if (!"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This account is suspended");
        }
        if (account.tokensValidAfter() != null && user.issuedAt().isBefore(account.tokensValidAfter())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token has been revoked, please log in again");
        }
        // Token mang role lúc đăng nhập; nếu Manager đã đổi role, buộc đăng nhập lại để
        // token mới mang đúng role (tránh Groom cũ vẫn giữ quyền Groom).
        if (!account.roleName().equals(user.role())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Your role has changed, please log in again");
        }
        // Lưu người dùng vào request để controller đọc qua @RequestAttribute(USER_ATTR).
        req.setAttribute(USER_ATTR, user);

        /* V14: tài khoản đang dùng mật khẩu tạm do Club Manager đặt chỉ được đổi
           mật khẩu (và xem /me, đăng xuất). Chặn ở đây chứ không chỉ ở frontend:
           Manager biết mật khẩu tạm, nên chỉ chặn trên giao diện thì người khác
           cầm mật khẩu đó vẫn gọi thẳng API được. 428 = phải làm một việc trước. */
        if (!PASSWORD_CHANGE_ALLOWED.contains(req.getRequestURI())
                && account.mustChangePassword()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,
                    "Please change your temporary password before continuing");
        }

        // handler là method controller sẽ được gọi. Với static resource (không phải
        // HandlerMethod) thì bỏ qua bước kiểm role.
        if (handler instanceof HandlerMethod hm) {
            RequireRole rule = hm.getMethodAnnotation(RequireRole.class);
            if (rule == null) rule = hm.getBeanType().getAnnotation(RequireRole.class);
            // Không có @RequireRole = chỉ cần đăng nhập. Có thì role phải nằm trong danh sách.
            if (rule != null && !Arrays.asList(rule.value()).contains(user.role())) {
                String action = ("ACCESS " + req.getMethod() + " " + req.getRequestURI());
                // Cột AUDIT_LOG.action giới hạn 100 ký tự, cắt bớt để INSERT không lỗi.
                if (action.length() > 100) action = action.substring(0, 100);
                users.writeAudit(user.userId(), action, null, null, "DENIED", req.getRemoteAddr());
                throw new ApiException(HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
            }
        }
        return true;
    }
}

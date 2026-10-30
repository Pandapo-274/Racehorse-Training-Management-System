package racehorse.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Bảo vệ cổng: mọi /api/** (trừ danh sách trong WebConfig) phải có JWT hợp lệ,
 * và nếu method gắn @RequireRole thì role phải nằm trong danh sách cho phép.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

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
        if ("OPTIONS".equals(req.getMethod())) return true;

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        AuthenticatedUser user = jwt.parse(header.substring(7).trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "Invalid or expired token"));
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
        UserRepository.AccessState account = users.findAccessState(user.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        if (!"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This account is suspended");
        }
        if (!account.roleName().equals(user.role())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Your role has changed, please log in again");
        }
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

        if (handler instanceof HandlerMethod hm) {
            RequireRole rule = hm.getMethodAnnotation(RequireRole.class);
            if (rule == null) rule = hm.getBeanType().getAnnotation(RequireRole.class);
            if (rule != null && !Arrays.asList(rule.value()).contains(user.role())) {
                String action = ("ACCESS " + req.getMethod() + " " + req.getRequestURI());
                if (action.length() > 100) action = action.substring(0, 100);
                users.writeAudit(user.userId(), action, null, null, "DENIED", req.getRemoteAddr());
                throw new ApiException(HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
            }
        }
        return true;
    }
}

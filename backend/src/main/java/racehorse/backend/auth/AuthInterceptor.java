package racehorse.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
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
        if (blacklist.isRevoked(user.tokenId())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token has been revoked, please log in again");
        }
        req.setAttribute(USER_ATTR, user);

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

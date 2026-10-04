package racehorse.backend.auth;

/** Người đang gọi API, lấy ra từ JWT. Controller nhận qua @RequestAttribute("authUser"). */
public record AuthenticatedUser(int userId, String username, String role) {}

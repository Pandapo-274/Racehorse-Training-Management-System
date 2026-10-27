package racehorse.backend.auth;

/** Giống LoginResponse, thêm "outcome" để frontend biết chuyện gì vừa xảy ra: LOGIN, REGISTERED hoặc LINKED. */
public record GoogleLoginResponse(String token, String tokenType, long expiresIn, UserInfo user, String outcome) {}

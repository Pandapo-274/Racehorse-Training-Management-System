package racehorse.backend.auth;

public record LoginResponse(String token, String tokenType, long expiresIn, UserInfo user) {}

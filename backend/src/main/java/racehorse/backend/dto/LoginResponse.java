package racehorse.backend.dto;

public record LoginResponse(String token, String tokenType, long expiresIn, UserInfo user) {}

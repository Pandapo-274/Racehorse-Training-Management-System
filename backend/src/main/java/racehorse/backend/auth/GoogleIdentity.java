package racehorse.backend.auth;

/** Danh tính đã được Google xác nhận. */
public record GoogleIdentity(String sub, String email, String name) {}

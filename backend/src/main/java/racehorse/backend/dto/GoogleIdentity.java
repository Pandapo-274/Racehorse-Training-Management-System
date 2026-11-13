package racehorse.backend.dto;

/** Danh tính đã được Google xác nhận. */
public record GoogleIdentity(String sub, String email, String name) {}

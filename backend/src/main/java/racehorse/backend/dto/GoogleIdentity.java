package racehorse.backend.dto;

/** Danh tính đã được Google xác nhận. */
// sub: mã định danh vĩnh viễn của tài khoản Google; email: email đã được Google xác minh;
// name: tên hiển thị (có thể null). Do GoogleTokenVerifier dựng ra.
public record GoogleIdentity(String sub, String email, String name) {}

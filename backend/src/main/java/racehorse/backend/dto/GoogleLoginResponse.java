package racehorse.backend.dto;

/** Giống LoginResponse, thêm "outcome" để frontend biết chuyện gì vừa xảy ra: LOGIN, REGISTERED hoặc LINKED. */
// outcome: LOGIN (đã có tài khoản liên kết), REGISTERED (vừa tạo tài khoản mới),
// LINKED (gắn Google vào tài khoản sẵn có cùng email). Frontend hiện thông báo tương ứng.
public record GoogleLoginResponse(String token, String tokenType, long expiresIn, UserInfo user, String outcome) {}

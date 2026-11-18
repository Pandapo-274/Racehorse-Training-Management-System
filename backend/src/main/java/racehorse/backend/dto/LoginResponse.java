package racehorse.backend.dto;

/*
 * Kết quả đăng nhập thành công, ví dụ:
 *   { "token": "eyJhbGciOi...", "tokenType": "Bearer", "expiresIn": 28800,
 *     "user": { "userId": 5, "username": "binh.lv", "role": "GROOM", ... } }
 * Frontend (authService.js) lưu token và user vào localStorage rồi chuyển tới dashboard
 * theo user.role.
 */
public record LoginResponse(String token, String tokenType, long expiresIn, UserInfo user) {}

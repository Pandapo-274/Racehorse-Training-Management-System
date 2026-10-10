package racehorse.backend.auth;

/** Thông tin user an toàn để trả cho frontend. Field "role" khớp với user.role mà frontend đang dùng. */
public record UserInfo(int userId, String username, String fullName, String email, String role,
                       /* Thiếu trường này là nguồn của lỗi "avatar đổi xong lại mất":
                          frontend lưu nguyên khối user trả về đây vào localStorage, nên
                          mỗi lần đăng nhập lại là ghi đè mất avatarUrl mà màn hồ sơ vừa
                          ghi vào. Ảnh vẫn nằm nguyên trong cơ sở dữ liệu - chỉ là trình
                          duyệt không còn biết đường dẫn. */
                       String avatarUrl) {}

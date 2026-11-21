package racehorse.backend.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gắn lên controller hoặc method để giới hạn vai trò được gọi.
 * Ví dụ: @RequireRole({"CLUB_MANAGER"}). Không gắn = chỉ cần đăng nhập.
 */
/*
 * Annotation tự định nghĩa (không phải của Spring Security).
 *   @Target METHOD, TYPE  -> gắn được lên method hoặc cả class controller.
 *   @Retention RUNTIME    -> giữ lại lúc chạy để AuthInterceptor đọc bằng reflection
 *                            (hm.getMethodAnnotation / getBeanType().getAnnotation).
 * Annotation trên method GHI ĐÈ annotation trên class (không cộng dồn).
 * PermissionService cũng đọc các annotation này để vẽ ma trận quyền trên trang
 * Permissions, nên ma trận luôn khớp với điều backend thực sự cho phép.
 *
 * Lưu ý: @RequireRole chỉ kiểm theo VAI TRÒ. Kiểm theo DỮ LIỆU (chủ ngựa chỉ xem ngựa
 * của mình, Groom chỉ bấm buổi tập được giao cho mình) nằm trong từng service.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    String[] value();
}

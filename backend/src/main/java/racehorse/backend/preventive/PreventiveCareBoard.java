package racehorse.backend.preventive;

import java.util.List;
import java.util.Map;

/**
 * Màn Preventive Care Reminders.
 *
 * counts đếm trên TOÀN bộ bảng, không phải trên phần items đã lọc - cùng cách
 * HealthRepository.countByStatus của UC13 làm. Nếu đếm sau khi lọc thì con số
 * "12 quá hạn" sẽ đổi theo bộ lọc người dùng đang bật, và một màn nhắc việc
 * mà con số cứ nhảy thì không nhắc được gì.
 */
public record PreventiveCareBoard(
        Map<String, Integer> counts,
        List<PreventiveCareItem> items
) {}

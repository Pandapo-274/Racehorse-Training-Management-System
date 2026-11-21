package racehorse.backend.dto;

import java.util.List;
import java.util.Map;

/*
 * Kết quả GET /api/health/overview (UC13 - bảng sức khoẻ đàn ngựa).
 *   totalHorses - số ngựa đang hiển thị
 *   summary     - đếm theo trạng thái, vd. {"ELIGIBLE": 20, "INJURED": 2, ...}
 *   horses      - từng ô chuồng (HorseHealthItem)
 */
public record HealthOverviewResponse(int totalHorses, Map<String, Integer> summary, List<HorseHealthItem> horses) {}

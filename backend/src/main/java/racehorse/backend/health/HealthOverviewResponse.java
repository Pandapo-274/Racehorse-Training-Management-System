package racehorse.backend.health;

import java.util.List;
import java.util.Map;

public record HealthOverviewResponse(int totalHorses, Map<String, Integer> summary, List<HorseHealthItem> horses) {}

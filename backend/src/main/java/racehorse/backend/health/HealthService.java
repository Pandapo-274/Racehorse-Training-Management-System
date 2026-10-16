package racehorse.backend.health;

import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import racehorse.backend.auth.ApiException;

@Service
public class HealthService {

    private static final Set<String> STATUSES = Set.of("ELIGIBLE", "MONITOR", "INJURED", "QUARANTINE");

    private final HealthRepository repo;

    public HealthService(HealthRepository repo) {
        this.repo = repo;
    }

    public HealthOverviewResponse overview(String status) {
        String filter = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        if (filter != null && !STATUSES.contains(filter)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status filter",
                    Map.of("status", "Must be one of " + STATUSES));
        }
        Map<String, Integer> summary = repo.countByStatus();
        int total = summary.values().stream().mapToInt(Integer::intValue).sum();
        return new HealthOverviewResponse(total, summary, repo.findHorses(filter));
    }
}

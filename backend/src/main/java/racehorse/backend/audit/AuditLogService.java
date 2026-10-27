package racehorse.backend.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import racehorse.backend.auth.ApiException;

@Service
public class AuditLogService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final Set<String> RESULTS = Set.of("SUCCESS", "DENIED");

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public AuditLog.Page search(String since, String until, String result, String action,
                                String user, String target, Long beforeId, Integer limit) {
        String res = blankToNull(result);
        if (res != null) {
            res = res.toUpperCase(Locale.ROOT);
            if (!RESULTS.contains(res)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid result filter",
                        Map.of("result", "Must be SUCCESS or DENIED"));
            }
        }
        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        AuditLogRepository.Filter f = new AuditLogRepository.Filter(
                parseInstant("since", since), parseInstant("until", until), res,
                upper(blankToNull(action)), blankToNull(user), upper(blankToNull(target)), beforeId);

        // Lấy dư một dòng để biết còn trang sau hay không.
        List<AuditLog.Row> rows = repository.search(f, n + 1);
        Long next = null;
        if (rows.size() > n) {
            rows = rows.subList(0, n);
            next = rows.get(n - 1).logId();
        }
        AuditLog.Summary summary = beforeId == null ? repository.summary() : null;
        return new AuditLog.Page(rows, next, summary);
    }

    private static Instant parseInstant(String field, String raw) {
        String v = blankToNull(raw);
        if (v == null) return null;
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid time filter",
                    Map.of(field, "Use an ISO-8601 instant, e.g. 2026-10-27T00:00:00Z"));
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}

package racehorse.backend.permission;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import racehorse.backend.auth.RequireRole;
import racehorse.backend.auth.WebConfig;

/**
 * Dựng ma trận quyền từ chính các controller đang chạy.
 *
 * Cách làm: hỏi Spring danh sách mọi API (RequestMappingHandlerMapping), đọc
 * @RequireRole trên method hoặc trên class giống hệt cách AuthInterceptor đọc,
 * và đối chiếu với WebConfig.PUBLIC_PATHS. Không có bảng quyền nào gõ tay:
 * PermissionCatalog chỉ đặt TÊN cho chức năng, còn vai trò nào được làm thì
 * luôn lấy từ annotation.
 */
@Service
public class PermissionService {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RequestMappingHandlerMapping mappings;

    public PermissionService(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    public PermissionMatrix.Response load() {
        List<PermissionMatrix.EndpointRow> endpoints = scanEndpoints();

        Map<String, PermissionMatrix.EndpointRow> byKey = new LinkedHashMap<>();
        endpoints.forEach(e -> byKey.put(e.method() + " " + e.path(), e));

        List<PermissionMatrix.FunctionRow> functions = new ArrayList<>();
        for (PermissionCatalog.Entry f : PermissionCatalog.ENTRIES) {
            PermissionMatrix.EndpointRow e = byKey.get(f.endpoint());
            // Chức năng chưa có API (vd. nhánh chưa merge) thì không hiện,
            // thay vì hiện một dòng toàn dấu gạch như thể không ai được làm.
            if (e == null) continue;
            functions.add(new PermissionMatrix.FunctionRow(f.area(), f.label(), f.endpoint(),
                    accessOf(e, f.ownOnly())));
        }
        return new PermissionMatrix.Response(PermissionMatrix.ROLES, functions, endpoints);
    }

    /** Mọi API dưới /api, sắp theo đường dẫn rồi theo phương thức. */
    private List<PermissionMatrix.EndpointRow> scanEndpoints() {
        List<PermissionMatrix.EndpointRow> rows = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> m : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = m.getKey();
            HandlerMethod handler = m.getValue();

            RequireRole rule = handler.getMethodAnnotation(RequireRole.class);
            if (rule == null) rule = handler.getBeanType().getAnnotation(RequireRole.class);

            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/")) continue;
                String access;
                List<String> roles;
                if (isPublic(path)) {
                    access = "PUBLIC";
                    roles = List.of();
                } else if (rule == null) {
                    access = "SIGNED_IN";
                    roles = PermissionMatrix.ROLES;
                } else {
                    access = "ROLES";
                    roles = PermissionMatrix.ROLES.stream()
                            .filter(Arrays.asList(rule.value())::contains)
                            .toList();
                }
                if (methods.isEmpty()) {
                    rows.add(new PermissionMatrix.EndpointRow("ANY", path, access, roles));
                } else {
                    for (RequestMethod rm : methods) {
                        rows.add(new PermissionMatrix.EndpointRow(rm.name(), path, access, roles));
                    }
                }
            }
        }
        rows.sort(Comparator.comparing(PermissionMatrix.EndpointRow::path)
                .thenComparing(PermissionMatrix.EndpointRow::method));
        return rows;
    }

    private static boolean isPublic(String path) {
        for (String p : WebConfig.PUBLIC_PATHS) {
            if (MATCHER.match(p, path)) return true;
        }
        return false;
    }

    /**
     * Vai trò nào được làm (từ annotation), và với vai trò nào thì chỉ trên dữ
     * liệu của chính mình (từ catalog: luật này nằm trong service, annotation
     * không nói lên được).
     */
    private static Map<String, String> accessOf(PermissionMatrix.EndpointRow e, Set<String> ownOnly) {
        Map<String, String> access = new LinkedHashMap<>();
        List<String> allowed = "PUBLIC".equals(e.access()) ? PermissionMatrix.ROLES : e.roles();
        for (String role : allowed) {
            access.put(role, ownOnly.contains(role) ? "OWN" : "ALL");
        }
        return access;
    }
}

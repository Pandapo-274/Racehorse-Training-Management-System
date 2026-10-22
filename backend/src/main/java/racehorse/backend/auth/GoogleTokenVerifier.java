package racehorse.backend.auth;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hỏi Google xem ID token có thật không (endpoint tokeninfo), rồi tự kiểm tra thêm:
 *  - aud: token phải được cấp cho ĐÚNG app của mình (chặn token Google cấp cho app khác),
 *  - iss: do Google phát hành,
 *  - email_verified: Google xác nhận người này sở hữu email.
 * Google khuyên production nên tự kiểm tra chữ ký bằng khoá công khai (JWKS) để khỏi gọi mạng mỗi lần.
 * Với đồ án thì tokeninfo là đủ; muốn nâng cấp chỉ cần sửa class này, nơi khác không đổi.
 */
@Component
public class GoogleTokenVerifier {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token={t}";

    private final RestClient http;
    private final String clientId;

    public GoogleTokenVerifier(@Value("${app.google.client-id:}") String clientId) {
        this.clientId = clientId;
        // Có timeout để Google chậm/mất mạng thì request không treo mãi.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public GoogleIdentity verify(String idToken) {
        if (clientId == null || clientId.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google login is not configured on the server");
        }

        Map<String, Object> claims;
        try {
            claims = http.get().uri(TOKENINFO_URL, idToken).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientResponseException e) {
            // Google trả 400 khi token sai chữ ký, hết hạn hoặc là rác.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid or expired Google token");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach Google to verify the token");
        }

        if (claims == null || !clientId.equals(str(claims.get("aud")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Google token was not issued for this application");
        }
        String iss = str(claims.get("iss"));
        if (!"accounts.google.com".equals(iss) && !"https://accounts.google.com".equals(iss)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid Google token issuer");
        }
        if (!"true".equalsIgnoreCase(str(claims.get("email_verified")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Google email is not verified");
        }
        String sub = str(claims.get("sub"));
        String email = str(claims.get("email"));
        if (sub == null || sub.isBlank() || email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Google token is missing required information");
        }
        return new GoogleIdentity(sub, email, str(claims.get("name")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

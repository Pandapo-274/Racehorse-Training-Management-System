package racehorse.backend.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.UserRepository;

@Service
public class ProfileService {

    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024; // 2 MB
    private static final String AVATAR_URL_PREFIX = "/uploads/avatars/";

    private final ProfileRepository profiles;
    private final UserRepository users; // dùng lại writeAudit của UC1
    private final Path avatarDir;

    public ProfileService(ProfileRepository profiles, UserRepository users,
                          @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.profiles = profiles;
        this.users = users;
        this.avatarDir = Path.of(uploadDir).toAbsolutePath().normalize().resolve("avatars");
    }

    public ProfileResponse getProfile(int userId) {
        return profiles.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    /** @Transactional: update + audit cùng thành công hoặc cùng rollback. */
    @Transactional
    public ProfileResponse updateProfile(int userId, UpdateProfileRequest req, String ip) {
        String fullName = req.fullName().trim();
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String phone = (req.phone() == null || req.phone().isBlank()) ? null : req.phone().trim();

        if (profiles.emailTakenByOther(email, userId)) throw emailConflict();
        try {
            profiles.updateBasic(userId, fullName, email, phone);
        } catch (DuplicateKeyException e) {
            throw emailConflict(); // UNIQUE của DB là chốt chặn cuối khi 2 người đổi cùng lúc
        }
        users.writeAudit(userId, "UPDATE_PROFILE", "APP_USER", userId, "SUCCESS", ip);
        return getProfile(userId);
    }

    public ProfileResponse uploadAvatar(int userId, MultipartFile file, String ip) {
        if (file == null || file.isEmpty()) {
            throw badFile("Image file is required");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large (max 2 MB)",
                    Map.of("file", "File is too large (max 2 MB)"));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the uploaded file");
        }
        // Đoán loại file bằng "chữ ký" ở đầu file, KHÔNG tin tên file hay Content-Type client gửi (dễ giả mạo).
        String ext = detectExtension(bytes);
        if (ext == null) {
            throw badFile("Only JPEG, PNG or WebP images are allowed");
        }

        // Tên file do server tự đặt => không thể bị path traversal kiểu "../../x".
        String filename = userId + "-" + UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(avatarDir);
            Files.write(avatarDir.resolve(filename), bytes);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the uploaded file");
        }

        String newUrl = AVATAR_URL_PREFIX + filename;
        Optional<String> oldUrl = profiles.findAvatarUrl(userId);
        try {
            profiles.updateAvatar(userId, newUrl);
        } catch (RuntimeException e) {
            deleteQuietly(newUrl); // DB lỗi thì dọn file vừa ghi, khỏi để file mồ côi
            throw e;
        }
        oldUrl.ifPresent(this::deleteQuietly); // xoá ảnh cũ cho đỡ rác ổ đĩa
        users.writeAudit(userId, "UPLOAD_AVATAR", "APP_USER", userId, "SUCCESS", ip);
        return getProfile(userId);
    }

    private static String detectExtension(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private void deleteQuietly(String url) {
        try {
            String name = url.substring(url.lastIndexOf('/') + 1);
            Path p = avatarDir.resolve(name).normalize();
            if (p.startsWith(avatarDir)) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) {
            // dọn rác thất bại thì thôi, không được làm hỏng request chính
        }
    }

    private ApiException emailConflict() {
        return new ApiException(HttpStatus.CONFLICT, "Email is already registered",
                Map.of("email", "Email is already registered"));
    }

    private ApiException badFile(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("file", msg));
    }
}

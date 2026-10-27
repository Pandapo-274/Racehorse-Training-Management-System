package racehorse.backend.horse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.upload.ImageFiles;

/**
 * Ảnh ngựa. Chỉ chủ ngựa đổi được, và chỉ ngựa của mình - cùng luật với
 * HorseService.updateByOwner.
 *
 * Làm giống avatar ở ProfileService: file nằm trong {app.upload.dir}/horses,
 * được ProfileWebConfig phục vụ qua /uploads/**, DB chỉ giữ đường dẫn.
 */
@Service
public class HorsePhotoService {

    private static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024; // khớp spring.servlet.multipart.max-file-size
    private static final String PHOTO_URL_PREFIX = "/uploads/horses/";

    private final HorseService horseService;
    private final HorseRepository horseRepository;
    private final UserRepository userRepository;
    private final Path photoDir;

    public HorsePhotoService(HorseService horseService, HorseRepository horseRepository,
                             UserRepository userRepository,
                             @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.horseService = horseService;
        this.horseRepository = horseRepository;
        this.userRepository = userRepository;
        this.photoDir = Path.of(uploadDir).toAbsolutePath().normalize().resolve("horses");
    }

    public HorseResponse upload(int horseId, MultipartFile file, AuthenticatedUser user, String ip) {
        HorseResponse horse = requireOwner(horseId, user);

        if (file == null || file.isEmpty()) {
            throw badFile("Image file is required");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large (max 2 MB)",
                    Map.of("file", "File is too large (max 2 MB)"));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the uploaded file");
        }
        String ext = ImageFiles.detectExtension(bytes);
        if (ext == null) {
            throw badFile("Only JPEG, PNG or WebP images are allowed");
        }

        // Tên file do server tự đặt => không thể bị path traversal kiểu "../../x".
        String filename = horseId + "-" + UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(photoDir);
            Files.write(photoDir.resolve(filename), bytes);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the uploaded file");
        }

        String newUrl = PHOTO_URL_PREFIX + filename;
        try {
            horseRepository.updatePhoto(horseId, newUrl);
        } catch (RuntimeException e) {
            deleteQuietly(newUrl); // DB lỗi thì dọn file vừa ghi, khỏi để file mồ côi
            throw e;
        }
        deleteQuietly(horse.photoUrl()); // xoá ảnh cũ cho đỡ rác ổ đĩa
        userRepository.writeAudit(user.userId(), "HORSE_PHOTO_UPLOAD", "HORSE", horseId, "SUCCESS", ip);
        return horseService.getOrThrow(horseId);
    }

    public HorseResponse remove(int horseId, AuthenticatedUser user, String ip) {
        HorseResponse horse = requireOwner(horseId, user);
        if (horse.photoUrl() == null) {
            return horse;
        }
        horseRepository.updatePhoto(horseId, null);
        deleteQuietly(horse.photoUrl());
        userRepository.writeAudit(user.userId(), "HORSE_PHOTO_REMOVE", "HORSE", horseId, "SUCCESS", ip);
        return horseService.getOrThrow(horseId);
    }

    /* ---------- helper ---------- */

    private HorseResponse requireOwner(int horseId, AuthenticatedUser user) {
        HorseResponse horse = horseService.getOrThrow(horseId);
        if (!"HORSE_OWNER".equals(user.role()) || horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only change photos of your own horses");
        }
        return horse;
    }

    private void deleteQuietly(String url) {
        if (url == null) return;
        try {
            String name = url.substring(url.lastIndexOf('/') + 1);
            Path p = photoDir.resolve(name).normalize();
            if (p.startsWith(photoDir)) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) {
            // dọn rác thất bại thì thôi, không được làm hỏng request chính
        }
    }

    private ApiException badFile(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg, Map.of("file", msg));
    }
}

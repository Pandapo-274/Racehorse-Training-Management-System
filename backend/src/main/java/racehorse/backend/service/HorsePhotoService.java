package racehorse.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import racehorse.backend.config.ProfileWebConfig;
import racehorse.backend.dto.HorseResponse;
import racehorse.backend.exception.ApiException;
import racehorse.backend.entity.HorseRequestRow;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.HorseRequestRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.util.ImageFiles;

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
    private final HorseRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final Path photoDir;

    public HorsePhotoService(HorseService horseService, HorseRepository horseRepository,
                             HorseRequestRepository requestRepository,
                             UserRepository userRepository,
                             @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.horseService = horseService;
        this.horseRepository = horseRepository;
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.photoDir = Path.of(uploadDir).toAbsolutePath().normalize().resolve("horses");
    }

    // Cùng quy trình với ProfileService.uploadAvatar: kiểm quyền chủ -> kiểm kích thước -> nhận
    // dạng ảnh bằng magic bytes -> ghi file tên ngẫu nhiên -> cập nhật DB -> xoá ảnh cũ.
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

    /* ---------- V48: photo of a registration request ----------
     * Review feedback: "a photo is needed before approval". The owner attaches it
     * to the request while it waits; the manager sees it on the review page, and
     * HorseRequestService.approve refuses a club horse without one. */

    public HorseRequestRow uploadRequestPhoto(int requestId, MultipartFile file, AuthenticatedUser user, String ip) {
        HorseRequestRow req = requestRepository.findById(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Request #" + requestId + " does not exist"));
        if (!"HORSE_OWNER".equals(user.role()) || req.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only add photos to your own requests");
        }
        if (!"PENDING".equals(req.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "The club has already handled this request");
        }
        byte[] bytes = readImage(file);
        String ext = ImageFiles.detectExtension(bytes);
        String filename = "req" + requestId + "-" + UUID.randomUUID() + "." + ext;
        String newUrl = store(filename, bytes);
        if (requestRepository.updatePhoto(requestId, user.userId(), newUrl) == 0) {
            deleteQuietly(newUrl);
            throw new ApiException(HttpStatus.CONFLICT, "The club has already handled this request");
        }
        deleteQuietly(req.photoUrl());
        userRepository.writeAudit(user.userId(), "HORSE_REQUEST_PHOTO", "HORSE_REQUEST", requestId, "SUCCESS", ip);
        return requestRepository.findById(requestId).orElseThrow();
    }

    /**
     * The request's photo becomes the horse's photo when the horse is received. It is
     * COPIED, not shared: the owner may later replace the horse photo, and upload()
     * deletes the old file, which would leave the request history without its picture.
     */
    public void copyRequestPhotoToHorse(String requestPhotoUrl, int horseId) {
        if (requestPhotoUrl == null) return;
        if (!requestPhotoUrl.startsWith(PHOTO_URL_PREFIX)) {
            // A seeded demo photo (/seed/horses/...) is a static file nobody deletes: share it.
            horseRepository.updatePhoto(horseId, requestPhotoUrl);
            return;
        }
        String name = requestPhotoUrl.substring(requestPhotoUrl.lastIndexOf('/') + 1);
        Path source = photoDir.resolve(name).normalize();
        if (!source.startsWith(photoDir) || !Files.exists(source)) return;
        String ext = name.substring(name.lastIndexOf('.') + 1);
        String filename = horseId + "-" + UUID.randomUUID() + "." + ext;
        try {
            Files.copy(source, photoDir.resolve(filename));
        } catch (IOException e) {
            return; // a missing photo must not block receiving the horse; the owner can upload again
        }
        horseRepository.updatePhoto(horseId, PHOTO_URL_PREFIX + filename);
    }

    private byte[] readImage(MultipartFile file) {
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
        if (ImageFiles.detectExtension(bytes) == null) {
            throw badFile("Only JPEG, PNG or WebP images are allowed");
        }
        return bytes;
    }

    private String store(String filename, byte[] bytes) {
        try {
            Files.createDirectories(photoDir);
            Files.write(photoDir.resolve(filename), bytes);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the uploaded file");
        }
        return PHOTO_URL_PREFIX + filename;
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

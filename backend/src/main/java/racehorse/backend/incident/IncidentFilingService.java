package racehorse.backend.incident;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorsePolicy;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.upload.ImageFiles;

/**
 * Flow 4 - Groom báo sự cố ngay tại chuồng (không gắn buổi tập): bỏ ăn, đau bụng,
 * sốt, xước móng... kèm ảnh nếu có.
 *
 * Báo cáo vào cùng hàng chờ khám của Vet như báo cáo khi dừng buổi tập, nên
 * IncidentGuard cũng chặn tập cho tới khi bác sĩ khám xong.
 * Ảnh lưu giống ảnh ngựa: {app.upload.dir}/incidents, phục vụ qua /uploads/**.
 */
@Service
public class IncidentFilingService {

    private static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024;
    private static final String PHOTO_URL_PREFIX = "/uploads/incidents/";
    private static final Set<String> URGENCY = Set.of("HIGH", "MEDIUM", "LOW");

    private final IncidentRepository incidents;
    private final HorseRepository horses;
    private final UserRepository users;
    private final NotificationService notifications;
    private final Path photoDir;

    public IncidentFilingService(IncidentRepository incidents, HorseRepository horses, UserRepository users,
                                 NotificationService notifications,
                                 @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.incidents = incidents;
        this.horses = horses;
        this.users = users;
        this.notifications = notifications;
        this.photoDir = Path.of(uploadDir).toAbsolutePath().normalize().resolve("incidents");
    }

    @Transactional
    public IncidentReportResponse file(int horseId, String symptoms, String description, String urgency,
                                       MultipartFile photo, AuthenticatedUser user, String ip) {
        String s = symptoms == null ? "" : symptoms.trim();
        String u = urgency == null ? "" : urgency.trim().toUpperCase();
        String d = description == null || description.isBlank() ? null : description.trim();
        if (s.isEmpty() || s.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid report",
                    Map.of("symptoms", s.isEmpty() ? "Symptoms are required" : "Symptoms must be at most 255 characters"));
        }
        if (!URGENCY.contains(u)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid report",
                    Map.of("urgency", "Urgency must be HIGH, MEDIUM or LOW"));
        }
        if (d != null && d.length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid report",
                    Map.of("description", "Description must be at most 2000 characters"));
        }

        HorseResponse horse = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + horseId + " not found"));
        if (HorsePolicy.PEDIGREE.equals(HorsePolicy.normalizeType(horse.recordType())) || horse.isWithdrawn()) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " is not stabled at the club");
        }

        String photoUrl = photo == null || photo.isEmpty() ? null : storePhoto(horseId, photo);
        int reportId;
        try {
            reportId = incidents.insertStandaloneReport(horseId, user.userId(), s, d, u, photoUrl);
        } catch (RuntimeException e) {
            deleteQuietly(photoUrl); // DB lỗi thì dọn ảnh vừa ghi
            throw e;
        }
        users.writeAudit(user.userId(), "INCIDENT_REPORT_CREATE", "INCIDENT_REPORT", reportId, "SUCCESS", ip);

        notifications.notifyRole("VETERINARIAN", user, new Notice(
                "INCIDENT_REPORTED", "Horse needs examination",
                Text.clip(horse.horseName() + ": " + s + " (" + u + ").", 500),
                Notice.HORSE, horseId));
        notifications.notifyHorseWatchers(horse.ownerId(), user, new Notice(
                "INCIDENT_REPORTED", "Incident reported",
                Text.clip(horse.horseName() + " was reported at the stable: " + s
                        + ". Training is paused until a veterinarian examines it.", 500),
                Notice.HORSE, horseId));

        return incidents.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The report was created but could not be read back"));
    }

    private String storePhoto(int horseId, MultipartFile file) {
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "File is too large (max 2 MB)",
                    Map.of("photo", "File is too large (max 2 MB)"));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the uploaded file");
        }
        String ext = ImageFiles.detectExtension(bytes);
        if (ext == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid photo",
                    Map.of("photo", "Only JPEG, PNG or WebP images are allowed"));
        }
        // Tên file do server đặt nên không thể bị path traversal.
        String filename = horseId + "-" + UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(photoDir);
            Files.write(photoDir.resolve(filename), bytes);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the uploaded file");
        }
        return PHOTO_URL_PREFIX + filename;
    }

    private void deleteQuietly(String url) {
        if (url == null || !url.startsWith(PHOTO_URL_PREFIX)) return;
        try {
            Path p = photoDir.resolve(url.substring(PHOTO_URL_PREFIX.length())).normalize();
            if (p.startsWith(photoDir)) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) {
            // Ảnh mồ côi không ảnh hưởng nghiệp vụ.
        }
    }
}

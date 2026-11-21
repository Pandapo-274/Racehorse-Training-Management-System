package racehorse.backend.util;

import racehorse.backend.service.HorsePhotoService;
import racehorse.backend.service.ProfileService;

/**
 * Nhận dạng ảnh tải lên, dùng chung cho avatar (ProfileService) và ảnh ngựa
 * (HorsePhotoService) để hai nơi luôn nhận cùng một loại file.
 *
 * Đoán loại file bằng "chữ ký" ở đầu file, KHÔNG tin tên file hay Content-Type
 * client gửi (dễ giả mạo). Frontend kiểm cùng tiêu chuẩn trong profileApi.js
 * (detectImageType).
 */
public final class ImageFiles {

    private ImageFiles() {
    }

    /** @return "jpg", "png", "webp", hoặc null nếu không phải ba loại đó. */
    /*
     * "Magic bytes" (chữ ký đầu file):
     *   JPEG: FF D8 FF
     *   PNG : 89 50 4E 47 0D 0A 1A 0A
     *   WEBP: "RIFF" <4 byte kích thước> "WEBP"
     * (b[i] & 0xFF) đổi byte có dấu của Java (-128..127) sang 0..255 để so với hằng hex.
     */
    public static String detectExtension(byte[] b) {
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
}

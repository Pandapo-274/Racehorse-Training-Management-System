package racehorse.backend.horse;

/** Một nút trong cây phả hệ. sire/dam = null nếu không có dữ liệu hoặc đã hết số đời cần lấy. */
public record PedigreeNode(
        int horseId, String horseName, String registrationCode, String horseGender,
        PedigreeNode sire, PedigreeNode dam
) {}
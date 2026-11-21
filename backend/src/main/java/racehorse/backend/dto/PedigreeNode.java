package racehorse.backend.dto;

/** Một nút trong cây phả hệ. sire/dam = null nếu không có dữ liệu hoặc đã hết số đời cần lấy. */
/*
 * Cây phả hệ dạng đệ quy: mỗi nút chứa nút cha (sire) và nút mẹ (dam), mỗi nút đó lại chứa
 * cha mẹ của nó... HorseService dựng cây tới số đời yêu cầu (GET /api/horses/{id}/pedigree
 * ?generations=3 hoặc 4). Frontend PedigreeTree.js vẽ lại từ JSON lồng nhau này.
 */
public record PedigreeNode(
        int horseId, String horseName, String registrationCode, String horseGender,
        PedigreeNode sire, PedigreeNode dam
) {}

package racehorse.backend.permission;

import java.util.List;
import java.util.Set;

/**
 * Tên nghiệp vụ của các chức năng hiện trên trang Permissions.
 *
 * Danh mục này CHỈ đặt tên và nhóm. Vai trò nào được làm luôn đọc từ
 * @RequireRole của API trong `endpoint` (xem PermissionService), nên sửa quyền
 * trong controller là trang tự đổi, không phải sửa ở đây.
 *
 * ownOnly: vai trò chỉ được làm trên dữ liệu của chính mình. Luật này nằm
 * trong service (vd. HorseService.checkCanView, TrainingSessionService.checkCanAct),
 * annotation không thể hiện được, nên ghi lại ở đây. Chỉ ghi những chỗ đã đọc
 * code xác nhận.
 *
 * Chức năng mà API chưa tồn tại (vd. nhánh chưa merge) tự bị ẩn, nên thêm
 * trước một dòng cho tính năng đang làm là an toàn.
 */
final class PermissionCatalog {

    private PermissionCatalog() {
    }

    record Entry(String area, String label, String endpoint, Set<String> ownOnly) {
    }

    private static final Set<String> NONE = Set.of();
    private static final Set<String> OWNER = Set.of("HORSE_OWNER");
    private static final Set<String> GROOM = Set.of("GROOM");
    private static final Set<String> GROOM_OWNER = Set.of("GROOM", "HORSE_OWNER");

    private static final String ACCOUNTS = "Accounts & access";
    private static final String HORSES = "Horses & registration";
    private static final String TRAINING = "Training";
    private static final String HEALTH = "Health & injuries";
    private static final String CARE = "Stable care & supplies";
    private static final String RACES = "Races & results";
    private static final String REPORTS = "Reports";

    static final List<Entry> ENTRIES = List.of(
            // ---------- Tài khoản và phân quyền (UC19) ----------
            new Entry(ACCOUNTS, "Create staff accounts", "POST /api/staff", NONE),
            new Entry(ACCOUNTS, "Change a staff member's role", "PUT /api/staff/{id}/role", NONE),
            new Entry(ACCOUNTS, "Suspend or activate accounts", "PUT /api/staff/{id}/status", NONE),
            new Entry(ACCOUNTS, "See a staff member's unfinished work", "GET /api/staff/{id}/open-work", NONE),
            new Entry(ACCOUNTS, "Edit an account's contact details", "PUT /api/staff/{id}", NONE),
            new Entry(ACCOUNTS, "Set a temporary password", "PUT /api/staff/{id}/password", NONE),
            new Entry(ACCOUNTS, "View this permission matrix", "GET /api/permissions", NONE),
            new Entry(ACCOUNTS, "View the audit log", "GET /api/audit-logs", NONE),

            // ---------- Flow 1: hồ sơ ngựa ----------
            new Entry(HORSES, "View the horse list", "GET /api/horses", OWNER),
            new Entry(HORSES, "View a horse's pedigree", "GET /api/horses/{id}/pedigree", OWNER),
            new Entry(HORSES, "View a horse's vitals", "GET /api/horses/{id}/vitals", OWNER),
            new Entry(HORSES, "Edit horse details", "PUT /api/horses/{id}", OWNER),
            new Entry(HORSES, "Choose a sire or dam", "GET /api/horses/parent-candidates", OWNER),
            new Entry(HORSES, "Correct a horse's parents", "PUT /api/horses/{id}/parents", NONE),
            new Entry(HORSES, "Transfer a horse to another owner", "PUT /api/horses/{id}/owner", NONE),
            new Entry(HORSES, "Re-admit a withdrawn horse", "PUT /api/horses/{id}/readmit", NONE),
            new Entry(HORSES, "Upload a horse photo", "POST /api/horses/{id}/photo", OWNER),
            new Entry(HORSES, "Request a horse registration", "POST /api/horse-requests", NONE),
            new Entry(HORSES, "View registration requests", "GET /api/horse-requests", OWNER),
            new Entry(HORSES, "Cancel a pending registration", "PUT /api/horse-requests/{id}/cancel", OWNER),
            new Entry(HORSES, "Approve or reject registrations", "PUT /api/horse-requests/{id}/approve", NONE),
            new Entry(HORSES, "Request a horse withdrawal", "POST /api/horse-withdrawals", OWNER),
            new Entry(HORSES, "Approve or reject withdrawals", "PUT /api/horse-withdrawals/{id}/approve", NONE),

            // ---------- Flow 2: giáo án và buổi tập ----------
            new Entry(TRAINING, "View the training dashboard", "GET /api/trainer/dashboard", NONE),
            new Entry(TRAINING, "View training plans", "GET /api/training-plans", OWNER),
            new Entry(TRAINING, "Create training plans", "POST /api/training-plans", NONE),
            new Entry(TRAINING, "Update training plan phases and groom", "PUT /api/training-plans/{id}", NONE),
            new Entry(TRAINING, "Activate, pause or complete plans", "PUT /api/training-plans/{id}/status", NONE),
            new Entry(TRAINING, "Delete draft plans", "DELETE /api/training-plans/{id}", NONE),
            new Entry(TRAINING, "Approve or reject training plans", "PUT /api/training-plans/{id}/owner-review", OWNER),
            new Entry(TRAINING, "Send owner feedback on training plan", "PUT /api/training-plans/{id}/owner-feedback", OWNER),
            new Entry(TRAINING, "Assign training sessions", "POST /api/training-sessions", NONE),
            new Entry(TRAINING, "Reschedule sessions", "PUT /api/training-sessions/{id}/schedule", NONE),
            new Entry(TRAINING, "View training sessions", "GET /api/training-sessions", GROOM_OWNER),
            new Entry(TRAINING, "Start a training session", "PUT /api/training-sessions/{id}/start", GROOM),
            new Entry(TRAINING, "Send heart-rate readings", "POST /api/sessions/{sessionId}/vitals", GROOM),
            new Entry(TRAINING, "Finish a training session", "PUT /api/training-sessions/{id}/finish", GROOM),
            new Entry(TRAINING, "Stop a session when the horse is unwell", "PUT /api/training-sessions/{id}/stop", GROOM),
            new Entry(TRAINING, "View threshold alerts", "GET /api/alerts", NONE),
            new Entry(TRAINING, "Resolve alerts", "PUT /api/alerts/{alertId}/resolve", NONE),
            new Entry(TRAINING, "Evaluate sessions", "PUT /api/sessions/{id}/evaluation", NONE),
            new Entry(TRAINING, "Mark trial runs and attach video", "PUT /api/sessions/{id}/trial-run", NONE),
            new Entry(TRAINING, "Read trainer remarks", "GET /api/sessions", OWNER),

            // ---------- Flow 3: y tế ----------
            new Entry(HEALTH, "View the herd health board", "GET /api/health/overview", NONE),
            new Entry(HEALTH, "View incident reports", "GET /api/incident-reports", GROOM_OWNER),
            new Entry(HEALTH, "Report an incident at the stable", "POST /api/incident-reports", NONE),
            new Entry(HEALTH, "Take an incident case", "PUT /api/incident-reports/{id}/receive", NONE),
            new Entry(HEALTH, "Dismiss a false alarm from training", "PUT /api/incident-reports/{id}/dismiss", NONE),
            new Entry(HEALTH, "Record examinations", "POST /api/medical-records", NONE),
            new Entry(HEALTH, "Prescribe treatment", "POST /api/treatments", NONE),
            new Entry(HEALTH, "Consent to major treatment", "PUT /api/treatments/{id}/consent", OWNER),
            new Entry(HEALTH, "Reject major treatment", "PUT /api/treatments/{id}/reject-consent", OWNER),
            new Entry(HEALTH, "Mark injuries", "POST /api/injuries", NONE),
            new Entry(HEALTH, "Issue a training lock", "POST /api/training-locks", NONE),
            new Entry(HEALTH, "Release a training lock", "POST /api/training-locks/{id}/release", NONE),
            new Entry(HEALTH, "Confirm recovery", "POST /api/recovery-confirmations", NONE),
            new Entry(HEALTH, "View the preventive care schedule", "GET /api/preventive-care", NONE),

            // ---------- Chăm sóc chuồng trại, dinh dưỡng, vật tư (Flow 4) ----------
            new Entry(CARE, "View approved rations", "GET /api/feeding", OWNER),
            new Entry(CARE, "Approve a horse's ration", "PUT /api/feeding/horse/{horseId}", NONE),
            new Entry(CARE, "View daily care tasks", "GET /api/daily-tasks", GROOM),
            new Entry(CARE, "Assign daily care tasks", "POST /api/daily-tasks", NONE),
            new Entry(CARE, "Assign care tasks for many horses and days", "POST /api/daily-tasks/bulk", NONE),
            new Entry(CARE, "Mark a care task done", "PUT /api/daily-tasks/{id}/complete", GROOM),
            new Entry(CARE, "View supplies", "GET /api/supplies", NONE),
            new Entry(CARE, "Add or edit supply items", "POST /api/supplies", NONE),
            new Entry(CARE, "Update stock counts", "PUT /api/supplies/{id}/count", NONE),
            new Entry(CARE, "Receive deliveries into the store", "POST /api/supplies/{id}/receive", NONE),
            new Entry(CARE, "Record stock thrown away", "POST /api/supplies/{id}/waste", NONE),
            new Entry(CARE, "View a supply's stock history", "GET /api/supplies/{id}/movements", NONE),
            new Entry(CARE, "Hand a care task to another groom", "PUT /api/daily-tasks/{id}/assignee", NONE),
            new Entry(CARE, "View missed care tasks", "GET /api/daily-tasks/missed", NONE),

            // ---------- Giải đua (Flow 5) ----------
            new Entry(RACES, "View race entries and results", "GET /api/races", OWNER),
            new Entry(RACES, "View races", "GET /api/races/events", NONE),
            new Entry(RACES, "Create races", "POST /api/races/events", NONE),
            new Entry(RACES, "Cancel a race", "PUT /api/races/events/{id}/cancel", NONE),
            new Entry(RACES, "Register horses for races", "POST /api/races", NONE),
            new Entry(RACES, "Record race results", "PUT /api/races/{id}/result", NONE),
            new Entry(RACES, "Approve a race entry", "PUT /api/races/{id}/approve", OWNER),
            new Entry(RACES, "Reject a race entry", "PUT /api/races/{id}/reject", OWNER),
            new Entry(RACES, "Scratch a race entry", "PUT /api/races/{id}/scratch", NONE),
            new Entry(RACES, "View race performance and prize totals", "GET /api/races/summary", NONE),

            // ---------- Báo cáo ----------
            new Entry(REPORTS, "View the club overview (workload, costs, prizes)", "GET /api/manager/dashboard", NONE),
            new Entry(REPORTS, "View operating costs", "GET /api/expenses", NONE),
            new Entry(REPORTS, "Record operating costs", "POST /api/expenses", NONE),
            new Entry(REPORTS, "Void a wrongly recorded cost", "DELETE /api/expenses/{id}", NONE),
            new Entry(REPORTS, "View each cost of own horses", "GET /api/owner/expenses", OWNER),
            new Entry(REPORTS, "Dispute a recorded cost", "PUT /api/owner/expenses/{id}/dispute", OWNER),
            new Entry(REPORTS, "View the owner dashboard (costs, prizes)", "GET /api/owner/dashboard", OWNER));
}

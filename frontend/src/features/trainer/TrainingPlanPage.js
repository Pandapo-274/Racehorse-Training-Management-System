/*
 * src/features/trainer/TrainingPlanPage.js - trang /trainer/training-plans (giáo án huấn luyện, UC9, Flow 2).
 * Ba khu vực trên cùng một trang:
 *   1. Form tạo giáo án: ngựa (chỉ ngựa ELIGIBLE/MONITOR được giao cho HLV này, chưa có giáo án đang mở), Groom
 *      (tối đa 2 giáo án mở mỗi Groom - V42), tên, ngày bắt đầu/kết thúc, các giai đoạn (Base / Build / Peak...)
 *      mỗi giai đoạn có cự ly, mặt sân, số buổi/tuần, trần nhịp tim, khoảng nghỉ tối thiểu, vận tốc mục tiêu.
 *      Bản nháp form được lưu tạm trong sessionStorage để không mất khi tải lại trang.
 *   2. Danh sách giáo án (lọc theo trạng thái) với nút đổi trạng thái: gửi chủ duyệt, kích hoạt, tạm dừng,
 *      tiếp tục, kết thúc, xoá.
 *   3. Xem chi tiết / sửa một giáo án; sửa giáo án đã APPROVED/ACTIVE/PAUSED phải chờ chủ ngựa duyệt (V47).
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import { useSignOutModal } from "../auth/SignOutModal";
import ConfirmModal from "../common/ConfirmModal";
import { isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import {
  autoScheduleTrainingPlan,
  changeTrainingPlanStatus,
  deleteTrainingPlan,
  createTrainingPlan,
  updateTrainingPlan,
  listTrainingPlans,
  listPlanGrooms,
  getHorseStandards,
  getPlanTemplates,
  getTrainingPlan,
} from "./trainingPlanApi";
import "./trainer.css";
import "./training-plan.css";

// Sinh id tạm (chỉ dùng ở frontend làm key cho React) cho mỗi giai đoạn mới thêm vào form.
let phaseIdCounter = 1;
function generatePhaseId() {
  return `phase-${Date.now()}-${phaseIdCounter++}`;
}

function addDaysIso(isoDate, deltaDays) {
  if (!isoDate) return "";
  const d = new Date(`${isoDate}T00:00:00`);
  if (Number.isNaN(d.getTime())) return "";
  d.setDate(d.getDate() + deltaDays);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

const RUNNING_STYLE_LABEL = {
  FRONT: "Front-runner",
  STALKER: "Mid-pack",
  MIDDLE: "Mid-pack",
  CLOSER: "Closer",
  BACK: "Closer",
};

const DISTANCE_TYPE_LABEL = {
  SPRINT: "Sprint",
  MEDIUM: "Medium",
  LONG: "Long",
};

function formatRunningStyle(style) {
  const up = String(style || "").trim().toUpperCase();
  return RUNNING_STYLE_LABEL[up] || style || "—";
}

function formatDistanceType(dist) {
  const up = String(dist || "").trim().toUpperCase();
  return DISTANCE_TYPE_LABEL[up] || dist || "—";
}

function formatTemplateName(name) {
  return String(name || "").replace(/\bStalker\b/gi, "Mid-pack");
}

// Bảng chuẩn theo giống và tháng tuổi. Ghi rõ số nào đã công bố, số nào là tham chiếu của CLB.
function StandardsCard() {
  const [catalog, setCatalog] = useState(null);
  const [breed, setBreed] = useState("");

  useEffect(() => {
    const ctrl = new AbortController();
    getHorseStandards(ctrl.signal)
      .then((c) => { setCatalog(c); setBreed(c?.breeds?.[0]?.breed || ""); })
      .catch(() => {});
    return () => ctrl.abort();
  }, []);

  if (!catalog) return null;
  const table = catalog.breeds.find((b) => b.breed === breed) || catalog.breeds[0];
  return (
    <section className="trainer-card plans-overview-card" style={{ marginTop: 20 }}>
      <div className="plans-overview-head">
        <div className="trainer-card-title" style={{ marginBottom: 0 }}>
          <span className="trainer-diamond" />
          <div>
            <h2>Standards by breed and age</h2>
            <p>
              What a plan is based on. No plan before {catalog.minTrainingMonths} months. A plan cannot go above the speed limit
              for the horse's breed and age. Past {catalog.growthUntilMonths} months, use the body condition score.
            </p>
          </div>
        </div>
        <select className="plan-standard-select" value={table.breed} aria-label="Breed"
                onChange={(e) => setBreed(e.target.value)}>
          {catalog.breeds.map((b) => <option key={b.breed} value={b.breed}>{b.breed}</option>)}
        </select>
      </div>
      <div className="trainer-table-wrap">
        <table className="plans-table">
          <thead>
            <tr>
              <th>Age (months)</th>
              <th>Expected weight</th>
              <th>Expected height</th>
              <th>Speed limit</th>
              <th>Weight and height from</th>
            </tr>
          </thead>
          <tbody>
            {table.rows.map((r) => (
              <tr key={r.months}>
                <td><strong>{r.months > catalog.growthUntilMonths ? `${r.months}+` : r.months}</strong></td>
                <td>{r.weightKg != null ? `${r.weightKg} kg` : "—"}</td>
                <td>{r.heightCm != null ? `${r.heightCm} cm` : "—"}</td>
                <td>{Number(r.speedLimitKmh) > 0 ? `${r.speedLimitKmh} km/h` : "Not in training"}</td>
                <td>{r.basis === "PUBLISHED" ? "Published (KER 2006)" : "Club reference"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="plan-standard-note">
        Speed limits are club rules. Breeds without a reference use {catalog.unknownBreedLimitKmh} km/h.
      </p>
    </section>
  );
}

function normalizeUiSurface(raw) {
  const up = String(raw || "").trim().toUpperCase();
  return up === "DIRT" || up === "SYNTHETIC" ? "DIRT" : "TURF";
}

function computeRatioPhases(planStart, planEnd, currentPhases) {
  if (!Array.isArray(currentPhases) || currentPhases.length !== 3) {
    return { phases: currentPhases, dayCounts: null, totalDays: 0 };
  }
  const r1 = Number(currentPhases[0].phaseRatio ?? 30);
  const r2 = Number(currentPhases[1].phaseRatio ?? 45);
  const hasRange = Boolean(planStart && planEnd && planEnd >= planStart);

  if (!hasRange) {
    const b1 = Math.floor((7 * r1) / 100);
    const b2 = Math.floor((7 * r2) / 100);
    const b3 = Math.max(0, 7 - (b1 + b2));
    const baseCounts = [b1, b2, b3];
    const next = currentPhases.map((p, idx) => ({
      ...p,
      sessionsPerWeek: String(Math.max(1, baseCounts[idx])),
    }));
    return { phases: next, dayCounts: null, totalDays: 0 };
  }

  const s = new Date(`${planStart}T00:00:00`);
  const e = new Date(`${planEnd}T00:00:00`);
  const totalDays = Math.floor((e.getTime() - s.getTime()) / 86400000) + 1;
  if (!Number.isFinite(totalDays) || totalDays <= 0) {
    return { phases: currentPhases, dayCounts: null, totalDays: 0 };
  }
  const d1 = Math.floor((totalDays * r1) / 100);
  const d2 = Math.floor((totalDays * r2) / 100);
  const d3 = Math.max(0, totalDays - (d1 + d2));
  const counts = [d1, d2, d3];

  let cursor = planStart;
  const next = currentPhases.map((p, idx) => {
    const len = counts[idx];
    const pStart = idx === 0 ? planStart : cursor > planEnd ? planEnd : cursor;
    let pEnd = len > 0 ? addDaysIso(pStart, len - 1) : pStart;
    if (pEnd > planEnd || idx === currentPhases.length - 1) pEnd = planEnd;
    if (len > 0) cursor = addDaysIso(pEnd, 1);
    return {
      ...p,
      startDate: pStart,
      endDate: pEnd,
      sessionsPerWeek: String(Math.max(1, len)),
    };
  });
  return { phases: next, dayCounts: counts, totalDays };
}

// Giai đoạn mặc định khi mở form lần đầu: 3 giai đoạn chuẩn cố định (30% - 45% - 25%).
const initialPhases = [
  {
    id: "init-phase-1",
    phaseCode: "FOUNDATION",
    phaseName: "Foundation",
    phaseRatio: 30,
    recoveryDurationMinutes: 15,
    distance: "1200",
    workload: "Light / Aerobic",
    surface: "TURF",
    sessionsPerWeek: "2",
    heartRateCeiling: "185",
    minGapMinutes: "90",
    targetVelocity: "50",
    startDate: "",
    endDate: "",
  },
  {
    id: "init-phase-2",
    phaseCode: "BUILD",
    phaseName: "Build",
    phaseRatio: 45,
    recoveryDurationMinutes: 20,
    distance: "1600",
    workload: "Moderate / Interval",
    surface: "TURF",
    sessionsPerWeek: "3",
    heartRateCeiling: "195",
    minGapMinutes: "90",
    targetVelocity: "58",
    startDate: "",
    endDate: "",
  },
  {
    id: "init-phase-3",
    phaseCode: "PEAK",
    phaseName: "Peak",
    phaseRatio: 25,
    recoveryDurationMinutes: 30,
    distance: "1800",
    workload: "High / Race Pace",
    surface: "TURF",
    sessionsPerWeek: "2",
    heartRateCeiling: "205",
    minGapMinutes: "90",
    targetVelocity: "64",
    startDate: "",
    endDate: "",
  },
];

const PLAN_STATUS_LABEL = {
  DRAFT: "Draft",
  PENDING_APPROVAL: "Waiting for owner",
  APPROVED: "Approved by owner",
  ACTIVE: "Active",
  PAUSED: "Paused",
  COMPLETED: "Completed",
};

function HorseshoeIcon() {
  return (
    <svg
      className="trainer-nav-icon"
      viewBox="0 0 24 24"
      width="20"
      height="20"
      aria-hidden="true"
    >
      <path
        d="M6.2 21V12a5.8 5.8 0 0 1 11.6 0v9"
        fill="none"
        stroke="currentColor"
        strokeWidth="3.6"
        strokeLinecap="round"
      />
    </svg>
  );
}

function TrainerSidebar({ active, onSignOut }) {
  return (
    <aside className="trainer-sidebar">
      <div className="trainer-brand">
        <img src="/logo.png" alt="Tracen logo" />
        <div>
          <strong>TRACEN</strong>
          <span>Racing Club</span>
        </div>
      </div>
      <div className="trainer-menu-title">
        <HorseshoeIcon /> MENU
      </div>
      <nav className="trainer-nav">
        <NavItems role="HEAD_TRAINER" prefix="trainer" icon={<HorseshoeIcon />} />
      </nav>
      <div className="trainer-sidebar-bottom">
        <button type="button" className="trainer-signout" onClick={onSignOut}>
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <polyline points="16 17 21 12 16 7" />
            <line x1="21" y1="12" x2="9" y2="12" />
          </svg>
          Sign out
        </button>

        <ProfileCard
          className="trainer-user"
          avatarClassName="trainer-avatar"
          role="Head Trainer"
        />
      </div>
    </aside>
  );
}

// Ngày "yyyy-mm-dd" -> "17 Nov 2026" (thêm T00:00:00 để đọc theo giờ địa phương).
function dateLabel(value) {
  if (!value) return "—";
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString("en-GB", {
      day: "2-digit",
      month: "short",
      year: "numeric",
    });
}

// Trạng thái -> hậu tố lớp CSS ("PENDING_APPROVAL" -> "pending_approval" rồi ký tự lạ thành "-").
function statusClass(status) {
  return String(status || "DRAFT")
    .toLowerCase()
    .replace(/[^a-z-]/g, "-");
}

// Ô trống -> null (backend hiểu là không đặt), có giá trị -> số.
function optionalNumber(value) {
  return value === "" || value == null ? null : Number(value);
}

// Bản nháp form tạo giáo án trong sessionStorage (mất khi đóng tab). Lỗi đọc/ghi (vd. bộ nhớ đầy) bị bỏ qua.
const DRAFT_KEY = "tracen_training_plan_draft";

function loadDraft() {
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function saveDraft(draft) {
  try {
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
  } catch {}
}

function clearDraft() {
  try {
    sessionStorage.removeItem(DRAFT_KEY);
  } catch {}
}

// Đổi lỗi từng ô của backend ("phases[1].startDate") thành câu dễ đọc ("Phase 2 start date: ...").
function formatPlanErrors(err) {
  if (!err) return "";
  const friendlyField = (field) => {
    const phaseMatch = field.match(/^phases\[(\d+)\]\.(.*)$/);
    if (phaseMatch) {
      const phaseNum = Number(phaseMatch[1]) + 1;
      const subField = phaseMatch[2];
      const subLabels = {
        startDate: "start date",
        endDate: "end date",
        distance: "distance",
        phaseName: "name",
        workload: "workload",
        surface: "surface",
        sessionsPerWeek: "sessions/week",
        heartRateCeiling: "heart-rate ceiling",
        minGapMinutes: "rest between sessions",
        targetVelocity: "target speed",
      };
      return `Phase ${phaseNum} ${subLabels[subField] || subField}`;
    }
    const map = {
      startDate: "Plan start date",
      endDate: "Plan end date",
      planName: "Plan name",
      horseId: "Horse",
    };
    return map[field] || field;
  };

  if (err.fieldErrors && Object.keys(err.fieldErrors).length > 0) {
    return Object.entries(err.fieldErrors).map(([field, msg]) => {
      if (msg.toLowerCase().startsWith("phase ") || msg.toLowerCase().startsWith("plan ")) {
        return msg;
      }
      return `${friendlyField(field)}: ${msg}`;
    });
  }
  return err.message || "Could not save training plan.";
}

/*
 * State: horses / plans / grooms (dữ liệu tải về), các ô của form tạo (horseId, groomId, planName, startDate, endDate,
 * phases), viewingPlan (giáo án đang xem chi tiết), editingPlan + edit... (form sửa), deleteConfirm (ConfirmModal xoá).
 * savedDraft: bản nháp đọc từ sessionStorage một lần lúc mở trang để điền lại form.
 */
export default function TrainingPlanPage() {
  const navigate = useNavigate();
  const savedDraft = useMemo(() => loadDraft(), []);
  const myUser = getUser();

  const [horses, setHorses] = useState([]);
  const [plans, setPlans] = useState([]);
  const [grooms, setGrooms] = useState([]);
  const [isCreating, setIsCreating] = useState(false);
  const [viewingPlan, setViewingPlan] = useState(null);
  const [viewingLoading, setViewingLoading] = useState(false);
  const [horseId, setHorseId] = useState(savedDraft?.horseId || "");
  const [groomId, setGroomId] = useState(savedDraft?.groomId || "");
  const [backupGroomId, setBackupGroomId] = useState(savedDraft?.backupGroomId || "");
  const [templateRec, setTemplateRec] = useState(null);
  const [catalogTemplates, setCatalogTemplates] = useState([]);
  const [templateLoading, setTemplateLoading] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState(savedDraft?.selectedTemplateId || "");
  const [intensity, setIntensity] = useState(savedDraft?.intensity || "MEDIUM");
  const [maxHeartRate, setMaxHeartRate] = useState(savedDraft?.maxHeartRate || "");
  const [maxSpeed, setMaxSpeed] = useState(savedDraft?.maxSpeed || "");
  const [restDaysPerWeek, setRestDaysPerWeek] = useState(savedDraft?.restDaysPerWeek ?? 1);
  const [targetHrr, setTargetHrr] = useState(savedDraft?.targetHrr || "");
  const [planName, setPlanName] = useState(savedDraft?.planName || "");
  const [startDate, setStartDate] = useState(savedDraft?.startDate || "");
  const [endDate, setEndDate] = useState(savedDraft?.endDate || "");
  const [phases, setPhases] = useState(() => {
    const raw =
      savedDraft?.phases && savedDraft.phases.length === 3
        ? savedDraft.phases.map((p, idx) => ({
            ...initialPhases[idx],
            ...p,
            surface: normalizeUiSurface(p.surface),
          }))
        : initialPhases;
    return computeRatioPhases(savedDraft?.startDate || "", savedDraft?.endDate || "", raw).phases;
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [filter, setFilter] = useState("ALL");

  // State cho việc cập nhật giáo án (Edit training plan)
  const [editingPlan, setEditingPlan] = useState(null);
  const [editGroomId, setEditGroomId] = useState("");
  const [editPlanName, setEditPlanName] = useState("");
  const [editPhases, setEditPhases] = useState([]);
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState("");
  const [deleteConfirm, setDeleteConfirm] = useState({ isOpen: false, plan: null });

  const applyTemplateToForm = useCallback((tpl, sDate, eDate, lockedSurface) => {
    if (!tpl) return;
    setSelectedTemplateId(String(tpl.templateId));
    setIntensity(tpl.intensity || "MEDIUM");
    setMaxHeartRate(tpl.maxHeartRate != null ? String(tpl.maxHeartRate) : "");
    setMaxSpeed(tpl.maxSpeed != null ? String(tpl.maxSpeed) : "");
    setRestDaysPerWeek(tpl.restDaysPerWeek != null ? tpl.restDaysPerWeek : 1);
    setTargetHrr(tpl.targetHrr != null ? String(tpl.targetHrr) : "");
    const rawPhases = (tpl.phases || []).map((ph) => ({
      id: generatePhaseId(),
      phaseCode: ph.phaseCode,
      phaseName: ph.phaseName,
      phaseRatio: ph.phaseRatio,
      recoveryDurationMinutes: ph.recoveryDurationMinutes,
      distance: String(ph.distance ?? ""),
      workload: ph.workload || "",
      surface: normalizeUiSurface(lockedSurface || ph.surface || "TURF"),
      sessionsPerWeek: String(ph.sessionsPerWeek ?? 2),
      heartRateCeiling: String(ph.heartRateCeiling ?? ""),
      minGapMinutes: "90",
      targetVelocity: String(ph.targetVelocity ?? ""),
      startDate: "",
      endDate: "",
    }));
    const computed = computeRatioPhases(sDate, eDate, rawPhases);
    setPhases(computed.phases);
  }, []);

  // Tự động gợi ý template & khoá surface theo hồ sơ ngựa khi Trainer chọn ngựa.
  useEffect(() => {
    if (!horseId) {
      setTemplateRec(null);
      return undefined;
    }
    const controller = new AbortController();
    setTemplateLoading(true);
    getPlanTemplates(horseId, controller.signal)
      .then((rec) => {
        setTemplateRec(rec);
        if (rec?.allTemplates?.length) setCatalogTemplates(rec.allTemplates);
        if (rec?.recommendedTemplate && String(selectedTemplateId) !== String(rec.recommendedTemplate.templateId)) {
          applyTemplateToForm(rec.recommendedTemplate, startDate, endDate, rec.lockedSurface);
        } else if (rec && !rec.recommendedTemplate) {
          // Giống không có mẫu: bỏ mẫu của con ngựa trước, HLV tự chọn.
          setSelectedTemplateId("");
        } else if (rec?.lockedSurface) {
          const safeSurf = normalizeUiSurface(rec.lockedSurface);
          setPhases((curr) => curr.map((p) => ({ ...p, surface: safeSurf })));
        }
      })
      .catch(() => {})
      .finally(() => setTemplateLoading(false));
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [horseId]);

  // Tự động chia ngày & số buổi (sessionsPerWeek) 3 phase theo tỷ lệ khi startDate/endDate/tỷ lệ đổi.
  useEffect(() => {
    if (phases.length === 3 && phases.every((p) => p.phaseRatio != null && p.phaseRatio !== "")) {
      const { phases: next } = computeRatioPhases(startDate, endDate, phases);
      const changed = next.some(
        (np, i) =>
          np.startDate !== phases[i].startDate ||
          np.endDate !== phases[i].endDate ||
          String(np.sessionsPerWeek) !== String(phases[i].sessionsPerWeek),
      );
      if (changed) setPhases(next);
    }
  }, [startDate, endDate, phases]);

  // Mỗi lần một ô trong form tạo đổi thì lưu lại bản nháp.
  useEffect(() => {
    saveDraft({
      horseId,
      groomId,
      backupGroomId,
      selectedTemplateId,
      intensity,
      maxHeartRate,
      maxSpeed,
      restDaysPerWeek,
      targetHrr,
      planName,
      startDate,
      endDate,
      phases,
    });
  }, [horseId, groomId, backupGroomId, selectedTemplateId, intensity, maxHeartRate, maxSpeed, restDaysPerWeek, targetHrr, planName, startDate, endDate, phases]);

  // Tải song song ngựa, giáo án, Groom. Lọc ngựa lập được giáo án; bỏ lựa chọn ngựa / Groom trong bản nháp nếu chúng
  // không còn hợp lệ (ngựa đã có giáo án mở, Groom đã đủ giáo án).
  const load = useCallback(
    async (signal) => {
      setLoading(true);
      setError("");
      try {
        const [horseRows, planRows, groomRows, tplRec] = await Promise.all([
          listHorses(signal),
          listTrainingPlans({ signal }),
          listPlanGrooms(signal).catch((err) => {
            console.warn("Failed to load plan grooms:", err);
            return [];
          }),
          getPlanTemplates(null, signal).catch(() => null),
        ]);
        if (tplRec?.allTemplates?.length) {
          setCatalogTemplates(tplRec.allTemplates);
        }
        const myId = getUser()?.userId;
        const myRole = getUser()?.role;
        // V37: Head Trainer chỉ lập giáo án cho các ngựa đã được Club Manager điều phối cho mình
        const eligibleHorses = (horseRows || []).filter(
          (horse) =>
            !isPedigreeOnly(horse) &&
            !isWithdrawn(horse) &&
            (horse.status === "ELIGIBLE" || horse.status === "MONITOR") &&
            (myRole !== "HEAD_TRAINER" || !myId || horse.trainerId === myId),
        );

        setHorses(eligibleHorses);
        setPlans(planRows || []);
        setGrooms(groomRows || []);

        setHorseId((current) => {
          const currentHorseIsEligible = eligibleHorses.some(
            (horse) => String(horse.horseId) === String(current),
          );
          const currentHasOngoing = (planRows || []).some(
            (p) => String(p.horseId) === String(current) && !["COMPLETED", "CANCELLED"].includes(p.status)
          );
          return currentHorseIsEligible && !currentHasOngoing ? current : "";
        });

        setGroomId((current) => {
          const currentGroomIsValid = (groomRows || []).some(
            (g) => String(g.userId) === String(current) && g.eligible,
          );
          return currentGroomIsValid ? current : "";
        });
      } catch (err) {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message || "Unable to load training plans.");
      } finally {
        setLoading(false);
      }
    },
    [navigate],
  );

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const selectedHorse = useMemo(
    () => horses.find((h) => String(h.horseId) === String(horseId)),
    [horses, horseId],
  );
  const selectedGroom = useMemo(
    () => grooms.find((g) => String(g.userId) === String(groomId)),
    [grooms, groomId],
  );
  const visiblePlans = useMemo(
    () => (filter === "ALL" ? plans : plans.filter((p) => p.status === filter)),
    [plans, filter],
  );

  // Mở chi tiết một giáo án (GET /api/training-plans/{id}: kèm giai đoạn và bản sửa đang chờ duyệt).
  const handleViewPlan = async (plan) => {
    setViewingLoading(true);
    setViewingPlan(plan);
    try {
      const full = await getTrainingPlan(plan.planId);
      setViewingPlan(full);
    } catch (err) {
      if (!handleAuthError(err, navigate)) {
        // keep fallback plan
      }
    } finally {
      setViewingLoading(false);
    }
  };

  // Chép giáo án vào form sửa (Groom, tên, các giai đoạn giữ phaseId để backend biết giai đoạn nào sửa).
  const startEditing = (plan) => {
    setEditingPlan(plan);
    setEditGroomId(plan.groomId ? String(plan.groomId) : "");
    setEditPlanName(plan.planName || "");
    const defaultCodes = ["FOUNDATION", "BUILD", "PEAK"];
    const defaultRatios = [30, 45, 25];
    const defaultRecs = [15, 20, 30];
    const mapped = (plan.phases || []).map((p, idx) => ({
      phaseId: p.phaseId,
      phaseCode: p.phaseCode || (plan.phases?.length === 3 ? defaultCodes[idx] : null),
      phaseName: p.phaseName,
      phaseRatio: p.phaseRatio ?? (plan.phases?.length === 3 ? defaultRatios[idx] : ""),
      recoveryDurationMinutes: p.recoveryDurationMinutes ?? (plan.phases?.length === 3 ? defaultRecs[idx] : 15),
      startDate: p.startDate,
      endDate: p.endDate,
      distance: p.distance,
      surface: normalizeUiSurface(p.surface),
      sessionsPerWeek: p.sessionsPerWeek,
      heartRateCeiling: p.heartRateCeiling,
      workload: p.workload || "",
      minGapMinutes: p.minGapMinutes || 90,
      targetVelocity: p.targetVelocity ?? "",
    }));
    if (mapped.length === 3) {
      setEditPhases(computeRatioPhases(plan.startDate, plan.endDate, mapped).phases);
    } else {
      setEditPhases(mapped);
    }
    setEditError("");
  };

  // Gửi bản sửa (updateTrainingPlan). Giáo án DRAFT / PENDING_APPROVAL được sửa thẳng; đã duyệt thì thành bản chờ duyệt.
  const handleUpdateSubmit = async (e) => {
    e.preventDefault();
    if (!editingPlan) return;
    if (!editGroomId) {
      setEditError("Please select an assigned groom.");
      return;
    }
    const usesEditRatio = editPhases.length === 3 && editPhases.every((p) => p.phaseRatio !== "" && p.phaseRatio != null);
    if (usesEditRatio) {
      const editRatioSum = editPhases.reduce((s, p) => s + Number(p.phaseRatio || 0), 0);
      if (editRatioSum !== 100) {
        setEditError(`Phase ratios across the 3 phases must total 100% (currently ${editRatioSum}%).`);
        return;
      }
    }
    const effectiveEditPhases = usesEditRatio
      ? computeRatioPhases(editingPlan.startDate, editingPlan.endDate, editPhases).phases
      : editPhases;
    setEditSaving(true);
    setEditError("");
    try {
      const payload = {
        groomId: Number(editGroomId),
        planName: editPlanName.trim() || editingPlan.planName,
        phases: effectiveEditPhases.map((p, idx) => ({
          phaseId: p.phaseId || null,
          phaseCode: p.phaseCode || (effectiveEditPhases.length === 3 ? (idx === 0 ? "FOUNDATION" : idx === 1 ? "BUILD" : "PEAK") : null),
          phaseRatio: p.phaseRatio !== "" && p.phaseRatio != null ? Number(p.phaseRatio) : null,
          recoveryDurationMinutes: p.recoveryDurationMinutes !== "" && p.recoveryDurationMinutes != null ? Number(p.recoveryDurationMinutes) : 15,
          phaseName: p.phaseName,
          startDate: p.startDate,
          endDate: p.endDate,
          distance: Number(p.distance),
          surface: normalizeUiSurface(p.surface),
          sessionsPerWeek: Number(p.sessionsPerWeek),
          heartRateCeiling: Number(p.heartRateCeiling),
          workload: p.workload ? p.workload.trim() : null,
          minGapMinutes: Number(p.minGapMinutes) || 90,
          targetVelocity: p.targetVelocity ? Number(p.targetVelocity) : null,
        })),
      };
      const updated = await updateTrainingPlan(editingPlan.planId, payload);
      setNotice(
        `Training plan "${updated.planName}" updated successfully! Notification sent to the horse owner for review.`,
      );
      setEditingPlan(null);
      setViewingPlan(updated);
      await load();
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      const errMsg = err.fieldErrors && Object.keys(err.fieldErrors).length > 0
        ? Object.entries(err.fieldErrors).map(([_, v]) => v).join("; ")
        : (err.message || "Failed to update training plan.");
      setEditError(errMsg);
    } finally {
      setEditSaving(false);
    }
  };

  // Cập nhật field cho 3 giai đoạn cố định (FOUNDATION, BUILD, PEAK). Khi đổi tỷ lệ %, tự tính lại ngày và số buổi.
  const updatePhase = (index, field, value) =>
    setPhases((current) => {
      const updated = current.map((phase, i) =>
        i === index ? { ...phase, [field]: value } : phase,
      );
      if (field === "phaseRatio" && updated.length === 3 && updated.every((p) => p.phaseRatio !== "" && p.phaseRatio != null)) {
        return computeRatioPhases(startDate, endDate, updated).phases;
      }
      return updated;
    });

  const availableHorses = useMemo(
    () =>
      horses.filter(
        (h) => !plans.some((p) => p.horseId === h.horseId && !["COMPLETED", "CANCELLED"].includes(p.status)),
      ),
    [horses, plans],
  );
  const templateOptions = useMemo(
    () => (templateRec?.allTemplates?.length ? templateRec.allTemplates : catalogTemplates),
    [templateRec, catalogTemplates],
  );

  const usesRatioMode = phases.length === 3 && phases.every((p) => p.phaseRatio != null && p.phaseRatio !== "");
  const totalRatio = phases.reduce(
    (sum, p) => sum + (p.phaseRatio != null && p.phaseRatio !== "" ? Number(p.phaseRatio) : 0),
    0,
  );
  const ratioPreview = useMemo(
    () => (usesRatioMode ? computeRatioPhases(startDate, endDate, phases) : { phases, dayCounts: null, totalDays: 0 }),
    [usesRatioMode, startDate, endDate, phases],
  );

  // Tạo giáo án: kiểm ở trình duyệt (có ngựa, ngựa chưa có giáo án mở, có Primary/Backup Groom, tỷ lệ 3 phase = 100%).
  const submit = async (event) => {
    event.preventDefault();
    setError("");
    setNotice("");
    if (!horseId) {
      setError("Select a horse before saving the plan.");
      return;
    }
    const ongoingPlan = plans.find(
      (p) => p.horseId === Number(horseId) && !["COMPLETED", "CANCELLED"].includes(p.status)
    );
    if (ongoingPlan) {
      setError(`This horse already has an ongoing training plan (#${ongoingPlan.planId}: ${PLAN_STATUS_LABEL[ongoingPlan.status] || ongoingPlan.status}). A new plan can only be created once the current plan is completed or cancelled.`);
      return;
    }
    if (!groomId) {
      setError("Select a primary groom before saving the plan.");
      return;
    }
    if (backupGroomId && String(backupGroomId) === String(groomId)) {
      setError("Backup groom must be different from primary groom.");
      return;
    }
    if (endDate < startDate) {
      setError("Plan end date must be on or after the start date.");
      return;
    }
    if (usesRatioMode && totalRatio !== 100) {
      setError(`Phase time ratios across the 3 phases must total exactly 100% (currently ${totalRatio}%).`);
      return;
    }
    const effectivePhases = usesRatioMode ? ratioPreview.phases : phases;
    const payload = {
      horseId: Number(horseId),
      groomId: Number(groomId),
      primaryGroomId: Number(groomId),
      backupGroomId: backupGroomId ? Number(backupGroomId) : null,
      templateId: selectedTemplateId ? Number(selectedTemplateId) : null,
      preferredSurface: templateRec?.lockedSurface || null,
      intensity: intensity || null,
      maxHeartRate: optionalNumber(maxHeartRate),
      maxSpeed: optionalNumber(maxSpeed),
      restDaysPerWeek: restDaysPerWeek === "" || restDaysPerWeek == null ? 1 : Number(restDaysPerWeek),
      targetHrr: optionalNumber(targetHrr),
      autoSchedule: false,
      planName: planName.trim(),
      startDate,
      endDate,
      phases: effectivePhases.map((p, idx) => ({
        phaseCode: p.phaseCode || (effectivePhases.length === 3 ? (idx === 0 ? "FOUNDATION" : idx === 1 ? "BUILD" : "PEAK") : null),
        phaseRatio: p.phaseRatio !== "" && p.phaseRatio != null ? Number(p.phaseRatio) : null,
        recoveryDurationMinutes: p.recoveryDurationMinutes !== "" && p.recoveryDurationMinutes != null ? Number(p.recoveryDurationMinutes) : 15,
        phaseName: p.phaseName.trim(),
        distance: p.distance === "" ? null : Number(p.distance),
        workload: p.workload.trim() || null,
        surface: templateRec?.lockedSurface || p.surface,
        sessionsPerWeek:
          p.sessionsPerWeek === "" ? null : Number(p.sessionsPerWeek),
        heartRateCeiling:
          p.heartRateCeiling === "" ? null : Number(p.heartRateCeiling),
        minGapMinutes: optionalNumber(p.minGapMinutes),
        targetVelocity: optionalNumber(p.targetVelocity),
        startDate: p.startDate || startDate,
        endDate: p.endDate || endDate,
      })),
    };
    setSaving(true);
    try {
      const created = await createTrainingPlan(payload);
      setNotice(
        `Training plan “${created.planName}” was created and sent to the horse owner for review. It will become Active and auto-schedule once approved.`,
      );
      clearDraft();
      setPlanName("");
      setHorseId("");
      setGroomId("");
      setBackupGroomId("");
      setSelectedTemplateId("");
      setTemplateRec(null);
      setStartDate("");
      setEndDate("");
      setPhases(initialPhases);
      setIsCreating(false);
      await load();
    } catch (err) {
      if (!handleAuthError(err, navigate)) {
        setError(formatPlanErrors(err));
      }
    } finally {
      setSaving(false);
    }
  };

  const handleAutoSchedulePlan = async (plan) => {
    setError("");
    setNotice("");
    try {
      const res = await autoScheduleTrainingPlan(plan.planId);
      setNotice(
        `Auto-scheduled “${plan.planName}”: ${res.createdTrainingSessions} training session(s), ${res.createdRestSlots} rest day(s)${res.conflictCount ? `, ${res.conflictCount} UNASSIGNED conflict alert(s)` : ""}.`,
      );
      await load();
    } catch (err) {
      if (!handleAuthError(err, navigate)) {
        setError(err.message || "Failed to auto-schedule training plan.");
      }
    }
  };

  // Đổi trạng thái (changeTrainingPlanStatus). DRAFT -> PENDING_APPROVAL = gửi chủ ngựa duyệt.
  const updateStatus = async (plan, status) => {
    setError("");
    setNotice("");
    try {
      const updated = await changeTrainingPlanStatus(plan.planId, status);
      setPlans((current) =>
        current.map((p) =>
          p.planId === updated.planId ? { ...p, status: updated.status } : p,
        ),
      );
      setNotice(
        updated.status === "PENDING_APPROVAL"
          ? "Plan sent to the owner for approval."
          : `Plan status changed to ${PLAN_STATUS_LABEL[updated.status] || updated.status}.`,
      );
    } catch (err) {
      if (!handleAuthError(err, navigate))
        setError(err.message || "Could not change plan status.");
    }
  };

  // Xoá: mở ConfirmModal; confirmDeletePlan mới thật sự gọi DELETE.
  const deletePlan = (plan) => {
    setDeleteConfirm({ isOpen: true, plan });
  };

  const confirmDeletePlan = async () => {
    const plan = deleteConfirm.plan;
    setDeleteConfirm({ isOpen: false, plan: null });
    if (!plan) return;
    setError("");
    setNotice("");
    try {
      await deleteTrainingPlan(plan.planId);
      setPlans((current) => current.filter((p) => p.planId !== plan.planId));
      setNotice(`Plan "${plan.planName}" deleted.`);
    } catch (err) {
      if (!handleAuthError(err, navigate))
        setError(err.message || "Could not delete this plan.");
    }
  };

  const { requestSignOut, modalElement } = useSignOutModal(navigate);

  return (
    <div className="trainer-page training-plan-page">
      {modalElement}
      <ConfirmModal
        isOpen={deleteConfirm.isOpen}
        title="Delete Training Plan"
        message={`Delete the plan "${deleteConfirm.plan?.planName}"? This action cannot be undone.`}
        confirmText="Delete Plan"
        confirmTone="danger"
        icon="🗑️"
        onConfirm={confirmDeletePlan}
        onCancel={() => setDeleteConfirm({ isOpen: false, plan: null })}
      />
      <TrainerSidebar active="Training plans" onSignOut={requestSignOut} />
      <main className="trainer-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span className="trainer-diamond" aria-hidden="true" />
              <h1>Training plans</h1>
            </div>
            <p>
              {isCreating
                ? `Build a phased programme for a horse assigned to you (${horses.length} managed eligible horses)`
                : `Manage all training plans under your supervision (${plans.length} total)`}
            </p>
          </div>
          <div className="trainer-header-actions">
            {isCreating && (
              <button
                type="button"
                className="training-back-btn-top"
                onClick={() => {
                  setError("");
                  setIsCreating(false);
                }}
              >
                ← Back to plans list
              </button>
            )}
            <ProfileCircle className="trainer-profile" />
          </div>
        </header>
        <section className="trainer-content training-plan-content">
          {error && (
            <div className="trainer-state trainer-state--error" role="alert" style={{ display: "flex", flexDirection: "column", gap: "8px", textAlign: "left", padding: "16px 20px" }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <strong style={{ fontSize: "15px", color: "#b91c1c" }}>⚠️ Action could not be completed</strong>
                <button
                  type="button"
                  onClick={() => setError("")}
                  style={{ background: "none", border: "none", cursor: "pointer", fontSize: "20px", color: "#666", fontWeight: "bold", padding: "0 4px" }}
                  title="Close error"
                >
                  ×
                </button>
              </div>
              <div style={{ fontSize: "14px", lineHeight: "1.5" }}>
                {Array.isArray(error) ? (
                  <ul style={{ margin: "4px 0 0 18px", padding: 0 }}>
                    {error.map((msg, i) => (
                      <li key={i} style={{ marginBottom: "4px" }}>{msg}</li>
                    ))}
                  </ul>
                ) : (
                  <span>{error}</span>
                )}
              </div>
            </div>
          )}
          {notice && (
            <div className="training-plan-notice" role="status">
              {notice}
            </div>
          )}

          {!isCreating ? (
            <>
            <section className="trainer-card plans-overview-card">
              <div className="plans-overview-head">
                <div className="trainer-card-title" style={{ marginBottom: 0 }}>
                  <span className="trainer-diamond" />
                  <div>
                    <h2>Managed training plans</h2>
                    <p>
                      Showing {visiblePlans.length} of {plans.length} plan{plans.length === 1 ? "" : "s"} managed by {myUser?.fullName || "you"}
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  className="training-create-btn"
                  onClick={() => {
                    setError("");
                    setNotice("");
                    setIsCreating(true);
                  }}
                >
                  + Create new plan
                </button>
              </div>

              <div className="training-plan-filter" style={{ marginBottom: 16 }}>
                {["ALL", ...Object.keys(PLAN_STATUS_LABEL)].map((s) => (
                  <button
                    key={s}
                    type="button"
                    className={filter === s ? "selected" : ""}
                    onClick={() => setFilter(s)}
                  >
                    {s === "ALL" ? `All (${plans.length})` : PLAN_STATUS_LABEL[s]}
                  </button>
                ))}
              </div>

              {loading ? (
                <p className="training-empty">Loading training plans…</p>
              ) : visiblePlans.length === 0 ? (
                <div className="training-empty-box">
                  <p className="training-empty">No training plans found in this view.</p>
                  <button
                    type="button"
                    className="training-create-btn"
                    onClick={() => setIsCreating(true)}
                  >
                    + Create new plan
                  </button>
                </div>
              ) : (
                <div className="plans-table-wrap">
                  <table className="plans-table">
                    <thead>
                      <tr>
                        <th>Plan</th>
                        <th>Horse</th>
                        <th>Assigned Groom</th>
                        <th>Period</th>
                        <th style={{ textAlign: "center" }}>Phases</th>
                        <th style={{ textAlign: "center" }}>Status</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {visiblePlans.map((plan) => (
                        <tr key={plan.planId}>
                          <td>
                            <div className="plan-cell-main">
                              <strong>{plan.planName}</strong>
                              <span className="plan-cell-id">Plan #{plan.planId}</span>
                              {plan.status === "DRAFT" && plan.ownerNote && (
                                <p className="saved-plan-owner-note" style={{ marginTop: 4 }}>
                                  Owner rejected: {plan.ownerNote}
                                </p>
                              )}
                            </div>
                          </td>
                          <td>
                            <span className="plan-horse-badge">♞ {plan.horseName}</span>
                          </td>
                          <td>
                            {plan.groomName ? (
                              <span className="plan-groom-chip">👤 {plan.groomName}</span>
                            ) : (
                              <span className="plan-groom-unassigned">Unassigned</span>
                            )}
                          </td>
                          <td>
                            <span className="plan-period-box">
                              {dateLabel(plan.startDate)} – {dateLabel(plan.endDate)}
                            </span>
                          </td>
                          <td style={{ textAlign: "center" }}>
                            <span className="plan-phase-count">
                              {plan.phaseCount} phase{plan.phaseCount === 1 ? "" : "s"}
                            </span>
                          </td>
                          <td style={{ textAlign: "center" }}>
                            <span className={`training-status-pill ${statusClass(plan.status)}`}>
                              {PLAN_STATUS_LABEL[plan.status] || plan.status}
                            </span>
                            {plan.hasPendingUpdate && (
                              <div
                                style={{
                                  marginTop: 4,
                                  fontSize: "0.72rem",
                                  fontWeight: 600,
                                  color: "#92400e",
                                  background: "#fef3c7",
                                  borderRadius: 999,
                                  padding: "2px 8px",
                                  display: "inline-block",
                                }}
                              >
                                Update pending owner approval
                              </div>
                            )}
                          </td>
                          <td>
                            <div className="saved-plan-actions">
                              <button
                                type="button"
                                className="plan-action-btn plan-action-btn--view"
                                onClick={() => handleViewPlan(plan)}
                              >
                                View details
                              </button>
                              {plan.status === "DRAFT" && (
                                <button
                                  type="button"
                                  className="plan-action-btn plan-action-btn--primary"
                                  onClick={() => updateStatus(plan, "PENDING_APPROVAL")}
                                >
                                  Send to owner
                                </button>
                              )}
                              {plan.status === "APPROVED" && (
                                <button
                                  type="button"
                                  className="plan-action-btn plan-action-btn--success"
                                  onClick={() => updateStatus(plan, "ACTIVE")}
                                >
                                  Activate
                                </button>
                              )}
                              {plan.status === "ACTIVE" && (
                                <>
                                  <button
                                    type="button"
                                    className="plan-action-btn plan-action-btn--primary"
                                    onClick={() => handleAutoSchedulePlan(plan)}
                                  >
                                    ⚡ Auto-Schedule
                                  </button>
                                  <button
                                    type="button"
                                    className="plan-action-btn"
                                    onClick={() => updateStatus(plan, "PAUSED")}
                                  >
                                    Pause
                                  </button>
                                  <button
                                    type="button"
                                    className="plan-action-btn plan-action-btn--success"
                                    onClick={() => updateStatus(plan, "COMPLETED")}
                                  >
                                    Complete
                                  </button>
                                </>
                              )}
                              {plan.status === "PAUSED" && (
                                <>
                                  <button
                                    type="button"
                                    className="plan-action-btn plan-action-btn--primary"
                                    onClick={() => updateStatus(plan, "ACTIVE")}
                                  >
                                    Resume
                                  </button>
                                  <button
                                    type="button"
                                    className="plan-action-btn plan-action-btn--success"
                                    onClick={() => updateStatus(plan, "COMPLETED")}
                                  >
                                    Complete
                                  </button>
                                </>
                              )}
                              {["DRAFT", "PENDING_APPROVAL", "APPROVED"].includes(plan.status) && (
                                <button
                                  type="button"
                                  className="plan-action-btn plan-action-btn--danger"
                                  onClick={() => deletePlan(plan)}
                                >
                                  Delete
                                </button>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            <section className="trainer-card plans-overview-card" style={{ marginTop: 20 }}>
              <div className="plans-overview-head">
                <div className="trainer-card-title" style={{ marginBottom: 0 }}>
                  <span className="trainer-diamond" />
                  <div>
                    <h2>Training Plan Templates (9 Standard Templates)</h2>
                    <p>
                      3 Breeds × 3 Running Styles · Default 3-phase allocation: Foundation (30%, 15m recovery) · Build (45%, 20m recovery) · Peak (25%, 30m recovery)
                    </p>
                  </div>
                </div>
              </div>
              {catalogTemplates.length === 0 ? (
                <p className="training-empty">Loading training plan templates…</p>
              ) : (
                <div className="trainer-table-wrap">
                  <table className="plans-table">
                    <thead>
                      <tr>
                        <th>Template Name</th>
                        <th>Breed</th>
                        <th>Running Style</th>
                        <th>Intensity</th>
                        <th>Max HR</th>
                        <th>Max Speed</th>
                        <th>Rest / Wk</th>
                        <th>Target HRR</th>
                        <th>Base Dist.</th>
                        <th>3 Phases (Ratio · Rec)</th>
                      </tr>
                    </thead>
                    <tbody>
                      {catalogTemplates.map((tpl) => (
                        <tr key={tpl.templateId || tpl.templateCode}>
                          <td>
                            <div className="plan-name-strong">{formatTemplateName(tpl.templateName)}</div>
                          </td>
                          <td>{tpl.breed}</td>
                          <td>
                            <span className="plan-horse-badge">
                              {formatRunningStyle(tpl.runningStyle)}
                            </span>
                          </td>
                          <td>
                            <span className="training-status-pill">{tpl.intensity}</span>
                          </td>
                          <td>{tpl.maxHeartRate} bpm</td>
                          <td>{tpl.maxSpeed} km/h</td>
                          <td>{tpl.restDaysPerWeek} d/wk</td>
                          <td>{tpl.targetHrr} bpm</td>
                          <td>{Number(tpl.baseDistanceM || 0).toLocaleString()} m</td>
                          <td style={{ fontSize: 12, color: "#475569" }}>
                            {(tpl.phases || []).map((ph) => `${ph.phaseName} ${ph.phaseRatio}% (${ph.recoveryDurationMinutes}m)`).join(" · ")}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
            <StandardsCard />
            </>
          ) : (
            <form onSubmit={submit}>
              <div className="training-plan-layout">
                <div className="training-plan-left">
                  <section className="trainer-card plan-builder-card">
                    <div className="trainer-card-title">
                      <span className="trainer-diamond" />
                      <div>
                        <h2>Plan details</h2>
                        <p>Choose a horse and a groom allocated to you, and define the preparation period.</p>
                      </div>
                    </div>
                    <div className="training-plan-form-grid">
                      <label className="training-field">
                        <span className="training-field-title">1. Horse *</span>
                        <select
                          value={horseId}
                          onChange={(e) => setHorseId(e.target.value)}
                          required
                        >
                          <option value="">
                            {availableHorses.length === 0
                              ? "— No horse available without an active plan —"
                              : "Choose a horse"}
                          </option>
                          {availableHorses.map((h) => (
                            <option key={h.horseId} value={h.horseId}>
                              {h.horseName} · {h.registrationCode || `#${h.horseId}`} ({h.breed || "Racehorse"} · {h.status})
                            </option>
                          ))}
                        </select>
                      </label>

                      <label className="training-field">
                        <span className="training-field-title">
                          2. Training Plan Template *{templateLoading ? " · Loading…" : ""}
                        </span>
                        <select
                          value={selectedTemplateId}
                          onChange={(e) => {
                            const tpl = templateOptions.find(
                              (t) => String(t.templateId) === e.target.value,
                            );
                            if (tpl) {
                              applyTemplateToForm(
                                tpl,
                                startDate,
                                endDate,
                                templateRec?.lockedSurface || phases[0]?.surface || "TURF",
                              );
                            } else {
                              setSelectedTemplateId("");
                            }
                          }}
                          required
                        >
                          <option value="">Choose a training template</option>
                          {templateOptions.map((tpl) => {
                            const isRec = templateRec?.recommendedTemplate?.templateId === tpl.templateId;
                            return (
                              <option key={tpl.templateId} value={tpl.templateId}>
                                {formatTemplateName(tpl.templateName)} ({tpl.breed} · {formatRunningStyle(tpl.runningStyle)})
                                {isRec ? " ★ Recommended" : ""}
                              </option>
                            );
                          })}
                        </select>
                      </label>

                      {selectedHorse && (
                        <div
                          className="training-field training-field--wide selected-horse-strip"
                          style={{ marginTop: 2, flexDirection: "column", alignItems: "stretch", gap: "12px" }}
                        >
                          <div style={{ display: "flex", alignItems: "center", gap: "11px" }}>
                            <span className="training-horse-mark">♞</span>
                            <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: "2px" }}>
                              <strong style={{ color: "#202a49", fontSize: "13.5px" }}>{selectedHorse.horseName}</strong>
                              <span style={{ color: "#8e96ad", fontSize: "11.5px" }}>
                                {selectedHorse.registrationCode || `Horse #${selectedHorse.horseId}`} · {selectedHorse.breed || "Racehorse"}
                              </span>
                            </div>
                            <span className={`training-status-pill ${statusClass(selectedHorse.status)}`}>
                              {selectedHorse.status}
                            </span>
                          </div>
                          <div
                            style={{
                              display: "grid",
                              gridTemplateColumns: "repeat(3, minmax(0, 1fr))",
                              gap: "10px",
                            }}
                          >
                            <div
                              style={{
                                padding: "8px 12px",
                                borderRadius: "10px",
                                background: "#fff",
                                border: "1px solid #f2dee8",
                              }}
                            >
                              <div style={{ fontSize: "10.5px", color: "#8e96ad", fontWeight: 700, textTransform: "uppercase" }}>
                                Distance Type
                              </div>
                              <div style={{ fontSize: "13px", color: "#202a49", fontWeight: 700, marginTop: "2px" }}>
                                {templateLoading ? "Loading…" : formatDistanceType(templateRec?.distanceType)}
                              </div>
                            </div>
                            <div
                              style={{
                                padding: "8px 12px",
                                borderRadius: "10px",
                                background: "#fff",
                                border: "1px solid #f2dee8",
                              }}
                            >
                              <div style={{ fontSize: "10.5px", color: "#8e96ad", fontWeight: 700, textTransform: "uppercase" }}>
                                Running Style
                              </div>
                              <div style={{ fontSize: "13px", color: "#202a49", fontWeight: 700, marginTop: "2px" }}>
                                {templateLoading ? "Loading…" : formatRunningStyle(templateRec?.runningStyle)}
                              </div>
                            </div>
                            <div
                              style={{
                                padding: "8px 12px",
                                borderRadius: "10px",
                                background: "#fff",
                                border: "1px solid #f2dee8",
                              }}
                            >
                              <div style={{ fontSize: "10.5px", color: "#8e96ad", fontWeight: 700, textTransform: "uppercase" }}>
                                Preferred Surface
                              </div>
                              <div style={{ fontSize: "13px", color: "#202a49", fontWeight: 700, marginTop: "2px" }}>
                                {templateLoading ? "Loading…" : (templateRec?.lockedSurface === "DIRT" ? "Dirt" : "Turf")}
                              </div>
                            </div>
                          </div>
                          {!templateLoading && templateRec?.standard && (
                            <div className="plan-standard-strip">
                              <div><span>Age</span><strong>{templateRec.standard.ageMonths ?? "—"} months</strong></div>
                              <div>
                                <span>Expected weight</span>
                                <strong>{templateRec.standard.expectedWeightKg != null ? `${templateRec.standard.expectedWeightKg} kg` : "—"}</strong>
                              </div>
                              <div>
                                <span>Expected height</span>
                                <strong>{templateRec.standard.expectedHeightCm != null ? `${templateRec.standard.expectedHeightCm} cm` : "—"}</strong>
                              </div>
                              <div>
                                <span>Speed limit</span>
                                <strong>{Number(templateRec.standard.speedLimitKmh) > 0 ? `${templateRec.standard.speedLimitKmh} km/h` : "Not in training"}</strong>
                              </div>
                              {templateRec.standard.note && (
                                <p className={templateRec.standard.canTrain ? "" : "plan-standard-strip__stop"}>{templateRec.standard.note}</p>
                              )}
                            </div>
                          )}
                        </div>
                      )}

                      <label className="training-field training-field--wide">
                        Plan name
                        <input
                          value={planName}
                          onChange={(e) => setPlanName(e.target.value)}
                          maxLength={150}
                          required
                          placeholder="e.g. Tracen Cup preparation"
                        />
                      </label>

                      <label className="training-field">
                        <span className="training-field-title">Primary Groom *</span>
                        <select
                          value={groomId}
                          onChange={(e) => setGroomId(e.target.value)}
                          required
                        >
                          <option value="">
                            {grooms.length === 0
                              ? "— No groom assigned to you —"
                              : "Choose primary groom"}
                          </option>
                          {grooms.map((g) => (
                            <option
                              key={g.userId}
                              value={g.userId}
                              disabled={!g.eligible}
                            >
                              {g.fullName} ({g.activePlanCount}/2 active plans)
                              {!g.eligible ? " — Full" : ""}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label className="training-field">
                        <span className="training-field-title">Backup Groom *</span>
                        <select
                          value={backupGroomId}
                          onChange={(e) => setBackupGroomId(e.target.value)}
                          required
                        >
                          <option value="">Choose backup groom</option>
                          {grooms.map((g) => (
                            <option
                              key={g.userId}
                              value={g.userId}
                              disabled={String(g.userId) === String(groomId)}
                            >
                              {g.fullName}
                              {String(g.userId) === String(groomId) ? " (Selected as Primary)" : ""}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label className="training-field">
                        Start date *
                        <input
                          type="date"
                          value={startDate}
                          onChange={(e) => setStartDate(e.target.value)}
                          required
                        />
                      </label>
                      <label className="training-field">
                        End date *
                        <input
                          type="date"
                          value={endDate}
                          onChange={(e) => setEndDate(e.target.value)}
                          min={startDate}
                          required
                        />
                      </label>

                      {(templateRec || selectedTemplateId) && (
                        <>
                          {templateRec && (
                            <label className="training-field">
                              Preferred Surface
                              <input
                                type="text"
                                readOnly
                                value={templateRec.lockedSurface === "DIRT" ? "Dirt" : "Turf"}
                                style={{ backgroundColor: "#f1f5f9", color: "#334155", cursor: "not-allowed", fontWeight: 600 }}
                              />
                            </label>
                          )}
                          <label className="training-field">
                            Intensity
                            <select value={intensity} onChange={(e) => setIntensity(e.target.value)}>
                              <option value="LOW">LOW</option>
                              <option value="MEDIUM">MEDIUM</option>
                              <option value="HIGH">HIGH</option>
                            </select>
                          </label>
                          <label className="training-field">
                            Max Heart Rate (bpm)
                            <input
                              type="number"
                              min="100"
                              max="260"
                              value={maxHeartRate}
                              onChange={(e) => setMaxHeartRate(e.target.value)}
                              placeholder="e.g. 205"
                            />
                          </label>
                          <label className="training-field">
                            Max Speed Ceiling (km/h)
                            <input
                              type="number"
                              min="1"
                              max="90"
                              step="0.1"
                              value={maxSpeed}
                              onChange={(e) => setMaxSpeed(e.target.value)}
                              placeholder="e.g. 64.0"
                            />
                          </label>
                          <label className="training-field">
                            Rest Days / Week
                            <input
                              type="number"
                              min="0"
                              max="6"
                              value={restDaysPerWeek}
                              onChange={(e) => setRestDaysPerWeek(e.target.value)}
                            />
                          </label>
                          <label className="training-field">
                            Target Recovery HR (bpm)
                            <input
                              type="number"
                              min="40"
                              max="220"
                              value={targetHrr}
                              onChange={(e) => setTargetHrr(e.target.value)}
                              placeholder="e.g. 115"
                            />
                          </label>
                          <div
                            className="training-field training-field--wide"
                            style={{
                              padding: "10px 14px",
                              borderRadius: "10px",
                              background: "#fff8fb",
                              border: "1px solid #f2dee8",
                              color: "#626a83",
                              fontSize: "12px",
                              fontWeight: 500,
                            }}
                          >
                            Submitting this plan sends it to the horse owner for review. Once approved by the owner, the plan becomes <strong>Active</strong> and its training schedule is automatically generated.
                          </div>
                        </>
                      )}
                    </div>
                  </section>

                <section className="trainer-card plan-builder-card phases-card">
                  <div className="trainer-card-title">
                    <span className="trainer-diamond" />
                    <div>
                      <h2>Fixed 3 Phases (Foundation · Build · Peak)</h2>
                      <p>
                        Total phase ratios must equal 100%. Phase dates & sessions are auto-calculated from plan start/end dates.
                      </p>
                    </div>
                  </div>
                  {usesRatioMode && (
                    <div
                      style={{
                        marginBottom: "14px",
                        padding: "10px 14px",
                        borderRadius: "10px",
                        background: totalRatio === 100 ? "#ecfdf5" : "#fef2f2",
                        border: `1px solid ${totalRatio === 100 ? "#86efac" : "#fca5a5"}`,
                        color: totalRatio === 100 ? "#166534" : "#991b1b",
                        display: "flex",
                        justifyContent: "space-between",
                        alignItems: "center",
                        flexWrap: "wrap",
                        gap: "8px",
                        fontSize: "13px",
                        fontWeight: 600,
                      }}
                    >
                      <span>
                        {totalRatio === 100
                          ? `✓ 3-Phase Time Allocation: ${phases.map((p) => `${p.phaseRatio}%`).join(" / ")} = 100%`
                          : `⚠️ Phase ratios must equal 100% (currently ${totalRatio}%)`}
                      </span>
                      {ratioPreview.dayCounts && (
                        <span>
                          Days split ({ratioPreview.totalDays} total): Foundation {ratioPreview.dayCounts[0]}d · Build {ratioPreview.dayCounts[1]}d · Peak {ratioPreview.dayCounts[2]}d
                        </span>
                      )}
                    </div>
                  )}

                  <div className="training-phase-list">
                    {phases.map((phase, index) => (
                      <article
                        className="training-phase"
                        key={phase.id || `${index}-${phase.phaseName}`}
                      >
                        <div className="training-phase-heading">
                          <span className="training-phase-number">
                            {String(index + 1).padStart(2, "0")}
                          </span>
                          <div>
                            <strong>
                              {phase.phaseCode || `Phase ${index + 1}`}
                              {phase.phaseName && phase.phaseName !== `Phase ${index + 1}`
                                ? ` · ${phase.phaseName}`
                                : ""}
                            </strong>
                            {phase.startDate && phase.endDate && (
                              <span>
                                {dateLabel(phase.startDate)} –{" "}
                                {dateLabel(phase.endDate)}
                              </span>
                            )}
                          </div>
                        </div>
                        <div className="training-phase-fields">
                          <label className="training-field">
                            Phase name
                            <input
                              value={phase.phaseName}
                              onChange={(e) =>
                                updatePhase(index, "phaseName", e.target.value)
                              }
                              maxLength={100}
                              required
                              placeholder="e.g. Foundation, Build, Peak"
                            />
                          </label>
                          <label className="training-field">
                            Phase ratio (%)
                            <input
                              type="number"
                              min="1"
                              max="98"
                              value={phase.phaseRatio ?? ""}
                              onChange={(e) =>
                                updatePhase(index, "phaseRatio", e.target.value === "" ? "" : Number(e.target.value))
                              }
                              placeholder="e.g. 30"
                            />
                          </label>
                          <label className="training-field">
                            Recovery duration (min) *
                            <input
                              type="number"
                              min="5"
                              max="120"
                              required
                              value={phase.recoveryDurationMinutes ?? 15}
                              onChange={(e) =>
                                updatePhase(index, "recoveryDurationMinutes", e.target.value === "" ? "" : Number(e.target.value))
                              }
                              placeholder="e.g. 15"
                            />
                          </label>
                          <label className="training-field">
                            Distance (m)
                            <input
                              type="number"
                              min="1"
                              value={phase.distance}
                              onChange={(e) =>
                                updatePhase(index, "distance", e.target.value)
                              }
                              required
                              placeholder="e.g. 1600"
                            />
                          </label>
                          <label className="training-field">
                            Workload
                            <input
                              value={phase.workload}
                              onChange={(e) =>
                                updatePhase(index, "workload", e.target.value)
                              }
                              maxLength={50}
                              placeholder="e.g. Moderate / Interval"
                            />
                          </label>
                          <label className="training-field">
                            Surface
                            <select
                              value={normalizeUiSurface(templateRec?.lockedSurface || phase.surface)}
                              disabled={Boolean(templateRec?.lockedSurface)}
                              onChange={(e) =>
                                updatePhase(index, "surface", e.target.value)
                              }
                            >
                              <option value="TURF">Turf</option>
                              <option value="DIRT">Dirt</option>
                            </select>
                          </label>
                          <label className="training-field">
                            Sessions / week (Auto · {phase.phaseRatio ?? 0}%)
                            <input
                              type="number"
                              min="1"
                              max="365"
                              value={phase.sessionsPerWeek}
                              readOnly
                              title="Auto-calculated from phase ratio (%) and total plan days"
                              style={{ backgroundColor: "#f1f5f9", color: "#334155", cursor: "not-allowed", fontWeight: 600 }}
                              required
                            />
                          </label>
                          <label className="training-field">
                            Heart-rate ceiling (bpm)
                            <input
                              type="number"
                              min="100"
                              max="260"
                              value={phase.heartRateCeiling}
                              onChange={(e) =>
                                updatePhase(
                                  index,
                                  "heartRateCeiling",
                                  e.target.value,
                                )
                              }
                              required
                              placeholder="e.g. 180"
                            />
                          </label>
                          <label className="training-field">
                            Rest between sessions (min)
                            <input
                              type="number"
                              min="15"
                              max="720"
                              value={phase.minGapMinutes ?? ""}
                              onChange={(e) =>
                                updatePhase(
                                  index,
                                  "minGapMinutes",
                                  e.target.value,
                                )
                              }
                              placeholder="Default 90"
                            />
                          </label>
                          <label className="training-field">
                            Target speed (km/h) *
                            <input
                              type="number"
                              min="1"
                              max="90"
                              step="0.1"
                              required
                              value={phase.targetVelocity ?? ""}
                              onChange={(e) =>
                                updatePhase(
                                  index,
                                  "targetVelocity",
                                  e.target.value,
                                )
                              }
                              placeholder="e.g. 55"
                            />
                          </label>
                          <label className="training-field">
                            Phase start (Auto)
                            <input
                              type="date"
                              value={phase.startDate}
                              readOnly
                              title="Auto-calculated from active plan start date and phase ratios"
                              style={{ backgroundColor: "#f1f5f9", color: "#334155", cursor: "not-allowed", fontWeight: 600 }}
                              required
                            />
                          </label>
                          <label className="training-field">
                            Phase end (Auto)
                            <input
                              type="date"
                              value={phase.endDate}
                              readOnly
                              title="Auto-calculated from active plan end date and phase ratios"
                              style={{ backgroundColor: "#f1f5f9", color: "#334155", cursor: "not-allowed", fontWeight: 600 }}
                              required
                            />
                          </label>
                        </div>
                      </article>
                    ))}
                  </div>
                  <div className="training-plan-actions">
                    <button
                      className="training-save-button"
                      type="submit"
                      disabled={saving || loading || (usesRatioMode && totalRatio !== 100)}
                    >
                      {saving ? "Submitting…" : "Save & Send to Owner"}
                    </button>
                    <button
                      className="training-back-button"
                      type="button"
                      onClick={() => {
                        setError("");
                        setIsCreating(false);
                      }}
                    >
                      Cancel
                    </button>
                  </div>
                </section>
              </div>

              <aside className="training-plan-right">
                <section className="trainer-card training-settings-card">
                  <div className="trainer-card-title">
                    <span className="trainer-diamond" />
                    <div>
                      <h2>Sample session settings</h2>
                      <p>Current selected horse, groom and phase</p>
                    </div>
                  </div>
                  <div className="training-setting-row">
                    <span>Distance</span>
                    <strong>
                      {phases[0]?.distance
                        ? `${Number(phases[0].distance).toLocaleString()} m`
                        : "—"}
                    </strong>
                  </div>
                  <div className="training-setting-row">
                    <span>Workload</span>
                    <strong>{phases[0]?.workload || "Not set"}</strong>
                  </div>
                  <div className="training-setting-row">
                    <span>Surface</span>
                    <strong>
                      {{
                        TURF: "Turf",
                        DIRT: "Dirt",
                      }[normalizeUiSurface(phases[0]?.surface)] || "—"}
                    </strong>
                  </div>
                  <div className="training-setting-row">
                    <span>Sessions / week</span>
                    <strong>{phases[0]?.sessionsPerWeek || "—"}</strong>
                  </div>
                  <div className="training-setting-row">
                    <span>Heart rate ceiling</span>
                    <strong>
                      {phases[0]?.heartRateCeiling
                        ? `${phases[0].heartRateCeiling} bpm`
                        : "—"}
                    </strong>
                  </div>
                  <div className="training-setting-row">
                    <span>Horse</span>
                    <strong>
                      {selectedHorse?.horseName || "Choose a horse"}
                    </strong>
                  </div>
                  <div className="training-setting-row">
                    <span>Assigned Groom</span>
                    <strong>
                      {selectedGroom?.fullName || "Choose a groom"}
                    </strong>
                  </div>
                  <div className="training-medical-note">
                    <strong>Groom assignment rule</strong>
                    <p>
                      Each Groom can only handle up to 2 active training plans at a time, and cannot be transferred to another Head Trainer while handling at least 1 active plan.
                    </p>
                  </div>
                  <div className="training-rule-note">
                    The backend prevents activation if the horse is not
                    trainable, is under an active training lock, or already has
                    another active plan.
                  </div>
                </section>
              </aside>
            </div>
          </form>
          )}
        </section>
      </main>

      {(viewingPlan || viewingLoading) && (
            <div className="plan-modal-backdrop" onClick={() => setViewingPlan(null)}>
              <div className="plan-modal-box" onClick={(e) => e.stopPropagation()}>
                {viewingLoading ? (
                  <p className="training-empty">Loading plan details…</p>
                ) : viewingPlan ? (
                  <>
                    <div className="plan-modal-header">
                      <div>
                        <h2>{viewingPlan.planName}</h2>
                        <p style={{ marginTop: 4, color: "#475569", fontSize: 13.5 }}>
                          ♞ <strong>{viewingPlan.horseName}</strong> · 👤 Groom: <strong>{viewingPlan.groomName || "Unassigned"}</strong> · 📅 {dateLabel(viewingPlan.startDate)} – {dateLabel(viewingPlan.endDate)}
                        </p>
                      </div>
                      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                        <span className={`training-status-pill ${statusClass(viewingPlan.status)}`}>
                          {PLAN_STATUS_LABEL[viewingPlan.status] || viewingPlan.status}
                        </span>
                        <button
                          type="button"
                          className="plan-modal-close"
                          onClick={() => setViewingPlan(null)}
                        >
                          ×
                        </button>
                      </div>
                    </div>
                    {viewingPlan.pendingUpdate && (
                      <div
                        style={{
                          marginBottom: 12,
                          padding: "10px 14px",
                          borderRadius: 10,
                          background: "#fffbeb",
                          border: "1px solid #f59e0b",
                          color: "#92400e",
                          fontSize: "0.86rem",
                        }}
                      >
                        <strong>Update pending owner approval:</strong> Proposed changes (
                        {viewingPlan.pendingUpdate.planName || viewingPlan.planName}
                        {viewingPlan.pendingUpdate.groomName
                          ? `, groom ${viewingPlan.pendingUpdate.groomName}`
                          : ""}
                        , {viewingPlan.pendingUpdate.phases?.length || 0} phase(s)) are waiting for the owner.
                        The current plan remains active until approved (auto-declined after 2 days).
                      </div>
                    )}
                    {viewingPlan.ownerNote && (
                      <div className="plan-modal-owner-feedback">
                        <div className="plan-modal-owner-feedback-title">
                          <span>💬</span> Owner Feedback / Note
                        </div>
                        <div className="plan-modal-owner-feedback-body">
                          "{viewingPlan.ownerNote}"
                        </div>
                      </div>
                    )}
                    <div className="plan-modal-phases" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
                      {(viewingPlan.phases || []).map((ph, idx) => (
                        <div key={ph.phaseId || idx} className="plan-phase-card-detail">
                          <div className="plan-phase-card-top">
                            <strong>Phase {idx + 1}: {ph.phaseName}</strong>
                            <span>📅 {dateLabel(ph.startDate)} – {dateLabel(ph.endDate)}</span>
                          </div>
                          <div className="plan-phase-card-grid">
                            <div className="plan-phase-card-item">
                              <span>Distance</span>
                              <strong>{Number(ph.distance || 0).toLocaleString()} m</strong>
                            </div>
                            <div className="plan-phase-card-item">
                              <span>Surface</span>
                              <strong>{ph.surface}</strong>
                            </div>
                            <div className="plan-phase-card-item">
                              <span>Sessions / Wk</span>
                              <strong>{ph.sessionsPerWeek}</strong>
                            </div>
                            <div className="plan-phase-card-item">
                              <span>HR Ceiling</span>
                              <strong>{ph.heartRateCeiling} bpm</strong>
                            </div>
                            <div className="plan-phase-card-item">
                              <span>Workload</span>
                              <strong>{ph.workload || "—"}</strong>
                            </div>
                            <div className="plan-phase-card-item">
                              <span>Min Rest Gap</span>
                              <strong>{ph.minGapMinutes || 90} min</strong>
                            </div>
                            {ph.targetVelocity && (
                              <div className="plan-phase-card-item">
                                <span>Target Speed</span>
                                <strong>{ph.targetVelocity} km/h</strong>
                              </div>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>

                    <div className="plan-modal-actions">
                      {viewingPlan.status !== "COMPLETED" && (
                        <button
                          type="button"
                          className="training-btn training-btn--primary"
                          onClick={() => {
                            const p = viewingPlan;
                            setViewingPlan(null);
                            startEditing(p);
                          }}
                        >
                          ✏️ Update Plan Details
                        </button>
                      )}
                      <button
                        type="button"
                        className="training-btn training-btn--ghost"
                        onClick={() => setViewingPlan(null)}
                      >
                        Close
                      </button>
                    </div>
                  </>
                ) : null}
              </div>
            </div>
          )}

          {editingPlan && (
            <div className="plan-modal-backdrop" onClick={() => !editSaving && setEditingPlan(null)}>
              <div className="plan-modal-box" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 840 }}>
                <div className="plan-modal-header">
                  <div>
                    <h2>Update Training Plan</h2>
                    <p style={{ color: "#64748b", fontSize: "13px", marginTop: 4 }}>
                      You can modify the assigned Groom and phase settings. Horse and plan duration are locked.
                    </p>
                  </div>
                  <button
                    type="button"
                    className="plan-modal-close"
                    onClick={() => !editSaving && setEditingPlan(null)}
                    disabled={editSaving}
                  >
                    ×
                  </button>
                </div>

                {editError && (
                  <div className="training-alert training-alert--error" style={{ marginBottom: 12 }}>
                    {editError}
                  </div>
                )}

                <form onSubmit={handleUpdateSubmit} style={{ display: "flex", flexDirection: "column", gap: 18 }}>
                  <div className="plan-locked-info">
                    <div className="plan-locked-item">
                      <span>Horse (Locked)</span>
                      <strong>♞ {editingPlan.horseName} <span className="plan-locked-badge">🔒 Locked</span></strong>
                    </div>
                    <div className="plan-locked-item">
                      <span>Plan Duration (Locked)</span>
                      <strong>📅 {dateLabel(editingPlan.startDate)} – {dateLabel(editingPlan.endDate)} <span className="plan-locked-badge">🔒 Locked</span></strong>
                    </div>
                  </div>

                  <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))", gap: 14 }}>
                    <label style={{ display: "flex", flexDirection: "column", gap: 6, fontWeight: 600, fontSize: 13 }}>
                      Plan Name
                      <input
                        type="text"
                        required
                        maxLength={150}
                        value={editPlanName}
                        onChange={(e) => setEditPlanName(e.target.value)}
                        style={{ padding: "8px 12px", borderRadius: 8, border: "1px solid #cbd5e1" }}
                      />
                    </label>

                    <label style={{ display: "flex", flexDirection: "column", gap: 6, fontWeight: 600, fontSize: 13 }}>
                      Assigned Groom
                      <select
                        required
                        value={editGroomId}
                        onChange={(e) => setEditGroomId(e.target.value)}
                        style={{ padding: "8px 12px", borderRadius: 8, border: "1px solid #cbd5e1" }}
                      >
                        <option value="">Select a groom</option>
                        {grooms.map((g) => {
                          const isCurrent = String(g.userId) === String(editingPlan.groomId);
                          return (
                            <option key={g.userId} value={g.userId} disabled={!g.eligible && !isCurrent}>
                              {g.fullName} ({g.activePlanCount} active plans){isCurrent ? " (Current)" : ""}
                            </option>
                          );
                        })}
                      </select>
                    </label>
                  </div>

                  <div>
                    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
                      <h3 style={{ fontSize: 15, fontWeight: 700, color: "#1e1b4b" }}>
                        Fixed Training Phases ({editPhases.length})
                      </h3>
                      {editPhases.length === 3 && (
                        <span style={{ fontSize: 12, color: "#64748b", fontWeight: 600 }}>
                          Total ratio: {editPhases.reduce((s, p) => s + Number(p.phaseRatio || 0), 0)}% (must be 100%)
                        </span>
                      )}
                    </div>

                    <div className="plan-phase-edit-list">
                      {editPhases.map((ph, idx) => (
                        <div key={ph.phaseId || idx} className="plan-phase-edit-card">
                          <div className="plan-phase-edit-header">
                            <strong>Phase {idx + 1}: {ph.phaseName}</strong>
                          </div>

                          <div className="plan-phase-edit-grid">
                            <label>
                              Phase Name
                              <input
                                type="text"
                                required
                                value={ph.phaseName}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, phaseName: val } : p));
                                }}
                              />
                            </label>

                            {editPhases.length === 3 && (
                              <label>
                                Phase Ratio (%)
                                <input
                                  type="number"
                                  min="1"
                                  max="98"
                                  required
                                  value={ph.phaseRatio ?? ""}
                                  onChange={(e) => {
                                    const val = e.target.value === "" ? "" : Number(e.target.value);
                                    const next = editPhases.map((p, i) => i === idx ? { ...p, phaseRatio: val } : p);
                                    if (next.every((p) => p.phaseRatio !== "" && p.phaseRatio != null)) {
                                      setEditPhases(computeRatioPhases(editingPlan.startDate, editingPlan.endDate, next).phases);
                                    } else {
                                      setEditPhases(next);
                                    }
                                  }}
                                />
                              </label>
                            )}

                            <label>
                              Recovery Duration (min)
                              <input
                                type="number"
                                min="5"
                                max="120"
                                required
                                value={ph.recoveryDurationMinutes ?? 15}
                                onChange={(e) => {
                                  const val = e.target.value === "" ? "" : Number(e.target.value);
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, recoveryDurationMinutes: val } : p));
                                }}
                              />
                            </label>

                            <label>
                              Start Date (Auto)
                              <input
                                type="date"
                                required
                                readOnly
                                style={{ backgroundColor: "#f1f5f9", color: "#334155", cursor: "not-allowed", fontWeight: 600 }}
                                value={ph.startDate}
                              />
                            </label>

                            <label>
                              End Date (Auto)
                              <input
                                type="date"
                                required
                                readOnly
                                style={{ backgroundColor: "#f1f5f9", color: "#334155", cursor: "not-allowed", fontWeight: 600 }}
                                value={ph.endDate}
                              />
                            </label>

                            <label>
                              Distance (m)
                              <input
                                type="number"
                                required
                                min="100"
                                max="5000"
                                value={ph.distance}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, distance: val } : p));
                                }}
                              />
                            </label>

                            <label>
                              Surface
                              <select
                                value={normalizeUiSurface(ph.surface)}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, surface: val } : p));
                                }}
                              >
                                <option value="TURF">Turf</option>
                                <option value="DIRT">Dirt</option>
                              </select>
                            </label>

                            <label>
                              Sessions / Week (Auto)
                              <input
                                type="number"
                                required
                                readOnly
                                min="1"
                                max="365"
                                style={{ backgroundColor: "#f1f5f9", color: "#334155", cursor: "not-allowed", fontWeight: 600 }}
                                value={ph.sessionsPerWeek}
                              />
                            </label>

                            <label>
                              HR Ceiling (bpm)
                              <input
                                type="number"
                                required
                                min="100"
                                max="260"
                                value={ph.heartRateCeiling}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, heartRateCeiling: val } : p));
                                }}
                              />
                            </label>

                            <label>
                              Workload
                              <input
                                type="text"
                                placeholder="e.g. 80% VO2 max"
                                value={ph.workload || ""}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, workload: val } : p));
                                }}
                              />
                            </label>

                            <label>
                              Min Rest Gap (min)
                              <input
                                type="number"
                                min="15"
                                max="720"
                                value={ph.minGapMinutes || 90}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, minGapMinutes: val } : p));
                                }}
                              />
                            </label>

                            <label>
                              Target Speed (km/h)
                              <input
                                type="number"
                                step="0.1"
                                min="1.0"
                                max="90.0"
                                required
                                placeholder="e.g. 55"
                                value={ph.targetVelocity || ""}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, targetVelocity: val } : p));
                                }}
                              />
                            </label>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>

                  {editError && (
                    <div className="training-alert training-alert--error" style={{ margin: "10px 0 0" }}>
                      ⚠️ {editError}
                    </div>
                  )}

                  <div className="plan-modal-actions">
                    <button
                      type="button"
                      className="training-btn training-btn--ghost"
                      onClick={() => setEditingPlan(null)}
                      disabled={editSaving}
                    >
                      Cancel
                    </button>
                    <button
                      type="submit"
                      className="training-btn training-btn--primary"
                      disabled={editSaving || editPhases.length === 0}
                    >
                      {editSaving ? "Saving & Notifying..." : "💾 Save Changes & Notify Owner"}
                    </button>
                  </div>
                </form>
              </div>
            </div>
          )}
    </div>
  );
}
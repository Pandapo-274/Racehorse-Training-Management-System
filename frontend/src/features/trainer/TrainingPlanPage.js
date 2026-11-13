import { useCallback, useEffect, useMemo, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import { useSignOutModal } from "../auth/SignOutModal";
import ConfirmModal from "../common/ConfirmModal";
import { isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import {
  changeTrainingPlanStatus,
  deleteTrainingPlan,
  createTrainingPlan,
  updateTrainingPlan,
  listTrainingPlans,
  listPlanGrooms,
  getTrainingPlan,
} from "./trainingPlanApi";
import "./trainer.css";
import "./training-plan.css";

let phaseIdCounter = 1;
function generatePhaseId() {
  return `phase-${Date.now()}-${phaseIdCounter++}`;
}

const initialPhases = [
  {
    id: "init-phase-1",
    phaseName: "Base",
    distance: "",
    workload: "",
    surface: "DIRT",
    sessionsPerWeek: "",
    heartRateCeiling: "",
    minGapMinutes: "",
    targetVelocity: "",
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

function statusClass(status) {
  return String(status || "DRAFT")
    .toLowerCase()
    .replace(/[^a-z-]/g, "-");
}

function emptyPhase(index, startDate, endDate) {
  const defaultNames = ["Base", "Build", "Peak"];
  return {
    id: generatePhaseId(),
    phaseName: defaultNames[index] || `Phase ${index + 1}`,
    distance: "",
    workload: "",
    surface: "DIRT",
    sessionsPerWeek: "",
    heartRateCeiling: "",
    minGapMinutes: "",
    targetVelocity: "",
    startDate: startDate || "",
    endDate: endDate || "",
  };
}

function optionalNumber(value) {
  return value === "" || value == null ? null : Number(value);
}

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
  const [planName, setPlanName] = useState(savedDraft?.planName || "");
  const [startDate, setStartDate] = useState(savedDraft?.startDate || "");
  const [endDate, setEndDate] = useState(savedDraft?.endDate || "");
  const [phases, setPhases] = useState(
    savedDraft?.phases && savedDraft.phases.length > 0
      ? savedDraft.phases
      : initialPhases,
  );
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [phaseNotice, setPhaseNotice] = useState("");
  const [filter, setFilter] = useState("ALL");

  // State cho việc cập nhật giáo án (Edit training plan)
  const [editingPlan, setEditingPlan] = useState(null);
  const [editGroomId, setEditGroomId] = useState("");
  const [editPlanName, setEditPlanName] = useState("");
  const [editPhases, setEditPhases] = useState([]);
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState("");
  const [deleteConfirm, setDeleteConfirm] = useState({ isOpen: false, plan: null });

  useEffect(() => {
    saveDraft({
      horseId,
      groomId,
      planName,
      startDate,
      endDate,
      phases,
    });
  }, [horseId, groomId, planName, startDate, endDate, phases]);

  const load = useCallback(
    async (signal) => {
      setLoading(true);
      setError("");
      try {
        const [horseRows, planRows, groomRows] = await Promise.all([
          listHorses(signal),
          listTrainingPlans({ signal }),
          listPlanGrooms(signal).catch((err) => {
            console.warn("Failed to load plan grooms:", err);
            return [];
          }),
        ]);
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

  const startEditing = (plan) => {
    setEditingPlan(plan);
    setEditGroomId(plan.groomId ? String(plan.groomId) : "");
    setEditPlanName(plan.planName || "");
    setEditPhases(
      (plan.phases || []).map((p) => ({
        phaseId: p.phaseId,
        phaseName: p.phaseName,
        startDate: p.startDate,
        endDate: p.endDate,
        distance: p.distance,
        surface: p.surface,
        sessionsPerWeek: p.sessionsPerWeek,
        heartRateCeiling: p.heartRateCeiling,
        workload: p.workload || "",
        minGapMinutes: p.minGapMinutes || 90,
        targetVelocity: p.targetVelocity ?? "",
      })),
    );
    setEditError("");
  };

  const handleUpdateSubmit = async (e) => {
    e.preventDefault();
    if (!editingPlan) return;
    if (!editGroomId) {
      setEditError("Please select an assigned groom.");
      return;
    }
    setEditSaving(true);
    setEditError("");
    try {
      const payload = {
        groomId: Number(editGroomId),
        planName: editPlanName.trim() || editingPlan.planName,
        phases: editPhases.map((p) => ({
          phaseId: p.phaseId || null,
          phaseName: p.phaseName,
          startDate: p.startDate,
          endDate: p.endDate,
          distance: Number(p.distance),
          surface: p.surface,
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

  const updatePhase = (index, field, value) =>
    setPhases((current) =>
      current.map((phase, i) =>
        i === index ? { ...phase, [field]: value } : phase,
      ),
    );
  const addPhase = () => {
    if (phases.length >= 12) return;
    const nextNum = phases.length + 1;
    const newPhase = emptyPhase(phases.length, startDate, endDate);
    setPhases((current) => [...current, newPhase]);
    const msg = `Phase ${nextNum} added successfully!`;
    setNotice(msg);
    setPhaseNotice(msg);
    setTimeout(() => {
      setPhaseNotice((prev) => (prev === msg ? "" : prev));
    }, 4000);
  };
  const removePhase = (index) =>
    setPhases((current) => {
      if (current.length <= 1) return current;
      const filtered = current.filter((_, i) => i !== index);
      return filtered.map((p, i) => {
        if (/^Phase \d+$/i.test(p.phaseName)) {
          return { ...p, phaseName: `Phase ${i + 1}` };
        }
        return p;
      });
    });

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
      setError("Select an assigned groom before saving the plan.");
      return;
    }
    if (endDate < startDate) {
      setError("Plan end date must be on or after the start date.");
      return;
    }
    const payload = {
      horseId: Number(horseId),
      groomId: Number(groomId),
      planName: planName.trim(),
      startDate,
      endDate,
      phases: phases.map((p) => ({
        phaseName: p.phaseName.trim(),
        distance: p.distance === "" ? null : Number(p.distance),
        workload: p.workload.trim() || null,
        surface: p.surface,
        sessionsPerWeek:
          p.sessionsPerWeek === "" ? null : Number(p.sessionsPerWeek),
        heartRateCeiling:
          p.heartRateCeiling === "" ? null : Number(p.heartRateCeiling),
        minGapMinutes: optionalNumber(p.minGapMinutes),
        targetVelocity: optionalNumber(p.targetVelocity),
        startDate: p.startDate,
        endDate: p.endDate,
      })),
    };
    setSaving(true);
    try {
      const created = await createTrainingPlan(payload);
      setNotice(
        `Training plan “${created.planName}” was saved as a draft. Send it to the owner for approval before activating it.`,
      );
      clearDraft();
      setPlanName("");
      setHorseId("");
      setGroomId("");
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
            {!isCreating ? (
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
            ) : (
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
            <span className="training-plan-target">
              Target: Tracen Cup · 22 Nov 2026
            </span>
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
            <section className="trainer-card plans-overview-card">
              <div className="plans-overview-header">
                <div className="trainer-card-title" style={{ marginBottom: 0 }}>
                  <span className="trainer-diamond" />
                  <div>
                    <h2>Managed training plans</h2>
                    <p>
                      Showing {visiblePlans.length} of {plans.length} plan{plans.length === 1 ? "" : "s"} managed by {myUser?.fullName || "you"}
                    </p>
                  </div>
                </div>
                <div className="training-plan-filter">
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
                        <th>Phases</th>
                        <th>Status</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {visiblePlans.map((plan) => (
                        <tr key={plan.planId}>
                          <td>
                            <div className="plan-cell-main">
                              <strong>{plan.planName}</strong>
                              <small>Plan #{plan.planId}</small>
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
                            <span className="plan-period-text">
                              {dateLabel(plan.startDate)} – {dateLabel(plan.endDate)}
                            </span>
                          </td>
                          <td>
                            <span className="plan-phase-count">
                              {plan.phaseCount} phase{plan.phaseCount === 1 ? "" : "s"}
                            </span>
                          </td>
                          <td>
                            <span className={`training-status-pill ${statusClass(plan.status)}`}>
                              {PLAN_STATUS_LABEL[plan.status] || plan.status}
                            </span>
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
                        <span className="training-field-title">Horse</span>
                        <select
                          value={horseId}
                          onChange={(e) => setHorseId(e.target.value)}
                          required
                        >
                          <option value="">Choose a horse</option>
                          {horses.map((h) => {
                            const ongoing = plans.find(
                              (p) => p.horseId === h.horseId && !["COMPLETED", "CANCELLED"].includes(p.status)
                            );
                            return (
                              <option key={h.horseId} value={h.horseId} disabled={Boolean(ongoing)}>
                                {h.horseName} · {h.registrationCode || `#${h.horseId}`} ({h.status})
                                {ongoing ? ` — In progress (Plan #${ongoing.planId}: ${PLAN_STATUS_LABEL[ongoing.status] || ongoing.status})` : ""}
                              </option>
                            );
                          })}
                        </select>
                      </label>
                      <label className="training-field">
                        <span className="training-field-title">Assigned Groom</span>
                        <select
                          value={groomId}
                          onChange={(e) => setGroomId(e.target.value)}
                          required
                        >
                          <option value="">
                            {grooms.length === 0
                              ? "— No groom assigned to you —"
                              : "Choose a groom"}
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
                        Start date
                        <input
                          type="date"
                          value={startDate}
                          onChange={(e) => {
                            const val = e.target.value;
                            setStartDate(val);
                            setPhases((current) => {
                              if (current.length === 1 && (!current[0].startDate || current[0].startDate === startDate)) {
                                return [{ ...current[0], startDate: val }];
                              }
                              return current;
                            });
                          }}
                          required
                        />
                      </label>
                      <label className="training-field">
                        End date
                        <input
                          type="date"
                          value={endDate}
                          onChange={(e) => {
                            const val = e.target.value;
                            setEndDate(val);
                            setPhases((current) => {
                              if (current.length === 1 && (!current[0].endDate || current[0].endDate === endDate)) {
                                return [{ ...current[0], endDate: val }];
                              }
                              return current;
                            });
                          }}
                          min={startDate}
                          required
                        />
                      </label>
                    </div>
                    {selectedHorse && (
                      <div className="selected-horse-strip">
                        <span className="training-horse-mark">♞</span>
                        <div>
                          <strong>{selectedHorse.horseName}</strong>
                          <span>
                            {selectedHorse.registrationCode ||
                              `Horse #${selectedHorse.horseId}`}{" "}
                            · {selectedHorse.status}
                          </span>
                        </div>
                        <span
                          className={`training-status-pill ${statusClass(selectedHorse.status)}`}
                        >
                          {selectedHorse.status}
                        </span>
                      </div>
                    )}
                  </section>

                <section className="trainer-card plan-builder-card phases-card">
                  <div className="trainer-card-title">
                    <span className="trainer-diamond" />
                    <div>
                      <h2>Phases</h2>
                      <p>
                        Build the programme from base work to race-day
                        simulation.
                      </p>
                    </div>
                    <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
                      {phaseNotice && (
                        <span
                          className="phase-notice-badge"
                          style={{
                            color: "#2e7d32",
                            backgroundColor: "#e8f5e9",
                            border: "1px solid #c8e6c9",
                            padding: "4px 10px",
                            borderRadius: "8px",
                            fontSize: "12px",
                            fontWeight: "600",
                          }}
                          role="status"
                        >
                          ✓ {phaseNotice}
                        </span>
                      )}
                      <button
                        type="button"
                        className="training-add-phase"
                        onClick={addPhase}
                        disabled={phases.length >= 12}
                      >
                        + Add phase
                      </button>
                    </div>
                  </div>
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
                              Phase {index + 1}
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
                          {phases.length > 1 && (
                            <button
                              type="button"
                              className="training-remove-phase"
                              onClick={() => removePhase(index)}
                              aria-label={`Remove phase ${index + 1}`}
                            >
                              ×
                            </button>
                          )}
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
                              placeholder="e.g. Base, Build, Peak"
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
                              placeholder="e.g. 3200"
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
                              placeholder="e.g. 60% VO2 max"
                            />
                          </label>
                          <label className="training-field">
                            Surface
                            <select
                              value={phase.surface}
                              onChange={(e) =>
                                updatePhase(index, "surface", e.target.value)
                              }
                            >
                              <option value="TURF">Turf</option>
                              <option value="DIRT">Dirt</option>
                              <option value="SYNTHETIC">Synthetic dirt</option>
                            </select>
                          </label>
                          <label className="training-field">
                            Sessions / week
                            <input
                              type="number"
                              min="1"
                              max="14"
                              value={phase.sessionsPerWeek}
                              onChange={(e) =>
                                updatePhase(
                                  index,
                                  "sessionsPerWeek",
                                  e.target.value,
                                )
                              }
                              required
                              placeholder="e.g. 3"
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
                            Target speed (m/s)
                            <input
                              type="number"
                              min="1"
                              max="25"
                              step="0.1"
                              value={phase.targetVelocity ?? ""}
                              onChange={(e) =>
                                updatePhase(
                                  index,
                                  "targetVelocity",
                                  e.target.value,
                                )
                              }
                              placeholder="Optional, e.g. 10"
                            />
                          </label>
                          <label className="training-field">
                            Phase start
                            <input
                              type="date"
                              value={phase.startDate}
                              min={startDate || undefined}
                              max={endDate || undefined}
                              onChange={(e) =>
                                updatePhase(index, "startDate", e.target.value)
                              }
                              required
                            />
                          </label>
                          <label className="training-field">
                            Phase end
                            <input
                              type="date"
                              value={phase.endDate}
                              min={phase.startDate || startDate || undefined}
                              max={endDate || undefined}
                              onChange={(e) =>
                                updatePhase(index, "endDate", e.target.value)
                              }
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
                      disabled={saving || loading}
                    >
                      {saving ? "Saving…" : "Save plan"}
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
                        SYNTHETIC: "Synthetic dirt",
                      }[phases[0]?.surface] || "—"}
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
                                <strong>{ph.targetVelocity} m/s</strong>
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
                        Training Phases ({editPhases.length})
                      </h3>
                      <button
                        type="button"
                        className="training-btn training-btn--ghost"
                        style={{ fontSize: 12, padding: "5px 12px" }}
                        disabled={editPhases.length >= 12}
                        onClick={() => {
                          const lastPhase = editPhases[editPhases.length - 1];
                          const newStart = lastPhase?.endDate || editingPlan.startDate;
                          setEditPhases([
                            ...editPhases,
                            {
                              phaseId: null,
                              phaseName: `Phase ${editPhases.length + 1}`,
                              startDate: newStart,
                              endDate: editingPlan.endDate,
                              distance: 1200,
                              surface: "DIRT",
                              sessionsPerWeek: 3,
                              heartRateCeiling: 180,
                              workload: "Medium",
                              minGapMinutes: 90,
                              targetVelocity: "",
                            },
                          ]);
                        }}
                      >
                        + Add Phase
                      </button>
                    </div>

                    <div className="plan-phase-edit-list">
                      {editPhases.map((ph, idx) => (
                        <div key={ph.phaseId || idx} className="plan-phase-edit-card">
                          <div className="plan-phase-edit-header">
                            <strong>Phase {idx + 1}: {ph.phaseName}</strong>
                            {editPhases.length > 1 && (
                              <button
                                type="button"
                                className="plan-phase-remove-btn"
                                onClick={() => setEditPhases(editPhases.filter((_, i) => i !== idx))}
                              >
                                Remove
                              </button>
                            )}
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

                            <label>
                              Start Date
                              <input
                                type="date"
                                required
                                min={editingPlan.startDate}
                                max={editingPlan.endDate}
                                value={ph.startDate}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, startDate: val } : p));
                                }}
                              />
                            </label>

                            <label>
                              End Date
                              <input
                                type="date"
                                required
                                min={ph.startDate || editingPlan.startDate}
                                max={editingPlan.endDate}
                                value={ph.endDate}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, endDate: val } : p));
                                }}
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
                                value={ph.surface}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, surface: val } : p));
                                }}
                              >
                                <option value="TURF">Turf</option>
                                <option value="DIRT">Dirt</option>
                                <option value="SYNTHETIC">Synthetic</option>
                              </select>
                            </label>

                            <label>
                              Sessions / Week
                              <input
                                type="number"
                                required
                                min="1"
                                max="14"
                                value={ph.sessionsPerWeek}
                                onChange={(e) => {
                                  const val = e.target.value;
                                  setEditPhases(editPhases.map((p, i) => i === idx ? { ...p, sessionsPerWeek: val } : p));
                                }}
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
                              Target Speed (m/s)
                              <input
                                type="number"
                                step="0.1"
                                min="1.0"
                                max="25.0"
                                placeholder="Optional"
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
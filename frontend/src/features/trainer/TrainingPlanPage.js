import { useCallback, useEffect, useMemo, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import { useSignOutModal } from "../auth/SignOutModal";
import { isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import {
  changeTrainingPlanStatus,
  deleteTrainingPlan,
  createTrainingPlan,
  listTrainingPlans,
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
  const [horseId, setHorseId] = useState(savedDraft?.horseId || "");
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

  useEffect(() => {
    saveDraft({
      horseId,
      planName,
      startDate,
      endDate,
      phases,
    });
  }, [horseId, planName, startDate, endDate, phases]);

  const load = useCallback(
    async (signal) => {
      setLoading(true);
      setError("");
      try {
        const [horseRows, planRows] = await Promise.all([
          listHorses(signal),
          listTrainingPlans({ signal }),
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

        setHorseId((current) => {
          const currentHorseIsEligible = eligibleHorses.some(
            (horse) => String(horse.horseId) === String(current),
          );
          return currentHorseIsEligible ? current : "";
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
  const visiblePlans = useMemo(
    () => (filter === "ALL" ? plans : plans.filter((p) => p.status === filter)),
    [plans, filter],
  );

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
    if (endDate < startDate) {
      setError("Plan end date must be on or after the start date.");
      return;
    }
    const payload = {
      horseId: Number(horseId),
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
      setStartDate("");
      setEndDate("");
      setPhases(initialPhases);
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

  const deletePlan = async (plan) => {
    if (!window.confirm(`Delete the plan "${plan.planName}"? This cannot be undone.`)) return;
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
      <TrainerSidebar active="Training plans" onSignOut={requestSignOut} />
      <main className="trainer-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span className="trainer-diamond" aria-hidden="true" />
              <h1>Training plan</h1>
            </div>
            <p>Build a phased programme for a horse assigned to you ({horses.length} managed eligible horses)</p>
          </div>
          <div className="trainer-header-actions">
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
                <strong style={{ fontSize: "15px", color: "#b91c1c" }}>⚠️ Training plan could not be saved</strong>
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
          <form onSubmit={submit}>
            <div className="training-plan-layout">
              <div className="training-plan-left">
                <section className="trainer-card plan-builder-card">
                  <div className="trainer-card-title">
                    <span className="trainer-diamond" />
                    <div>
                      <h2>Plan details</h2>
                      <p>Choose a horse allocated to you by the Club Manager and define the preparation period.</p>
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
                      Horse (allocated to {myUser?.fullName || "you"})
                      <select
                        value={horseId}
                        onChange={(e) => setHorseId(e.target.value)}
                        required
                      >
                        <option value="">Choose a horse</option>
                        {horses.map((h) => (
                          <option key={h.horseId} value={h.horseId}>
                            {h.horseName} ·{" "}
                            {h.registrationCode || `#${h.horseId}`} ({h.status})
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
                      onClick={() => navigate("/trainer")}
                    >
                      Back
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
                      <p>Current selected horse and phase</p>
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
                  <div className="training-medical-note">
                    <strong>Medical constraint</strong>
                    <p>
                      Heavy sessions cannot be scheduled for a horse under
                      training lock. A veterinarian must release the lock first.
                    </p>
                  </div>
                  <div className="training-rule-note">
                    The backend prevents activation if the horse is not
                    trainable, is under an active training lock, or already has
                    another active plan.
                  </div>
                </section>
                <section className="trainer-card saved-plans-card">
                  <div className="trainer-card-title">
                    <span className="trainer-diamond" />
                    <div>
                      <h2>Saved plans</h2>
                      <p>
                        {plans.length} plan{plans.length === 1 ? "" : "s"}
                      </p>
                    </div>
                  </div>
                  <div className="training-plan-filter">
                    {["ALL", ...Object.keys(PLAN_STATUS_LABEL)].map(
                      (s) => (
                        <button
                          key={s}
                          type="button"
                          className={filter === s ? "selected" : ""}
                          onClick={() => setFilter(s)}
                        >
                          {s === "ALL" ? "All" : PLAN_STATUS_LABEL[s]}
                        </button>
                      ),
                    )}
                  </div>
                  {loading ? (
                    <p className="training-empty">Loading plans…</p>
                  ) : visiblePlans.length === 0 ? (
                    <p className="training-empty">No plans in this view.</p>
                  ) : (
                    <div className="saved-plan-list">
                      {visiblePlans.map((plan) => (
                        <article className="saved-plan-row" key={plan.planId}>
                          <div className="saved-plan-top">
                            <strong>{plan.planName}</strong>
                            <span
                              className={`training-status-pill ${statusClass(plan.status)}`}
                            >
                              {PLAN_STATUS_LABEL[plan.status] || plan.status}
                            </span>
                          </div>
                          <p>
                            {plan.horseName} · {dateLabel(plan.startDate)} –{" "}
                            {dateLabel(plan.endDate)}
                          </p>
                          <small>
                            {plan.phaseCount} phase
                            {plan.phaseCount === 1 ? "" : "s"} · Plan #
                            {plan.planId}
                          </small>
                          {plan.status === "DRAFT" && plan.ownerNote && (
                            <p className="saved-plan-owner-note">
                              Owner rejected: {plan.ownerNote}
                            </p>
                          )}
                          <div className="saved-plan-actions">
                            {plan.status === "DRAFT" && (
                              <button
                                type="button"
                                onClick={() =>
                                  updateStatus(plan, "PENDING_APPROVAL")
                                }
                              >
                                Send to owner
                              </button>
                            )}
                            {plan.status === "APPROVED" && (
                              <button
                                type="button"
                                onClick={() => updateStatus(plan, "ACTIVE")}
                              >
                                Activate
                              </button>
                            )}
                            {["DRAFT", "PENDING_APPROVAL", "APPROVED"].includes(
                              plan.status,
                            ) && (
                              <button
                                type="button"
                                onClick={() => deletePlan(plan)}
                              >
                                Delete
                              </button>
                            )}
                            {plan.status === "ACTIVE" && (
                              <>
                                <button
                                  type="button"
                                  onClick={() => updateStatus(plan, "PAUSED")}
                                >
                                  Pause
                                </button>
                                <button
                                  type="button"
                                  onClick={() =>
                                    updateStatus(plan, "COMPLETED")
                                  }
                                >
                                  Complete
                                </button>
                              </>
                            )}
                            {plan.status === "PAUSED" && (
                              <>
                                <button
                                  type="button"
                                  onClick={() => updateStatus(plan, "ACTIVE")}
                                >
                                  Resume
                                </button>
                                <button
                                  type="button"
                                  onClick={() =>
                                    updateStatus(plan, "COMPLETED")
                                  }
                                >
                                  Complete
                                </button>
                              </>
                            )}
                          </div>
                        </article>
                      ))}
                    </div>
                  )}
                </section>
              </aside>
            </div>
          </form>
        </section>
      </main>
    </div>
  );
}

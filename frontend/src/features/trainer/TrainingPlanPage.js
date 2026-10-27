import { useCallback, useEffect, useMemo, useState } from "react";
import NavItems from "../navigation/NavItems";
import { useNavigate } from "react-router-dom";
import { handleAuthError, logout } from "../auth/authService";
import { isPedigreeOnly, isWithdrawn, listHorses } from "../horse/horseApi";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";
import {
  changeTrainingPlanStatus,
  createTrainingPlan,
  listTrainingPlans,
} from "./trainingPlanApi";
import "./trainer.css";
import "./training-plan.css";

const initialPhases = [
  {
    phaseName: "Base",
    distance: "3200",
    workload: "60% VO2 max",
    surface: "DIRT",
    sessionsPerWeek: "3",
    heartRateCeiling: "180",
    startDate: "2026-08-31",
    endDate: "2026-09-27",
  },
  {
    phaseName: "Build",
    distance: "2000",
    workload: "Interval training",
    surface: "TURF",
    sessionsPerWeek: "4",
    heartRateCeiling: "180",
    startDate: "2026-09-28",
    endDate: "2026-11-01",
  },
  {
    phaseName: "Peak",
    distance: "1600",
    workload: "Race-day simulation",
    surface: "SYNTHETIC",
    sessionsPerWeek: "3",
    heartRateCeiling: "180",
    startDate: "2026-11-02",
    endDate: "2026-11-22",
  },
];

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
  return {
    phaseName: ["Base", "Build", "Peak"][index] || `Phase ${index + 1}`,
    distance: "",
    workload: "",
    surface: "DIRT",
    sessionsPerWeek: "3",
    heartRateCeiling: "180",
    startDate,
    endDate,
  };
}

export default function TrainingPlanPage() {
  const navigate = useNavigate();
  const [horses, setHorses] = useState([]);
  const [plans, setPlans] = useState([]);
  const [horseId, setHorseId] = useState("");
  const [planName, setPlanName] = useState("Tracen Cup preparation");
  const [startDate, setStartDate] = useState("2026-08-31");
  const [endDate, setEndDate] = useState("2026-11-22");
  const [phases, setPhases] = useState(initialPhases);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [filter, setFilter] = useState("ALL");

  const load = useCallback(
    async (signal) => {
      setLoading(true);
      setError("");
      try {
        const [horseRows, planRows] = await Promise.all([
          listHorses(signal),
          listTrainingPlans({ signal }),
        ]);
        // Khớp TRAINABLE ở backend (ELIGIBLE, MONITOR); hồ sơ PEDIGREE và ngựa
        // đã rút khỏi học viện không vào giáo án.
        const eligibleHorses = (horseRows || []).filter(
          (horse) =>
            !isPedigreeOnly(horse) &&
            !isWithdrawn(horse) &&
            (horse.status === "ELIGIBLE" || horse.status === "MONITOR"),
        );

        setHorses(eligibleHorses);
        setPlans(planRows || []);

        setHorseId((current) => {
          const currentHorseIsEligible = eligibleHorses.some(
            (horse) => String(horse.horseId) === String(current),
          );

          if (currentHorseIsEligible) {
            return current;
          }

          return String(eligibleHorses[0]?.horseId || "");
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
  const addPhase = () =>
    setPhases((current) =>
      current.length >= 12
        ? current
        : [...current, emptyPhase(current.length, startDate, endDate)],
    );
  const removePhase = (index) =>
    setPhases((current) =>
      current.length <= 1 ? current : current.filter((_, i) => i !== index),
    );

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
        startDate: p.startDate,
        endDate: p.endDate,
      })),
    };
    setSaving(true);
    try {
      const created = await createTrainingPlan(payload);
      setNotice(
        `Training plan “${created.planName}” was saved as ${created.status}.`,
      );
      await load();
    } catch (err) {
      if (!handleAuthError(err, navigate)) {
        const fieldMessages = Object.entries(err.fieldErrors || {}).map(
          ([field, message]) => `${field}: ${message}`,
        );
        setError([err.message, ...fieldMessages].filter(Boolean).join(" · "));
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
      setNotice(`Plan status changed to ${updated.status}.`);
    } catch (err) {
      if (!handleAuthError(err, navigate))
        setError(err.message || "Could not change plan status.");
    }
  };

  const signOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="trainer-page training-plan-page">
      <TrainerSidebar active="Training plans" onSignOut={signOut} />
      <main className="trainer-main">
        <header className="trainer-header">
          <div>
            <div className="trainer-page-title">
              <span className="trainer-diamond" aria-hidden="true" />
              <h1>Training plan</h1>
            </div>
            <p>Build a phased programme for a horse</p>
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
            <div className="trainer-state trainer-state--error" role="alert">
              <strong>Could not complete the request</strong>
              <p>{error}</p>
              <button
                className="trainer-retry"
                type="button"
                onClick={() => load()}
              >
                Try again
              </button>
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
                      <p>Choose a horse and define the preparation period.</p>
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
                      Horse
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
                        onChange={(e) => setStartDate(e.target.value)}
                        required
                      />
                    </label>
                    <label className="training-field">
                      End date
                      <input
                        type="date"
                        value={endDate}
                        onChange={(e) => setEndDate(e.target.value)}
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
                    <button
                      type="button"
                      className="training-add-phase"
                      onClick={addPhase}
                      disabled={phases.length >= 12}
                    >
                      + Add phase
                    </button>
                  </div>
                  <div className="training-phase-list">
                    {phases.map((phase, index) => (
                      <article
                        className="training-phase"
                        key={`${index}-${phase.phaseName}`}
                      >
                        <div className="training-phase-heading">
                          <span className="training-phase-number">
                            {String(index + 1).padStart(2, "0")}
                          </span>
                          <div>
                            <strong>
                              Phase {index + 1} ·{" "}
                              {phase.phaseName || "Untitled"}
                            </strong>
                            <span>
                              {dateLabel(phase.startDate)} –{" "}
                              {dateLabel(phase.endDate)}
                            </span>
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
                            />
                          </label>
                          <label className="training-field">
                            Phase start
                            <input
                              type="date"
                              value={phase.startDate}
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
                              onChange={(e) =>
                                updatePhase(index, "endDate", e.target.value)
                              }
                              min={phase.startDate}
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
                    {["ALL", "DRAFT", "ACTIVE", "PAUSED", "COMPLETED"].map(
                      (s) => (
                        <button
                          key={s}
                          type="button"
                          className={filter === s ? "selected" : ""}
                          onClick={() => setFilter(s)}
                        >
                          {s === "ALL" ? "All" : s}
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
                              {plan.status}
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
                          <div className="saved-plan-actions">
                            {plan.status === "DRAFT" && (
                              <button
                                type="button"
                                onClick={() => updateStatus(plan, "ACTIVE")}
                              >
                                Activate
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

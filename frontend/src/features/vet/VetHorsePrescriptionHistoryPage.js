// src/features/vet/VetHorsePrescriptionHistoryPage.js
// Dedicated page: Complete lifetime prescription & medical treatment history for a single horse.

import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import HorsePhoto from "../horse/HorsePhoto";
import { toGender, toAge, toStatus } from "../horse/horseApi";
import VetShell, { VetCard, VetState, VetError } from "./VetShell";
import {
  getHorse,
  listTreatmentsForHorse,
  endTreatment,
  formatDate,
  normalizeDate,
  todayInputValue,
} from "./vetApi";
import "./vet-care.css";

const treatmentTitle = (t) => t.medicineName || t.instruction || "Treatment";
const dose = (t) => [t.dosage, t.frequency].filter(Boolean).join(" · ") || "—";
const period = (t) =>
  `${formatDate(t.startDate)} → ${t.endDate ? formatDate(t.endDate) : "until review"}`;

function EndDialog({ treatment, onClose, onDone }) {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await endTreatment(treatment.treatmentId);
      onDone(
        `${treatmentTitle(treatment)} ends today for ${treatment.horseName}.`
      );
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="vx-overlay" role="dialog" aria-modal="true" aria-labelledby="vc-end-title">
      <div className="vx-dialog">
        <h2 id="vc-end-title">End treatment today?</h2>
        <p className="vx-lead">
          Set today as the end date for <strong>{treatmentTitle(treatment)}</strong> on{" "}
          <strong>{treatment.horseName}</strong>.
        </p>

        <VetError error={error} />

        <p className="vc-hint">
          The treatment stays active until the end of today. The prescription stays in the horse's
          history permanently; it is never deleted.
        </p>

        <div className="vx-actions">
          <button type="button" className="vx-btn vx-btn--red" disabled={busy} onClick={submit}>
            {busy ? "Ending…" : "End treatment today"}
          </button>
          <button type="button" className="vx-btn vx-btn--ghost" disabled={busy} onClick={onClose}>
            Keep running
          </button>
        </div>
      </div>
    </div>
  );
}

export default function VetHorsePrescriptionHistoryPage() {
  const { horseId } = useParams();
  const navigate = useNavigate();

  const [horse, setHorse] = useState(null);
  const [treatments, setTreatments] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reload, setReload] = useState(0);

  // Ending dialog state
  const [endingTreatment, setEndingTreatment] = useState(null);

  // Time & search filters
  const [timePreset, setTimePreset] = useState("ALL"); // ALL, 30D, 90D, 180D, 1Y, CUSTOM
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL"); // ALL, ACTIVE, ENDED

  const today = todayInputValue();

  // Load horse and treatments
  const loadData = useCallback(() => {
    if (!horseId) return;
    const ctrl = new AbortController();
    setLoading(true);
    setError("");

    Promise.all([
      getHorse(horseId, ctrl.signal),
      listTreatmentsForHorse(horseId, ctrl.signal),
    ])
      .then(([horseData, treatmentsData]) => {
        setHorse(horseData);
        setTreatments(treatmentsData || []);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message || "Failed to load horse prescription history");
      })
      .finally(() => setLoading(false));

    return () => ctrl.abort();
  }, [horseId, navigate]);

  useEffect(() => {
    return loadData();
  }, [loadData, reload]);

  // Compute cutoff date based on timePreset
  const cutoffDate = useMemo(() => {
    if (timePreset === "ALL") return null;
    const d = new Date();
    if (timePreset === "30D") d.setDate(d.getDate() - 30);
    else if (timePreset === "90D") d.setDate(d.getDate() - 90);
    else if (timePreset === "180D") d.setDate(d.getDate() - 180);
    else if (timePreset === "1Y") d.setFullYear(d.getFullYear() - 1);
    else return null;

    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${y}-${m}-${day}`;
  }, [timePreset]);

  // Group treatments into Prescription Orders (grouped by startDate + recordId)
  const prescriptionGroups = useMemo(() => {
    if (!treatments) return [];

    const map = new Map();
    treatments.forEach((t) => {
      const key = `${t.recordId || "std"}_${t.startDate}`;
      if (!map.has(key)) {
        map.set(key, {
          key,
          recordId: t.recordId,
          startDate: t.startDate,
          endDate: t.endDate,
          items: [],
        });
      }
      const grp = map.get(key);
      grp.items.push(t);
      if (t.endDate && (!grp.endDate || t.endDate > grp.endDate)) {
        grp.endDate = t.endDate;
      }
    });

    let groups = Array.from(map.values());

    // Sort newest prescription first
    groups.sort((a, b) => (b.startDate || "").localeCompare(a.startDate || ""));

    // Apply Time filter
    if (timePreset !== "ALL") {
      if (timePreset === "CUSTOM") {
        if (customFrom) {
          groups = groups.filter((g) => normalizeDate(g.startDate) >= customFrom);
        }
        if (customTo) {
          groups = groups.filter((g) => normalizeDate(g.startDate) <= customTo);
        }
      } else if (cutoffDate) {
        groups = groups.filter((g) => normalizeDate(g.startDate) >= cutoffDate);
      }
    }

    // Apply Search Query filter
    const q = searchQuery.trim().toLowerCase();
    if (q) {
      groups = groups.filter((g) =>
        g.items.some(
          (t) =>
            String(t.medicineName || "").toLowerCase().includes(q) ||
            String(t.instruction || "").toLowerCase().includes(q) ||
            String(t.dosage || "").toLowerCase().includes(q) ||
            String(t.frequency || "").toLowerCase().includes(q)
        )
      );
    }

    // Apply Status filter
    if (statusFilter !== "ALL") {
      groups = groups.filter((g) => {
        const hasActive = g.items.some((t) => {
          const endIso = normalizeDate(t.endDate);
          return t.isActive !== false && t.active !== false && (!endIso || endIso >= today);
        });
        return statusFilter === "ACTIVE" ? hasActive : !hasActive;
      });
    }

    return groups;
  }, [treatments, timePreset, cutoffDate, customFrom, customTo, searchQuery, statusFilter, today]);

  // Overall Statistics for this horse
  const stats = useMemo(() => {
    if (!treatments) return { totalPrescriptions: 0, totalMedicines: 0, activeCount: 0, endedCount: 0, latestDate: "—" };

    let activeCount = 0;
    let endedCount = 0;
    let latest = "";

    treatments.forEach((t) => {
      const endIso = normalizeDate(t.endDate);
      const isActive = t.isActive !== false && t.active !== false && (!endIso || endIso >= today);
      if (isActive) activeCount++;
      else endedCount++;

      if (t.startDate && (!latest || t.startDate > latest)) {
        latest = t.startDate;
      }
    });

    const uniquePrescriptionKeys = new Set(
      treatments.map((t) => `${t.recordId || "std"}_${t.startDate}`)
    );

    return {
      totalPrescriptions: uniquePrescriptionKeys.size,
      totalMedicines: treatments.length,
      activeCount,
      endedCount,
      latestDate: latest ? formatDate(latest) : "—",
    };
  }, [treatments, today]);

  const age = horse?.dateOfBirth ? toAge(horse.dateOfBirth) : null;
  const statusInfo = horse?.status ? toStatus(horse.status) : null;

  return (
    <VetShell
      active="prescriptions"
      title={`Prescription History · ${horse?.horseName || "Horse"}`}
      subtitle="Complete lifetime prescription and medical treatment records with timeline filter"
    >
      {notice && (
        <div className="vx-banner vx-banner--ok" role="status">
          <strong>{notice}</strong>
        </div>
      )}

      {error && (
        <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)}>
          Please verify that the backend is active.
        </VetState>
      )}

      {/* Back to Prescriptions button */}
      <div>
        <button
          type="button"
          className="vc-back-btn"
          onClick={() => navigate("/veterinarian/prescriptions")}
        >
          ← Back to Prescriptions
        </button>
      </div>

      {loading && !horse && <VetState title="Loading horse medical history…" />}

      {horse && (
        <>
          {/* Horse Hero Profile Banner */}
          <div className="vc-history-hero">
            <div className="vc-history-hero-left">
              <HorsePhoto horse={horse} className="vc-history-hero-photo" />
              <div className="vc-history-hero-meta">
                <h1>{horse.horseName}</h1>
                <div className="vc-history-hero-tags">
                  <strong>{horse.registrationCode}</strong>
                  <span>·</span>
                  <span>{toGender(horse.horseGender)}</span>
                  {horse.breed && (
                    <>
                      <span>·</span>
                      <span>{horse.breed}</span>
                    </>
                  )}
                  {age != null && (
                    <>
                      <span>·</span>
                      <span>{age} yrs</span>
                    </>
                  )}
                  <span>·</span>
                  <span>Stall: {horse.stallNo || "Unassigned"}</span>
                  {statusInfo && (
                    <>
                      <span>·</span>
                      <span className={`vc-pill vc-pill--${statusInfo.tone || "green"}`}>
                        {statusInfo.label}
                      </span>
                    </>
                  )}
                </div>
                <div className="vc-history-hero-owner">
                  👤 Owner: <strong>{horse.ownerName || horse.ownerFullName || "Tracen Bloodstock Club"}</strong>
                </div>
              </div>
            </div>

            <div className="vc-history-hero-stats">
              <div className="vc-hero-stat-box">
                <div className="vc-hero-stat-value">{stats.totalPrescriptions}</div>
                <div className="vc-hero-stat-label">Prescriptions</div>
              </div>
              <div className="vc-hero-stat-box">
                <div className="vc-hero-stat-value" style={{ color: "#2e7d32" }}>
                  {stats.activeCount}
                </div>
                <div className="vc-hero-stat-label">Active Meds</div>
              </div>
              <div className="vc-hero-stat-box">
                <div className="vc-hero-stat-value" style={{ color: "#5e6682" }}>
                  {stats.endedCount}
                </div>
                <div className="vc-hero-stat-label">Completed</div>
              </div>
              <div className="vc-hero-stat-box">
                <div className="vc-hero-stat-value" style={{ fontSize: "14px", fontWeight: 700 }}>
                  {stats.latestDate}
                </div>
                <div className="vc-hero-stat-label">Latest Date</div>
              </div>
            </div>
          </div>

          {/* Time & Search Filters Bar */}
          <div className="vc-history-filters">
            <div className="vc-filter-row">
              <div className="vc-time-presets">
                <span className="vc-time-presets-title">Timeline:</span>
                {[
                  { key: "ALL", label: "All Time" },
                  { key: "30D", label: "Last 30 Days" },
                  { key: "90D", label: "Last 90 Days" },
                  { key: "180D", label: "Last 180 Days" },
                  { key: "1Y", label: "Last 1 Year" },
                  { key: "CUSTOM", label: "Custom Range" },
                ].map(({ key, label }) => (
                  <button
                    key={key}
                    type="button"
                    className={`vc-preset-btn ${timePreset === key ? "active" : ""}`}
                    onClick={() => setTimePreset(key)}
                  >
                    {label}
                  </button>
                ))}
              </div>

              {timePreset === "CUSTOM" && (
                <div className="vc-custom-dates">
                  <label>
                    From:{" "}
                    <input
                      type="date"
                      className="vc-date-picker"
                      value={customFrom}
                      onChange={(e) => setCustomFrom(e.target.value)}
                    />
                  </label>
                  <label>
                    To:{" "}
                    <input
                      type="date"
                      className="vc-date-picker"
                      value={customTo}
                      onChange={(e) => setCustomTo(e.target.value)}
                    />
                  </label>
                </div>
              )}
            </div>

            <div className="vc-filter-inputs">
              <input
                type="search"
                className="vc-search-input"
                placeholder="Search by medicine name, dosage, instructions..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                aria-label="Search prescriptions"
              />

              <select
                className="vc-select-filter"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                aria-label="Filter status"
              >
                <option value="ALL">All Prescription Statuses</option>
                <option value="ACTIVE">Active Prescriptions Only</option>
                <option value="ENDED">Ended / Completed Only</option>
              </select>
            </div>
          </div>

          {/* Prescriptions Lifetime List */}
          <VetCard
            title="Prescription Records History"
            hint={`Showing ${prescriptionGroups.length} prescription ${prescriptionGroups.length === 1 ? "record" : "records"} for ${horse.horseName}`}
            tone="green"
          >
            {prescriptionGroups.length === 0 ? (
              <div className="vet-inline-state">
                {searchQuery || timePreset !== "ALL" || statusFilter !== "ALL"
                  ? "No prescription records match the selected timeline and filter criteria."
                  : "No prescription records found for this horse."}
              </div>
            ) : (
              prescriptionGroups.map((grp, idx) => {
                const isGroupActive = grp.items.some((t) => {
                  const endIso = normalizeDate(t.endDate);
                  return t.isActive !== false && t.active !== false && (!endIso || endIso >= today);
                });

                // Find if there is an extra note or custom note
                const noteItem = grp.items.find((t) => t.instruction && t.instruction.length > 30);

                return (
                  <div key={grp.key || idx} className="vc-history-card">
                    <div className="vc-history-card-header">
                      <div className="vc-history-card-date">
                        <span style={{ color: "#f55fa9", fontSize: "16px" }}>💊</span>
                        <span>Prescription Date: {formatDate(grp.startDate)}</span>
                        {grp.endDate && (
                          <span style={{ fontSize: "13px", color: "var(--v-muted)", fontWeight: 500 }}>
                            (until {formatDate(grp.endDate)})
                          </span>
                        )}
                        {grp.recordId && (
                          <span
                            className="vx-pill"
                            style={{
                              fontSize: "11px",
                              padding: "2px 8px",
                              background: "#eff6ff",
                              color: "#1e40af",
                              fontWeight: 600,
                            }}
                          >
                            Exam #{grp.recordId}
                          </span>
                        )}
                      </div>

                      <span className={`vc-pill vc-pill--${isGroupActive ? "green" : "muted"}`}>
                        {isGroupActive ? "Active" : "Completed"}
                      </span>
                    </div>

                    {noteItem && (
                      <div className="vc-history-card-note">
                        <strong>Prescription Note / Special Instructions:</strong> {noteItem.instruction}
                      </div>
                    )}

                    <div className="vc-table-wrap">
                      <table className="vc-table">
                        <thead>
                          <tr>
                            <th>Medicine / Treatment</th>
                            <th>Dosage</th>
                            <th>Period</th>
                            <th>Instructions</th>
                            <th>Status</th>
                            <th aria-label="Action" style={{ textAlign: "right" }} />
                          </tr>
                        </thead>
                        <tbody>
                          {grp.items.map((t) => {
                            const endIso = normalizeDate(t.endDate);
                            const itemActive =
                              t.isActive !== false &&
                              t.active !== false &&
                              (!endIso || endIso >= today);

                            return (
                              <tr key={t.treatmentId}>
                                <td>
                                  <strong>{treatmentTitle(t)}</strong>
                                </td>
                                <td>{dose(t)}</td>
                                <td className="vc-muted">{period(t)}</td>
                                <td>
                                  <span className="vc-instruction">
                                    {t.instruction || "As directed by veterinarian"}
                                  </span>
                                </td>
                                <td>
                                  <span className={`vc-pill vc-pill--${itemActive ? "green" : "muted"}`}>
                                    {itemActive ? "Active" : "Ended"}
                                  </span>
                                </td>
                                <td style={{ textAlign: "right" }}>
                                  {itemActive && (
                                    <button
                                      type="button"
                                      className="vc-btn vc-btn--danger"
                                      onClick={() => setEndingTreatment(t)}
                                      title="End treatment today"
                                    >
                                      End today
                                    </button>
                                  )}
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  </div>
                );
              })
            )}
          </VetCard>
        </>
      )}

      {endingTreatment && (
        <EndDialog
          treatment={endingTreatment}
          onClose={() => setEndingTreatment(null)}
          onDone={(msg) => {
            setEndingTreatment(null);
            setNotice(msg);
            setReload((n) => n + 1);
          }}
        />
      )}
    </VetShell>
  );
}

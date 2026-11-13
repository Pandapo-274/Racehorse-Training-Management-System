// src/features/horse/HorseMedicalHistoryTab.js
// Lifetime medical records, treatment plans, injuries, training locks, and referrals for a horse.
// Supports timeframe filtering (All, 30 days, 3 months, 6 months, 1 year, custom range),
// event category filtering, and keyword search.

import { useEffect, useMemo, useState } from "react";
import {
  listMedicalRecords,
  listInjuries,
  listLocksForHorse,
  listTreatmentsForHorse,
  listExternalReferrals,
  listAllIncidentReportsForHorse,
  lockLevelLabel,
  statusLabel,
} from "../vet/vetApi";
import { formatDate, formatDateTime } from "./horseApi";
import "./horse-health.css";

const EVENT_TYPE_OPTIONS = [
  { value: "ALL", label: "All Events" },
  { value: "EXAM", label: "Examinations & Diagnoses" },
  { value: "INJURY", label: "Injuries & Recovery" },
  { value: "TREATMENT", label: "Prescriptions & Treatments" },
  { value: "LOCK", label: "Training Locks" },
  { value: "REFERRAL", label: "External Referrals" },
  { value: "INCIDENT", label: "Track Incidents" },
];

const TIME_PRESETS = [
  { value: "all", label: "All Time" },
  { value: "30d", label: "Last 30 Days" },
  { value: "90d", label: "Last 90 Days" },
  { value: "180d", label: "Last 180 Days" },
  { value: "365d", label: "Last 1 Year" },
  { value: "custom", label: "Custom Range…" },
];

const SEVERITY_LABEL = {
  GRADE_1: "Grade 1 (Mild)",
  GRADE_2: "Grade 2 (Moderate)",
  GRADE_3: "Grade 3 (Severe)",
  GRADE_4: "Grade 4 (Critical)",
};

export default function HorseMedicalHistoryTab({ horse, user }) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [events, setEvents] = useState([]);

  // Filters
  const [timePreset, setTimePreset] = useState("all");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [typeFilter, setTypeFilter] = useState("ALL");
  const [searchQuery, setSearchQuery] = useState("");

  const isVet = user?.role === "VETERINARIAN";

  useEffect(() => {
    if (!horse?.horseId) return;
    const ctrl = new AbortController();
    setLoading(true);
    setError("");

    Promise.allSettled([
      listMedicalRecords(horse.horseId, ctrl.signal),
      listInjuries(horse.horseId, ctrl.signal),
      listLocksForHorse(horse.horseId, ctrl.signal),
      listTreatmentsForHorse(horse.horseId, ctrl.signal),
      listExternalReferrals({ horseId: horse.horseId, status: "ALL" }, ctrl.signal),
      listAllIncidentReportsForHorse(horse.horseId, ctrl.signal),
    ])
      .then(([examsRes, injuriesRes, locksRes, rxRes, referralsRes, incidentsRes]) => {
        const unified = [];

        // 1. Clinical Examination Records
        if (examsRes.status === "fulfilled" && Array.isArray(examsRes.value)) {
          examsRes.value.forEach((r) => {
            const date = new Date(r.examDate || r.createdAt);
            unified.push({
              id: `exam-${r.recordId}`,
              type: "EXAM",
              date,
              categoryLabel: r.recordType || "Examination",
              title: r.diagnosis ? `Diagnosis: ${r.diagnosis}` : "General Clinical Examination",
              tone: "blue",
              actorName: r.vetName ? `Dr. ${r.vetName}` : "Attending Veterinarian",
              actorRole: "Attending Vet",
              status: r.healthStatus ? statusLabel(r.healthStatus) : "Completed",
              searchableText: `${r.diagnosis || ""} ${r.notes || ""} ${r.vetName || ""}`,
              details: {
                diagnosis: r.diagnosis || "—",
                healthStatus: r.healthStatus,
                injuryConfirmed: r.injuryConfirmed,
                expectedRestDays: r.expectedRestDays,
                weight: r.weight,
                notes: r.notes,
              },
            });
          });
        }

        // 2. Injuries & Recovery
        if (injuriesRes.status === "fulfilled" && Array.isArray(injuriesRes.value)) {
          injuriesRes.value.forEach((i) => {
            const date = new Date(i.markedAt);
            const isRecovered = i.status === "RECOVERED";
            unified.push({
              id: `injury-${i.injuryId}`,
              type: "INJURY",
              date,
              categoryLabel: "Injury",
              title: `Lesion: ${i.bodyLocation}`,
              tone: isRecovered ? "green" : "red",
              actorName: isRecovered ? `Recovered (${formatDate(i.recoveredAt)})` : "Under Treatment",
              actorRole: "Status",
              status: isRecovered ? "Healed / Recovered" : "Active Injury",
              searchableText: `${i.bodyLocation} ${i.severity}`,
              details: {
                bodyLocation: i.bodyLocation,
                severity: SEVERITY_LABEL[i.severity] || i.severity,
                coordinate: `(X: ${i.coordinateX}%, Y: ${i.coordinateY}%)`,
                isRecovered,
                recoveredAt: i.recoveredAt,
              },
            });
          });
        }

        // 3. Treatments & Prescriptions
        if (rxRes.status === "fulfilled" && Array.isArray(rxRes.value)) {
          rxRes.value.forEach((t) => {
            const date = new Date(t.startDate);
            const isEnded = t.status === "ENDED";
            unified.push({
              id: `treatment-${t.treatmentId}`,
              type: "TREATMENT",
              date,
              categoryLabel: "Prescription",
              title: `Medicine: ${t.medicineName}`,
              tone: "purple",
              actorName: `Dosage: ${t.dosage || "—"} (${t.frequency || "—"})`,
              actorRole: "Regimen",
              status: isEnded ? "Completed Course" : "Active",
              searchableText: `${t.medicineName} ${t.dosage || ""} ${t.instruction || ""}`,
              details: {
                medicineName: t.medicineName,
                dosage: t.dosage,
                frequency: t.frequency,
                startDate: t.startDate,
                endDate: t.endDate,
                instruction: t.instruction,
                isEnded,
              },
            });
          });
        }

        // 4. Training Locks
        if (locksRes.status === "fulfilled" && Array.isArray(locksRes.value)) {
          locksRes.value.forEach((l) => {
            const date = new Date(l.issuedAt);
            const isActive = l.status === "ACTIVE";
            unified.push({
              id: `lock-${l.lockId}`,
              type: "LOCK",
              date,
              categoryLabel: "Training Lock",
              title: `Lock Level: ${lockLevelLabel(l.lockLevel)}`,
              tone: isActive ? "red" : "amber",
              actorName: l.issuedByName || "Manager / Trainer / Vet",
              actorRole: "Issued By",
              status: isActive ? "Active Lock" : "Released",
              searchableText: `${l.reason || ""} ${l.lockLevel} ${l.issuedByName || ""} ${l.releasedByName || ""}`,
              details: {
                lockLevel: l.lockLevel,
                reason: l.reason,
                issuedByName: l.issuedByName,
                issuedAt: l.issuedAt,
                releasedByName: l.releasedByName,
                releasedAt: l.releasedAt,
                isActive,
              },
            });
          });
        }

        // 5. External Referrals
        if (referralsRes.status === "fulfilled" && Array.isArray(referralsRes.value)) {
          referralsRes.value.forEach((ref) => {
            const date = new Date(ref.referredAt);
            const isHospitalized = ref.status === "HOSPITALIZED";
            unified.push({
              id: `ref-${ref.referralId}`,
              type: "REFERRAL",
              date,
              categoryLabel: "External Referral",
              title: `Hospital: ${ref.hospitalName}`,
              tone: "pink",
              actorName: ref.referredByName ? `Dr. ${ref.referredByName}` : "Attending Vet",
              actorRole: "Referring Vet",
              status: isHospitalized ? "Hospitalized" : "Discharged to Club",
              searchableText: `${ref.hospitalName} ${ref.referralReason} ${ref.dischargeNotes || ""}`,
              details: {
                hospitalName: ref.hospitalName,
                urgency: ref.urgency,
                referralReason: ref.referralReason,
                expectedReturn: ref.expectedReturn,
                isHospitalized,
                dischargedAt: ref.dischargedAt,
                dischargedByName: ref.dischargedByName,
                dischargeNotes: ref.dischargeNotes,
              },
            });
          });
        }

        // 6. Track Incidents
        if (incidentsRes.status === "fulfilled" && Array.isArray(incidentsRes.value)) {
          incidentsRes.value.forEach((inc) => {
            const date = new Date(inc.reportedAt);
            unified.push({
              id: `inc-${inc.reportId}`,
              type: "INCIDENT",
              date,
              categoryLabel: "Track Incident",
              title: `Report: ${inc.symptoms}`,
              tone: inc.urgency === "HIGH" ? "urgent" : "amber",
              actorName: inc.reporterName || "Groom",
              actorRole: "Reported By",
              status: inc.status,
              searchableText: `${inc.symptoms} ${inc.description || ""} ${inc.reporterName || ""} ${inc.handoverByName || ""}`,
              details: {
                symptoms: inc.symptoms,
                description: inc.description,
                urgency: inc.urgency,
                sessionId: inc.sessionId,
                reporterName: inc.reporterName,
                receivedByName: inc.receivedByName,
                handoverByName: inc.handoverByName,
                handoverNotes: inc.handoverNotes,
                dismissedByName: inc.dismissedByName,
                dismissReason: inc.dismissReason,
              },
            });
          });
        }

        // Sort chronological descending (most recent first)
        unified.sort((a, b) => b.date - a.date);
        setEvents(unified);
      })
      .catch((err) => {
        if (err.name === "AbortError") return;
        setError(err.message || "Failed to load horse medical history.");
      })
      .finally(() => setLoading(false));

    return () => ctrl.abort();
  }, [horse.horseId]);

  // Date range calculation
  const { minDate, maxDate } = useMemo(() => {
    const now = new Date();
    let min = null;
    let max = null;
    if (timePreset === "30d") {
      min = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
    } else if (timePreset === "90d") {
      min = new Date(now.getTime() - 90 * 24 * 60 * 60 * 1000);
    } else if (timePreset === "180d") {
      min = new Date(now.getTime() - 180 * 24 * 60 * 60 * 1000);
    } else if (timePreset === "365d") {
      min = new Date(now.getTime() - 365 * 24 * 60 * 60 * 1000);
    } else if (timePreset === "custom") {
      if (customFrom) min = new Date(customFrom + "T00:00:00");
      if (customTo) max = new Date(customTo + "T23:59:59");
    }
    return { minDate: min, maxDate: max };
  }, [timePreset, customFrom, customTo]);

  // Filter events
  const filteredEvents = useMemo(() => {
    return events.filter((e) => {
      if (minDate && e.date < minDate) return false;
      if (maxDate && e.date > maxDate) return false;
      if (typeFilter !== "ALL" && e.type !== typeFilter) return false;
      if (searchQuery.trim()) {
        const q = searchQuery.trim().toLowerCase();
        const text = (
          (e.title || "") + " " +
          (e.categoryLabel || "") + " " +
          (e.actorName || "") + " " +
          (e.status || "") + " " +
          (e.searchableText || "")
        ).toLowerCase();
        if (!text.includes(q)) return false;
      }
      return true;
    });
  }, [events, minDate, maxDate, typeFilter, searchQuery]);

  // Quick summary counts
  const stats = useMemo(() => {
    const total = filteredEvents.length;
    let exams = 0;
    let injuries = 0;
    let treatments = 0;
    let referrals = 0;
    let locks = 0;

    filteredEvents.forEach((e) => {
      if (e.type === "EXAM") exams++;
      else if (e.type === "INJURY") injuries++;
      else if (e.type === "TREATMENT") treatments++;
      else if (e.type === "REFERRAL") referrals++;
      else if (e.type === "LOCK") locks++;
    });

    return { total, exams, injuries, treatments, referrals, locks };
  }, [filteredEvents]);

  if (loading) {
    return (
      <div className="hz-card" style={{ padding: "36px", textAlign: "center" }}>
        <p className="hh-muted">Loading lifetime medical records and treatment history for {horse.horseName}…</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="hz-card" style={{ padding: "24px" }}>
        <p className="hz-field__error">{error}</p>
      </div>
    );
  }

  return (
    <div className="mh-container">
      {/* 1. TIMELINE & KEYWORD FILTERS */}
      <div className="mh-filter-card">
        <div className="mh-filter-header">
          <div>
            <h3 style={{ margin: "0 0 4px", fontSize: "16px", fontWeight: 800 }}>
              Medical History & Treatment Records · {horse.horseName}
            </h3>
            <p style={{ margin: 0, fontSize: "13px", color: "#69728f" }}>
              Append-only clinical audit log recording all examinations, confirmed injuries, treatments, training locks, and external referrals.
            </p>
          </div>

          <div className="mh-search-box">
            <input
              type="text"
              className="vx-input"
              style={{ width: "240px", padding: "6px 12px", fontSize: "13px" }}
              placeholder="Search by symptoms, medicine, vet…"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </div>

        {/* Quick timeframe segments */}
        <div className="mh-filter-controls">
          <div className="mh-time-bar">
            <span className="mh-filter-label">Timeline:</span>
            <div className="mh-segments" role="group">
              {TIME_PRESETS.map((p) => (
                <button
                  key={p.value}
                  type="button"
                  className={`mh-segment-btn ${timePreset === p.value ? "active" : ""}`}
                  onClick={() => setTimePreset(p.value)}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          {/* Event type filter */}
          <div className="mh-type-bar">
            <span className="mh-filter-label">Category:</span>
            <select
              className="vx-input"
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              style={{ padding: "6px 10px", fontSize: "13px" }}
            >
              {EVENT_TYPE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Custom date range row */}
        {timePreset === "custom" && (
          <div className="mh-custom-row">
            <span className="mh-filter-label">Select Date Range:</span>
            <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
              <label style={{ fontSize: "12.5px", color: "#69728f" }}>From:</label>
              <input
                type="date"
                className="vx-input"
                style={{ padding: "4px 8px", fontSize: "12.5px" }}
                value={customFrom}
                onChange={(e) => setCustomFrom(e.target.value)}
              />
              <label style={{ fontSize: "12.5px", color: "#69728f" }}>To:</label>
              <input
                type="date"
                className="vx-input"
                style={{ padding: "4px 8px", fontSize: "12.5px" }}
                value={customTo}
                onChange={(e) => setCustomTo(e.target.value)}
              />
              {(customFrom || customTo) && (
                <button
                  type="button"
                  className="vx-btn vx-btn--ghost"
                  style={{ padding: "4px 8px", fontSize: "12px" }}
                  onClick={() => {
                    setCustomFrom("");
                    setCustomTo("");
                  }}
                >
                  Clear Range
                </button>
              )}
            </div>
          </div>
        )}
      </div>

      {/* 2. STATS SUMMARY BOXES */}
      <div className="mh-stats-grid">
        <div className="mh-stat-box">
          <span className="mh-stat-num">{stats.total}</span>
          <span className="mh-stat-title">Total Events</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#3074f6" }}>{stats.exams}</span>
          <span className="mh-stat-title">Examinations & Diagnoses</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#e43e8c" }}>{stats.injuries}</span>
          <span className="mh-stat-title">Confirmed Injuries</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#8a4af3" }}>{stats.treatments}</span>
          <span className="mh-stat-title">Treatments & Prescriptions</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#ff9800" }}>{stats.locks}</span>
          <span className="mh-stat-title">Training Locks</span>
        </div>
        <div className="mh-stat-box">
          <span className="mh-stat-num" style={{ color: "#f55fa9" }}>{stats.referrals}</span>
          <span className="mh-stat-title">External Referrals</span>
        </div>
      </div>

      {/* 3. TIMELINE */}
      {filteredEvents.length === 0 ? (
        <div className="hz-card" style={{ padding: "40px", textAlign: "center" }}>
          <p className="hh-muted">
            No medical or treatment records found matching the selected timeframe or search criteria.
          </p>
        </div>
      ) : (
        <div className="mh-timeline">
          {filteredEvents.map((evt) => (
            <div key={evt.id} className={`mh-item tone-${evt.tone}`}>
              {/* Date stamp */}
              <div className="mh-stamp">
                <span className="mh-stamp-date">{formatDate(evt.date)}</span>
                <span className="mh-stamp-time">{formatDateTime(evt.date).split(" ")[1] || ""}</span>
              </div>

              {/* Timeline dot */}
              <div className="mh-line-dot" />

              {/* Card content */}
              <div className="mh-card">
                <div className="mh-card-top">
                  <div className="mh-title-wrap">
                    <span className={`mh-tag mh-tag--${evt.tone}`}>{evt.categoryLabel}</span>
                    <h4 className="mh-event-title">{evt.title}</h4>
                  </div>
                  <div className="mh-status-badge">
                    <span className={`mh-chip mh-chip--${evt.tone}`}>{evt.status}</span>
                  </div>
                </div>

                <div className="mh-actor-line">
                  <span>{evt.actorRole}: <strong>{evt.actorName}</strong></span>
                </div>

                {/* Event specific details */}
                <div className="mh-details-block">
                  {evt.type === "EXAM" && (
                    <div className="mh-fact-grid">
                      <div className="mh-span-full">
                        <span>Diagnosis:</span>
                        <strong>{evt.details.diagnosis}</strong>
                      </div>
                      {evt.details.healthStatus && (
                        <div className="mh-nowrap">
                          <span>Post-Exam Status:</span>
                          <strong>{statusLabel(evt.details.healthStatus)}</strong>
                        </div>
                      )}
                      {evt.details.expectedRestDays && (
                        <div className="mh-nowrap">
                          <span>Prescribed Rest:</span>
                          <strong>{evt.details.expectedRestDays} days</strong>
                        </div>
                      )}
                      {evt.details.weight && (
                        <div className="mh-nowrap">
                          <span>Weight at Exam:</span>
                          <strong>{evt.details.weight} kg</strong>
                        </div>
                      )}
                      {isVet && evt.details.notes && (
                        <div className="mh-span-full">
                          <span>Clinical Notes:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.notes}</p>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "INJURY" && (
                    <div className="mh-fact-grid">
                      <div className="mh-span-full">
                        <span>Body Location:</span>
                        <strong>{evt.details.bodyLocation}</strong>
                      </div>
                      <div className="mh-nowrap">
                        <span>Severity:</span>
                        <strong>{evt.details.severity}</strong>
                      </div>
                      <div className="mh-nowrap">
                        <span>Coordinates:</span>
                        <strong>{evt.details.coordinate}</strong>
                      </div>
                      {evt.details.isRecovered && evt.details.recoveredAt && (
                        <div className="mh-nowrap">
                          <span>Recovery Date:</span>
                          <strong style={{ color: "#2e7d32" }}>{formatDateTime(evt.details.recoveredAt)}</strong>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "TREATMENT" && (
                    <div className="mh-fact-grid">
                      <div className="mh-nowrap">
                        <span>Dosage:</span>
                        <strong>{evt.details.dosage || "As prescribed"}</strong>
                      </div>
                      <div className="mh-nowrap">
                        <span>Frequency:</span>
                        <strong>{evt.details.frequency || "Daily"}</strong>
                      </div>
                      <div className="mh-nowrap">
                        <span>Regimen:</span>
                        <strong>
                          {formatDate(evt.details.startDate)} → {evt.details.endDate ? formatDate(evt.details.endDate) : "Ongoing"}
                        </strong>
                      </div>
                      {evt.details.instruction && (
                        <div className="mh-span-full">
                          <span>Instructions:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.instruction}</p>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "LOCK" && (
                    <div className="mh-fact-grid">
                      <div className="mh-nowrap">
                        <span>Lock Level:</span>
                        <strong>{lockLevelLabel(evt.details.lockLevel)}</strong>
                      </div>
                      <div className="mh-span-full">
                        <span>Reason:</span>
                        <strong>{evt.details.reason}</strong>
                      </div>
                      {!evt.details.isActive && evt.details.releasedByName && (
                        <div className="mh-span-full">
                          <span>Released By:</span>
                          <strong>
                            {evt.details.releasedByName} (at {formatDateTime(evt.details.releasedAt)})
                          </strong>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "REFERRAL" && (
                    <div className="mh-fact-grid">
                      <div>
                        <span>Receiving Hospital:</span>
                        <strong style={{ color: "#b32569" }}>{evt.details.hospitalName}</strong>
                      </div>
                      <div className="mh-nowrap">
                        <span>Urgency:</span>
                        <strong>{evt.details.urgency}</strong>
                      </div>
                      <div className="mh-nowrap">
                        <span>Estimated Return:</span>
                        <strong>{evt.details.expectedReturn ? formatDate(evt.details.expectedReturn) : "TBD"}</strong>
                      </div>
                      <div className="mh-span-full">
                        <span>Referral Reason:</span>
                        <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.referralReason}</p>
                      </div>
                      {!evt.details.isHospitalized && evt.details.dischargeNotes && (
                        <div className="mh-span-full" style={{ background: "#f8f9fc", padding: "8px 12px", borderRadius: "8px" }}>
                          <span style={{ fontWeight: 700, color: "#1976d2" }}>Discharge & Return Summary:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.dischargeNotes}</p>
                          <small style={{ color: "#8e96b1", display: "block", marginTop: "4px" }}>
                            Readmitted on {formatDate(evt.details.dischargedAt)} by Dr. {evt.details.dischargedByName}
                          </small>
                        </div>
                      )}
                    </div>
                  )}

                  {evt.type === "INCIDENT" && (
                    <div className="mh-fact-grid">
                      <div>
                        <span>Urgency:</span>
                        <strong>{evt.details.urgency}</strong>
                      </div>
                      {evt.details.sessionId && (
                        <div>
                          <span>Training Session:</span>
                          <strong>Session #{evt.details.sessionId} (Aborted)</strong>
                        </div>
                      )}
                      {evt.details.handoverByName && (
                        <div style={{ gridColumn: "1 / -1", background: "#fff5f9", padding: "8px 12px", borderRadius: "8px", border: "1px solid #f8cce1" }}>
                          <span style={{ fontWeight: 700, color: "#b32569" }}>Clinical Handover Log:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>
                            Handed over by Dr. {evt.details.handoverByName}: {evt.details.handoverNotes}
                          </p>
                        </div>
                      )}
                      {evt.details.dismissedByName && (
                        <div style={{ gridColumn: "1 / -1", background: "#f5f5f5", padding: "8px 12px", borderRadius: "8px" }}>
                          <span>Dismissed by Head Trainer {evt.details.dismissedByName}:</span>
                          <p style={{ margin: "2px 0 0", color: "#3a415a" }}>{evt.details.dismissReason}</p>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

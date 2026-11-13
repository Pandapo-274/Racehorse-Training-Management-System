import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { getUser, handleAuthError } from "../auth/authService";
import VetShell, { VetState } from "./VetShell";
import {
  listHorses,
  toBoardStatus,
  listIncidentReports,
  receiveIncidentReport,
  toUrgency,
  formatReportTime,
  listVeterinarians,
  handoverIncidentReport,
  listExternalReferrals,
  dischargeExternalReferral,
} from "./vetApi";
import { isPedigreeOnly, isWithdrawn } from "../horse/horseApi";
import { listVetCalls } from "../vetcall/vetCallApi";

// Màn 16 - Herd health board. Cùng khung với Trainer và Manager (xem VetShell).
//
// Sơ đồ chuồng và các thẻ đếm lấy từ GET /api/horses (status + stallNo).
// Hàng chờ khám và banner sự cố lấy từ GET /api/incident-reports: báo cáo còn
// mở (PENDING, RECEIVED), sinh ra khi Groom / Head Trainer dừng buổi tập vì
// ngựa bất thường. Khám kèm reportId thì báo cáo đóng (EXAMINED) và rời hàng chờ.
// V40: Bàn giao ca trực (handover/takeover) & Quản lý chuyển viện ngoài (External Referral).

const STATUS_META = {
  fit: { label: "Healthy" },
  monitor: { label: "Monitor" },
  injured: { label: "Injured" },
  quarantine: { label: "Quarantine" },
};

const STATUS_FILTERS = [
  { value: "all", label: "All" },
  { value: "fit", label: "Healthy" },
  { value: "monitor", label: "Monitor" },
  { value: "injured", label: "Injured" },
  { value: "quarantine", label: "Quarantine" },
];

function StatCard({ value, label, type, active, onClick }) {
  return (
    <button
      type="button"
      className={`vet-stat-card ${type}${active ? " selected" : ""}`}
      onClick={onClick}
      aria-pressed={active}
    >
      <span className="vet-stat-value">{value}</span>
      <span className="vet-stat-label">{label}</span>
    </button>
  );
}

function StableMap({ horses, counts, statusFilter, onStatusChange, matches, onOpen }) {
  return (
    <div className="vet-card stable-card">
      <div className="vet-card-title">
        <span className="vet-diamond" aria-hidden="true" />
        <div>
          <h2>Stable map</h2>
          <p>Stall colour shows health status · click a stall to examine</p>
        </div>

        <div className="vet-status-segment" role="group" aria-label="Filter stalls by health status">
          {STATUS_FILTERS.map((filter) => (
            <button
              key={filter.value}
              type="button"
              aria-pressed={statusFilter === filter.value}
              onClick={() => onStatusChange(filter.value)}
            >
              {filter.label} <b>{counts[filter.value]}</b>
            </button>
          ))}
        </div>
      </div>

      <div className="stable-grid">
        {horses.map((horse) => (
          <button
            type="button"
            key={horse.id}
            className={`stable-box ${horse.status}${matches(horse) ? "" : " dimmed"}`}
            title={`Stall ${horse.stall} · ${horse.name} · ${STATUS_META[horse.status].label}`}
            onClick={() => onOpen(horse)}
          >
            <span className="stall-number">Stall {horse.stall}</span>
            <strong>{horse.name}</strong>
          </button>
        ))}
      </div>
    </div>
  );
}

function HealthCard({ counts, total }) {
  const fitPct = total ? Math.round((counts.fit / total) * 100) : 0;

  const donut = useMemo(() => {
    if (!total) return "#f4e1eb";
    const parts = [
      ["#8cc63f", counts.fit],
      ["#ffc53d", counts.monitor],
      ["#ff5f6d", counts.injured],
      ["#f55fa9", counts.quarantine],
    ];
    let start = 0;
    const stops = parts
      .filter(([, n]) => n > 0)
      .map(([color, n]) => {
        const end = start + (n / total) * 360;
        const stop = `${color} ${start}deg ${end}deg`;
        start = end;
        return stop;
      });
    return `conic-gradient(${stops.join(", ")})`;
  }, [counts, total]);

  return (
    <div className="vet-card health-card">
      <div className="vet-card-title">
        <span className="vet-diamond" aria-hidden="true" />
        <div>
          <h2>Herd health status</h2>
          <p>{total} horses</p>
        </div>
      </div>

      <div className="health-content">
        <div className="health-donut" style={{ background: donut }}>
          <div className="health-donut-inner">
            <strong>{fitPct}%</strong>
            <span>healthy</span>
          </div>
        </div>

        <div className="health-legend">
          {["fit", "monitor", "injured", "quarantine"].map((key) => (
            <div key={key}>
              <span className={`legend-dot ${key}`} />
              <strong>{STATUS_META[key].label}</strong>
              <b>{counts[key]}</b>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function WaitingList({ items, total, onExamine, onReceive, onOpenHandover, busyId, currentUser }) {
  const first = items[0];

  return (
    <div className="vet-card waiting-card">
      <div className="waiting-header">
        <div className="vet-card-title">
          <span className="vet-diamond" aria-hidden="true" />
          <div>
            <h2>Waiting to be seen</h2>
            <p>
              {total} open incident report{total === 1 ? "" : "s"}
            </p>
          </div>
        </div>

        {first && (
          <button type="button" className="examine-button" onClick={() => onExamine(first)}>
            Examine {first.horseName} now
          </button>
        )}
      </div>

      {items.length === 0 ? (
        <div className="vet-inline-state">
          {total === 0 ? "No horses are waiting for an examination." : "No report matches your search."}
        </div>
      ) : (
        <div className="waiting-table-wrapper">
          <table className="waiting-table">
            <thead>
              <tr>
                <th>Horse</th>
                <th>Symptoms</th>
                <th>Reported</th>
                <th>Priority</th>
                <th>Case</th>
              </tr>
            </thead>

            <tbody>
              {items.map((r) => {
                const urgency = toUrgency(r.urgency);
                const isMyCase = r.receivedBy && r.receivedBy === currentUser?.userId;
                return (
                  <tr key={r.reportId}>
                    <td className="horse-name">
                      {r.horseName}
                      {r.stallNo && <span className="waiting-sub">Stall {r.stallNo}</span>}
                    </td>
                    <td>
                      {r.symptoms}
                      {r.sessionId && <span className="waiting-sub">Stopped during a training session</span>}
                      {r.handoverByName && (
                        <div className="waiting-handover-tag" title={r.handoverNotes}>
                          Handover by Dr. {r.handoverByName}
                          {r.handoverNotes ? `: ${r.handoverNotes}` : ""}
                        </div>
                      )}
                    </td>
                    <td>
                      {formatReportTime(r.reportedAt)}
                      <span className="waiting-sub">by {r.reporterName}</span>
                    </td>
                    <td>
                      <span className={`waiting-status ${urgency.cls}`}>{urgency.label}</span>
                    </td>
                    <td>
                      <div className="waiting-actions">
                        {r.status === "PENDING" ? (
                          <button
                            type="button"
                            className="waiting-action"
                            disabled={busyId === r.reportId}
                            onClick={() => onReceive(r)}
                          >
                            Take case
                          </button>
                        ) : (
                          <>
                            <span style={{ fontSize: "11px", color: "var(--text-600)", whiteSpace: "nowrap", flexShrink: 0 }}>
                              Taken by {r.receivedByName}
                            </span>
                            <button
                              type="button"
                              className="waiting-action"
                              style={{ whiteSpace: "nowrap", flexShrink: 0 }}
                              title="Bàn giao ca cho bác sĩ khác hoặc tiếp quản ca"
                              onClick={() => onOpenHandover(r)}
                            >
                              {isMyCase ? "Handover" : "Take over"}
                            </button>
                          </>
                        )}
                        <button
                          type="button"
                          className="waiting-action waiting-action--main"
                          style={{ whiteSpace: "nowrap", flexShrink: 0 }}
                          onClick={() => onExamine(r)}
                        >
                          Examine
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function HospitalizedList({ items, total, onDischarge }) {
  if (total === 0) return null;

  return (
    <div className="vet-card waiting-card" style={{ marginTop: "24px" }}>
      <div className="waiting-header">
        <div className="vet-card-title">
          <span className="vet-diamond" style={{ background: "#f55fa9" }} aria-hidden="true" />
          <div>
            <h2>External Hospital Referrals (Chuyển viện ngoài)</h2>
            <p>{total} horse{total === 1 ? "" : "s"} currently hospitalized</p>
          </div>
        </div>
      </div>

      <div className="waiting-table-wrapper">
        <table className="waiting-table">
          <thead>
            <tr>
              <th>Horse</th>
              <th>Hospital & Urgency</th>
              <th>Referral Reason</th>
              <th>Expected Return</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {items.map((ref) => (
              <tr key={ref.referralId}>
                <td className="horse-name">
                  {ref.horseName}
                  {ref.stallNo && <span className="waiting-sub">Stall {ref.stallNo}</span>}
                </td>
                <td>
                  <strong>{ref.hospitalName}</strong>
                  <span className="waiting-sub">
                    <span className={`waiting-status ${ref.urgency === "CRITICAL" ? "urgent" : "soon"}`}>
                      {ref.urgency}
                    </span>
                  </span>
                </td>
                <td>
                  {ref.referralReason}
                  <span className="waiting-sub">Referred by Dr. {ref.referredByName}</span>
                </td>
                <td>
                  {ref.expectedReturn ? ref.expectedReturn : "TBD"}
                  <span className="waiting-sub">Since {formatReportTime(ref.referredAt)}</span>
                </td>
                <td>
                  <button
                    type="button"
                    className="waiting-action waiting-action--main"
                    onClick={() => onDischarge(ref)}
                  >
                    Discharge / Đón về CLB
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function HandoverModal({ report, vets, currentUser, onClose, onSubmit, submitting }) {
  const isMine = report.receivedBy === currentUser?.userId;
  const [targetVetId, setTargetVetId] = useState(
    isMine ? "" : String(currentUser?.userId || "")
  );
  const [notes, setNotes] = useState("");
  const [error, setError] = useState("");

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!notes.trim()) {
      setError("Please provide handover notes (clinical status, medications given, precautions).");
      return;
    }
    setError("");
    onSubmit({
      targetVetId: targetVetId === "" ? null : Number(targetVetId),
      handoverNotes: notes.trim(),
    });
  };

  return (
    <div className="vet-modal-backdrop" onClick={onClose}>
      <div className="vet-modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="vet-modal-header">
          <h3>Handover / Reassign: {report.horseName}</h3>
          <button type="button" className="vet-modal-close" onClick={onClose}>&times;</button>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="vet-modal-body">
            {error && <p className="waiting-error" style={{ margin: 0 }}>{error}</p>}
            <p style={{ margin: 0, color: "#505872", fontSize: "13.5px" }}>
              Bàn giao ca bệnh của <strong>{report.horseName}</strong> cho bác sĩ thú y khác hoặc chuyển về hàng đợi chung để đội ngũ trực tiếp quản.
            </p>

            <div className="vx-form-field">
              <label style={{ display: "block", fontWeight: 700, marginBottom: "6px" }}>
                Target Veterinarian / Người tiếp nhận:
              </label>
              <select
                className="vx-input"
                value={targetVetId}
                onChange={(e) => setTargetVetId(e.target.value)}
                style={{ width: "100%", padding: "8px 12px" }}
              >
                <option value="">-- Release to Team Queue (Hàng đợi chung) --</option>
                {vets.map((v) => (
                  <option key={v.userId} value={v.userId}>
                    Dr. {v.fullName} {v.userId === currentUser?.userId ? "(You - Tiếp quản)" : ""}
                  </option>
                ))}
              </select>
            </div>

            <div className="vx-form-field">
              <label style={{ display: "block", fontWeight: 700, marginBottom: "6px" }}>
                Handover Notes / Ghi chú bàn giao <span style={{ color: "#e43e8c" }}>*</span>:
              </label>
              <textarea
                className="vx-input vx-textarea"
                rows={4}
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="Triệu chứng lâm sàng hiện tại, thuốc đã tiêm/cho uống, lịch theo dõi, dặn dò..."
                style={{ width: "100%", boxSizing: "border-box", padding: "8px 12px" }}
                required
              />
            </div>
          </div>
          <div className="vet-modal-footer">
            <button type="button" className="vx-btn vx-btn--ghost" onClick={onClose} disabled={submitting}>
              Cancel
            </button>
            <button type="submit" className="vx-btn vx-btn--primary" disabled={submitting}>
              {submitting ? "Processing..." : "Confirm Handover"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function DischargeModal({ referral, onClose, onSubmit, submitting }) {
  const [notes, setNotes] = useState("");
  const [postStatus, setPostStatus] = useState("INJURED");
  const [error, setError] = useState("");

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!notes.trim()) {
      setError("Please enter discharge notes and instructions.");
      return;
    }
    setError("");
    onSubmit({
      dischargeNotes: notes.trim(),
      postDischargeStatus: postStatus,
    });
  };

  return (
    <div className="vet-modal-backdrop" onClick={onClose}>
      <div className="vet-modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="vet-modal-header">
          <h3>Hospital Discharge & Return: {referral.horseName}</h3>
          <button type="button" className="vet-modal-close" onClick={onClose}>&times;</button>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="vet-modal-body">
            {error && <p className="waiting-error" style={{ margin: 0 }}>{error}</p>}
            <p style={{ margin: 0, color: "#505872", fontSize: "13.5px" }}>
              Tiếp nhận ngựa <strong>{referral.horseName}</strong> xuất viện từ <strong>{referral.hospitalName}</strong> trở về CLB.
            </p>

            <div className="vx-form-field">
              <label style={{ display: "block", fontWeight: 700, marginBottom: "6px" }}>
                Post-discharge Status / Trạng thái sức khoẻ sau xuất viện:
              </label>
              <select
                className="vx-input"
                value={postStatus}
                onChange={(e) => setPostStatus(e.target.value)}
                style={{ width: "100%", padding: "8px 12px" }}
              >
                <option value="INJURED">Injured (Tiếp tục phác đồ phục hồi/nghỉ ngơi tại CLB)</option>
                <option value="MONITOR">Monitor (Vết thương ổn định, chuyển sang theo dõi)</option>
              </select>
            </div>

            <div className="vx-form-field">
              <label style={{ display: "block", fontWeight: 700, marginBottom: "6px" }}>
                Discharge Notes & Care Instructions / Đánh giá xuất viện & dặn dò <span style={{ color: "#e43e8c" }}>*</span>:
              </label>
              <textarea
                className="vx-input vx-textarea"
                rows={4}
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="Kết quả phẫu thuật/điều trị tại viện ngoài, tình trạng hiện tại, chế độ thuốc men và phục hồi tại CLB..."
                style={{ width: "100%", boxSizing: "border-box", padding: "8px 12px" }}
                required
              />
            </div>
          </div>
          <div className="vet-modal-footer">
            <button type="button" className="vx-btn vx-btn--ghost" onClick={onClose} disabled={submitting}>
              Cancel
            </button>
            <button type="submit" className="vx-btn vx-btn--primary" disabled={submitting}>
              {submitting ? "Processing..." : "Complete Discharge & Return"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function VetDashboard() {
  const navigate = useNavigate();
  const { state } = useLocation();
  const alertRef = useRef(null);

  const [currentUser, setCurrentUser] = useState(null);
  const [apiHorses, setApiHorses] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const [reports, setReports] = useState([]);
  const [referrals, setReferrals] = useState([]);
  const [vets, setVets] = useState([]);
  const [busyId, setBusyId] = useState(null);
  const [actionError, setActionError] = useState("");
  const [waitingCalls, setWaitingCalls] = useState([]);

  // Modals state
  const [handoverModalReport, setHandoverModalReport] = useState(null);
  const [dischargeModalRef, setDischargeModalRef] = useState(null);
  const [modalSubmitting, setModalSubmitting] = useState(false);

  useEffect(() => {
    setCurrentUser(getUser());
  }, []);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    listHorses(ctrl.signal)
      .then(setApiHorses)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reload]);

  // Hàng chờ khám
  useEffect(() => {
    const ctrl = new AbortController();
    listIncidentReports(ctrl.signal)
      .then((rows) => setReports(rows || []))
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setActionError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reload]);

  // Danh sách chuyển viện ngoài
  useEffect(() => {
    const ctrl = new AbortController();
    listExternalReferrals({ status: "HOSPITALIZED" }, ctrl.signal)
      .then((rows) => setReferrals(rows || []))
      .catch((err) => {
        if (err.name !== "AbortError") handleAuthError(err, navigate);
      });
    return () => ctrl.abort();
  }, [navigate, reload]);

  // Danh sách bác sĩ thú y (phục vụ chọn người bàn giao ca)
  useEffect(() => {
    const ctrl = new AbortController();
    listVeterinarians(ctrl.signal)
      .then((rows) => setVets(rows || []))
      .catch((err) => {
        if (err.name !== "AbortError") handleAuthError(err, navigate);
      });
    return () => ctrl.abort();
  }, [navigate]);

  // HLV đang chờ bác sĩ trả lời (V27)
  useEffect(() => {
    const ctrl = new AbortController();
    listVetCalls({ status: "PENDING", limit: 20, signal: ctrl.signal })
      .then((rows) => setWaitingCalls(rows || []))
      .catch((err) => {
        if (err.name !== "AbortError") handleAuthError(err, navigate);
      });
    return () => ctrl.abort();
  }, [navigate, reload]);

  const horses = useMemo(
    () =>
      (apiHorses || [])
        .filter((h) => !isWithdrawn(h) && !isPedigreeOnly(h))
        .map((h) => ({
          id: h.horseId,
          stall: h.stallNo || "—",
          name: h.horseName,
          status: toBoardStatus(h.status),
        }))
        .sort((a, b) => a.stall.localeCompare(b.stall, undefined, { numeric: true })),
    [apiHorses]
  );

  const counts = useMemo(() => {
    const result = { all: horses.length, fit: 0, monitor: 0, injured: 0, quarantine: 0 };
    horses.forEach((horse) => {
      result[horse.status] += 1;
    });
    return result;
  }, [horses]);

  const normalizedQuery = query.trim().toLowerCase();

  const matches = (horse) =>
    (statusFilter === "all" || horse.status === statusFilter) &&
    (!normalizedQuery ||
      horse.name.toLowerCase().includes(normalizedQuery) ||
      horse.stall.toLowerCase().includes(normalizedQuery));

  const visibleWaiting = reports.filter(
    (r) =>
      !normalizedQuery ||
      r.horseName.toLowerCase().includes(normalizedQuery) ||
      r.symptoms.toLowerCase().includes(normalizedQuery)
  );

  const visibleReferrals = referrals.filter(
    (ref) =>
      !normalizedQuery ||
      ref.horseName.toLowerCase().includes(normalizedQuery) ||
      ref.hospitalName.toLowerCase().includes(normalizedQuery)
  );

  const openExamination = (horse) => navigate(`/veterinarian/examination/${horse.id}`);

  const examineWaiting = (report) =>
    navigate(`/veterinarian/examination/${report.horseId}?reportId=${report.reportId}`);

  const receiveReport = (report) => {
    setBusyId(report.reportId);
    setActionError("");
    receiveIncidentReport(report.reportId)
      .then((updated) =>
        setReports((rows) => rows.map((r) => (r.reportId === updated.reportId ? updated : r))))
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        setActionError(err.message);
      })
      .finally(() => setBusyId(null));
  };

  const handleHandoverSubmit = (body) => {
    if (!handoverModalReport) return;
    setModalSubmitting(true);
    handoverIncidentReport(handoverModalReport.reportId, body)
      .then((updated) => {
        setReports((rows) => rows.map((r) => (r.reportId === updated.reportId ? updated : r)));
        setHandoverModalReport(null);
      })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        alert(err.message || "Failed to handover case");
      })
      .finally(() => setModalSubmitting(false));
  };

  const handleDischargeSubmit = (body) => {
    if (!dischargeModalRef) return;
    setModalSubmitting(true);
    dischargeExternalReferral(dischargeModalRef.referralId, body)
      .then(() => {
        setDischargeModalRef(null);
        setReload((n) => n + 1);
      })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        alert(err.message || "Failed to discharge horse");
      })
      .finally(() => setModalSubmitting(false));
  };

  const newest = reports
    .filter((r) => r.status === "PENDING")
    .sort((a, b) => String(b.reportedAt).localeCompare(String(a.reportedAt)))[0];

  const today = new Date().toLocaleDateString("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });

  const statCards = [
    { key: "fit", label: "Race-fit", type: "green" },
    { key: "monitor", label: "Under monitoring", type: "gold" },
    { key: "injured", label: "Injured", type: "red" },
    { key: "quarantine", label: "Quarantine", type: "pink" },
  ];

  return (
    <VetShell
      active="health"
      title="Herd health board"
      subtitle={`Live · ${today}`}
      query={query}
      onQueryChange={setQuery}
    >
      {state?.notice && (
        <div className="vx-banner vx-banner--ok" role="status">
          <strong>{state.notice}</strong>
        </div>
      )}

      {error && (
        <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)}>
          Check that the backend is running on port 8080 and that you are still signed in.
        </VetState>
      )}

      {!error && !apiHorses && <VetState title="Loading herd…" />}

      {apiHorses && (
        <>
          <div className="vet-stats">
            {statCards.map((card) => (
              <StatCard
                key={card.key}
                value={counts[card.key]}
                label={card.label}
                type={card.type}
                active={statusFilter === card.key}
                onClick={() => setStatusFilter(statusFilter === card.key ? "all" : card.key)}
              />
            ))}
          </div>

          {waitingCalls.length > 0 && (
            <button
              type="button"
              className="vet-alert vet-alert--call"
              onClick={() => navigate(
                waitingCalls.length === 1
                  ? `/veterinarian/vet-calls/${waitingCalls[0].callId}`
                  : "/veterinarian/vet-calls"
              )}
            >
              <strong>Vet needed at training:</strong>
              <span>
                {waitingCalls.length === 1
                  ? `${waitingCalls[0].horseName}: ${waitingCalls[0].reason}`
                  : `${waitingCalls.length} head trainer calls are waiting for your advice.`}{" "}
                Answer →
              </span>
            </button>
          )}

          {newest && (
            <div ref={alertRef} className="vet-alert" role="alert">
              <strong>New incident report:</strong>
              <span>
                {newest.horseName}: {newest.symptoms} ({toUrgency(newest.urgency).label.toLowerCase()},
                reported by {newest.reporterName}).
              </span>
            </div>
          )}

          <div className="vet-middle">
            <StableMap
              horses={horses}
              counts={counts}
              statusFilter={statusFilter}
              onStatusChange={setStatusFilter}
              matches={matches}
              onOpen={openExamination}
            />
            <HealthCard counts={counts} total={horses.length} />
          </div>

          {actionError && <p className="waiting-error" role="alert">{actionError}</p>}
          <WaitingList
            items={visibleWaiting}
            total={reports.length}
            onExamine={examineWaiting}
            onReceive={receiveReport}
            onOpenHandover={(rep) => setHandoverModalReport(rep)}
            busyId={busyId}
            currentUser={currentUser}
          />

          <HospitalizedList
            items={visibleReferrals}
            total={referrals.length}
            onDischarge={(ref) => setDischargeModalRef(ref)}
          />

          {handoverModalReport && (
            <HandoverModal
              report={handoverModalReport}
              vets={vets}
              currentUser={currentUser}
              onClose={() => setHandoverModalReport(null)}
              onSubmit={handleHandoverSubmit}
              submitting={modalSubmitting}
            />
          )}

          {dischargeModalRef && (
            <DischargeModal
              referral={dischargeModalRef}
              onClose={() => setDischargeModalRef(null)}
              onSubmit={handleDischargeSubmit}
              submitting={modalSubmitting}
            />
          )}
        </>
      )}
    </VetShell>
  );
}

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

// Nhãn của bốn lớp màu sơ đồ chuồng (fit / monitor / injured / quarantine - xem toBoardStatus trong vetApi.js).
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

// Ô số liệu (đếm ngựa theo trạng thái); bấm để lọc sơ đồ chuồng.
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

// Sơ đồ chuồng: mỗi ô là một ngựa (chuồng + tên) tô màu theo sức khoẻ; bấm để mở trang khám ngựa đó.
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

// Biểu đồ vòng tỉ lệ sức khoẻ đàn (conic-gradient), tính trong useMemo từ counts.
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

// Hàng chờ khám: báo cáo sự cố PENDING (chờ nhận) / RECEIVED (đã có bác sĩ nhận). Nút: Receive (nhận ca),
// Examine (mở form khám kèm ?reportId=), Handover (bàn giao ca cho bác sĩ khác hoặc trả về hàng chờ).
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
                      {r.status === "PENDING" && (
                        <span style={{ display: "inline-block", padding: "1px 6px", borderRadius: "10px", background: "#fef3c7", color: "#92400e", fontWeight: 700, fontSize: "10px", marginLeft: "6px" }}>
                          Unassigned
                        </span>
                      )}
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
                              title="Handover this case to another veterinarian or take over"
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

// Hộp thoại bàn giao ca: ca của mình thì mặc định trả về hàng chờ (""), ca của người khác thì mặc định nhận về mình.
// handoverNotes bắt buộc. Gửi { targetVetId, handoverNotes } -> PUT /api/incident-reports/{id}/handover.
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

/*
 * Trang /veterinarian (Herd health board). Tải riêng từng nguồn (mỗi nguồn một useEffect): ngựa, báo cáo sự cố mở,
 * danh sách bác sĩ (để bàn giao), các cuộc gọi bác sĩ đang chờ (banner V27).
 * reload tăng để tải lại sau thao tác. location.state có thể mang thông báo từ trang khác (vd. sau khi khám xong).
 * Lưu ý: route /veterinarian không bọc RoleRoute (xem App.js); bảo vệ thật nằm ở backend.
 */
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
  const [vets, setVets] = useState([]);
  const [busyId, setBusyId] = useState(null);
  const [actionError, setActionError] = useState("");
  const [waitingCalls, setWaitingCalls] = useState([]);

  // Modals state
  const [handoverModalReport, setHandoverModalReport] = useState(null);
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

  // Chuẩn hoá danh sách ngựa cho sơ đồ: bỏ ngựa đã rút và hồ sơ PEDIGREE, sắp theo số chuồng (so số tự nhiên: A-2 < A-10).
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

  const openExamination = (horse) => navigate(`/veterinarian/examination/${horse.id}`);

  const examineWaiting = (report) =>
    navigate(`/veterinarian/examination/${report.horseId}?reportId=${report.reportId}`);

  // Nhận ca (PENDING -> RECEIVED) rồi thay đúng báo cáo đó trong danh sách bằng bản backend trả về.
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

  // Hai handler dưới dùng window.alert để báo lỗi (chưa chuyển sang khung lỗi trên trang).
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

  // Báo cáo PENDING mới nhất, dùng cho banner cảnh báo ở đầu trang.
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
        </>
      )}
    </VetShell>
  );
}

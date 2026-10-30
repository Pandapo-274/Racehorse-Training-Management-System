import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import VetShell, { VetState } from "./VetShell";
import {
  listHorses, toBoardStatus, listIncidentReports, receiveIncidentReport, toUrgency, formatReportTime,
} from "./vetApi";
import { isPedigreeOnly, isWithdrawn } from "../horse/horseApi";

// Màn 16 - Herd health board. Cùng khung với Trainer và Manager (xem VetShell).
//
// Sơ đồ chuồng và các thẻ đếm lấy từ GET /api/horses (status + stallNo).
// Hàng chờ khám và banner sự cố lấy từ GET /api/incident-reports: báo cáo còn
// mở (PENDING, RECEIVED), sinh ra khi Groom / Head Trainer dừng buổi tập vì
// ngựa bất thường. Khám kèm reportId thì báo cáo đóng (EXAMINED) và rời hàng chờ.

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

function WaitingList({ items, total, onExamine, onReceive, busyId }) {
  // Backend đã xếp khẩn cấp lên trước, nên dòng đầu là ca cần khám nhất.
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
                return (
                  <tr key={r.reportId}>
                    <td className="horse-name">
                      {r.horseName}
                      {r.stallNo && <span className="waiting-sub">Stall {r.stallNo}</span>}
                    </td>
                    <td>
                      {r.symptoms}
                      {r.sessionId && <span className="waiting-sub">Stopped during a training session</span>}
                    </td>
                    <td>
                      {formatReportTime(r.reportedAt)}
                      <span className="waiting-sub">by {r.reporterName}</span>
                    </td>
                    <td>
                      <span className={`waiting-status ${urgency.cls}`}>{urgency.label}</span>
                    </td>
                    <td className="waiting-actions">
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
                        <span className="waiting-sub">Taken by {r.receivedByName}</span>
                      )}
                      <button type="button" className="waiting-action waiting-action--main" onClick={() => onExamine(r)}>
                        Examine
                      </button>
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

export default function VetDashboard() {
  const navigate = useNavigate();
  const { state } = useLocation();
  const alertRef = useRef(null);

  const [apiHorses, setApiHorses] = useState(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const [reports, setReports] = useState([]);
  const [busyId, setBusyId] = useState(null);
  const [actionError, setActionError] = useState("");

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

  // Hàng chờ khám. Lỗi ở đây không chặn bảng sức khoẻ: chỉ hiện dưới khối hàng chờ.
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

  const horses = useMemo(
    () =>
      (apiHorses || [])
        // Ngựa đã rút không còn trong chuồng, hồ sơ PEDIGREE chỉ là tổ tiên
        // trong phả hệ: cả hai đều không lên bảng sức khoẻ.
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

  // Khám kèm reportId: lưu bản khám xong thì backend đóng báo cáo này.
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

  // Banner: báo cáo mới nhất chưa ai nhận.
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
            busyId={busyId}
          />
        </>
      )}
    </VetShell>
  );
}
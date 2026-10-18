import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import VetShell, { VetState } from "./VetShell";
import { listHorses, toBoardStatus } from "./vetApi";

// Màn 16 - Herd health board. Cùng khung với Trainer và Manager (xem VetShell).
//
// Sơ đồ chuồng và các thẻ đếm lấy từ GET /api/horses (status + stallNo).
// Danh sách chờ khám và báo cáo sự cố vẫn là dữ liệu mẫu: backend chưa có
// endpoint liệt kê INCIDENT_REPORT.

// TODO(fetch): waitingHorses -> API ngựa đang chờ khám (WaitingList)
const waitingHorses = [
  { name: "Air Groove", description: "Lame, left hind", priority: "urgent" },
  { name: "TM Opera O", description: "Follow-up after treatment", priority: "normal" },
  { name: "Gold Ship", description: "Routine hoof check", priority: "normal" },
];

// TODO(fetch): incident -> API báo cáo sự cố mới nhất
const incident = {
  message: "Air Groove is lame with swelling in the left hind hock.",
};

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

function WaitingList({ items, onExamine }) {
  const urgent = items.find((item) => item.priority === "urgent");

  return (
    <div className="vet-card waiting-card">
      <div className="waiting-header">
        <div className="vet-card-title">
          <span className="vet-diamond" aria-hidden="true" />
          <div>
            <h2>Waiting to be seen</h2>
            <p>
              {items.length} horse{items.length === 1 ? "" : "s"} in the queue
            </p>
          </div>
        </div>

        {urgent && (
          <button type="button" className="examine-button" onClick={() => onExamine(urgent)}>
            Examine {urgent.name} now
          </button>
        )}
      </div>

      {items.length === 0 ? (
        <div className="vet-inline-state">No horses are waiting for an examination.</div>
      ) : (
        <div className="waiting-table-wrapper">
          <table className="waiting-table">
            <thead>
              <tr>
                <th>Horses</th>
                <th>Reason</th>
                <th>Priority</th>
              </tr>
            </thead>

            <tbody>
              {items.map((horse) => (
                <tr key={horse.name}>
                  <td className="horse-name">{horse.name}</td>
                  <td>{horse.description}</td>
                  <td>
                    <span className={`waiting-status ${horse.priority}`}>
                      {horse.priority === "urgent" ? "Urgent" : "Routine"}
                    </span>
                  </td>
                </tr>
              ))}
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

  const horses = useMemo(
    () =>
      (apiHorses || [])
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

  const visibleWaiting = waitingHorses.filter(
    (horse) =>
      !normalizedQuery ||
      horse.name.toLowerCase().includes(normalizedQuery) ||
      horse.description.toLowerCase().includes(normalizedQuery)
  );

  const openExamination = (horse) => navigate(`/veterinarian/examination/${horse.id}`);

  // Danh sách chờ là dữ liệu mẫu nên chỉ có tên: tìm con ngựa thật cùng tên.
  const examineWaiting = (item) => {
    const match = horses.find((h) => h.name.toLowerCase() === item.name.toLowerCase());
    navigate(match ? `/veterinarian/examination/${match.id}` : "/veterinarian/examination");
  };

  const scrollToAlert = () =>
    alertRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });

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
      onBellClick={scrollToAlert}
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

          <div ref={alertRef} className="vet-alert" role="alert">
            <strong>New incident report:</strong>
            <span>{incident.message}</span>
          </div>

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

          <WaitingList items={visibleWaiting} onExamine={examineWaiting} />
        </>
      )}
    </VetShell>
  );
}
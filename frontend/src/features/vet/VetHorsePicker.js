// src/features/vet/VetHorsePicker.js
// Trang /veterinarian/examination và /veterinarian/recovery khi chưa chọn con
// ngựa nào (bấm từ sidebar). Danh sách lấy từ GET /api/horses.

import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import VetShell, { VetCard, VetState } from "./VetShell";
import { listHorses, toBoardStatus, statusLabel } from "./vetApi";

const ORDER = { injured: 0, quarantine: 1, monitor: 2, fit: 3 };

const COPY = {
  examination: {
    title: "Examination",
    subtitle: "Choose a horse to examine",
    card: "Which horse are you examining?",
    action: "Examine",
    base: "/veterinarian/examination",
  },
  recovery: {
    title: "Recovery",
    subtitle: "Choose a horse to track",
    card: "Horses that are not healthy",
    action: "Track recovery",
    base: "/veterinarian/recovery",
  },
};

export default function VetHorsePicker({ mode }) {
  const copy = COPY[mode];
  const navigate = useNavigate();
  const [horses, setHorses] = useState(null);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setError("");
    listHorses(ctrl.signal)
      .then(setHorses)
      .catch((err) => {
        if (err.name === "AbortError") return;
        if (handleAuthError(err, navigate)) return;
        setError(err.message);
      });
    return () => ctrl.abort();
  }, [navigate, reload]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (horses || [])
      .filter((h) => (mode === "recovery" ? h.status !== "ELIGIBLE" : true))
      .filter(
        (h) =>
          !q ||
          h.horseName.toLowerCase().includes(q) ||
          String(h.stallNo || "").toLowerCase().includes(q) ||
          String(h.registrationCode || "").toLowerCase().includes(q)
      )
      .sort(
        (a, b) =>
          ORDER[toBoardStatus(a.status)] - ORDER[toBoardStatus(b.status)] ||
          a.horseName.localeCompare(b.horseName)
      );
  }, [horses, mode, query]);

  return (
    <VetShell
      active={mode}
      title={copy.title}
      subtitle={copy.subtitle}
      query={query}
      onQueryChange={setQuery}
    >
      {error && (
        <VetState kind="error" title={error} onRetry={() => setReload((n) => n + 1)}>
          Check that the backend is running on port 8080 and that you are still signed in.
        </VetState>
      )}

      {!error && !horses && <VetState title="Loading horses…" />}

      {horses && (
        <VetCard title={copy.card} hint={`${rows.length} horse${rows.length === 1 ? "" : "s"}`}>
          {rows.length === 0 ? (
            <div className="vet-inline-state">
              {mode === "recovery" && !query
                ? "Every horse is healthy. Nothing to track right now."
                : "No horses match your search."}
            </div>
          ) : (
            <ul className="vx-picker-list">
              {rows.map((h) => {
                const board = toBoardStatus(h.status);
                return (
                  <li key={h.horseId}>
                    <button
                      type="button"
                      className="vx-picker-row"
                      onClick={() => navigate(`${copy.base}/${h.horseId}`)}
                    >
                      <span className={`vx-dot vx-dot--${board}`} aria-hidden="true" />
                      <span className="vx-picker-name">
                        <strong>{h.horseName}</strong>
                        <small>
                          {h.registrationCode} · Stall {h.stallNo || "—"}
                        </small>
                      </span>
                      <span className={`vx-chip vx-chip--${board}`}>{statusLabel(h.status)}</span>
                      <span className="vx-picker-go">{copy.action} →</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </VetCard>
      )}
    </VetShell>
  );
}
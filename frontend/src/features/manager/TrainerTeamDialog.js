// src/features/manager/TrainerTeamDialog.js
// Popup on the Staff screen: the grooms and the club horses of one Head Trainer,
// opened by clicking "Grooms: x/3 · Horses: y/z" on the trainer's row.
// Same look as AppDialog (appDialog.css), with a wider card for two lists.
import { useEffect, useState } from "react";
import ReactDOM from "react-dom";
import { Link, useNavigate } from "react-router-dom";
import { handleAuthError } from "../auth/authService";
import { StatusChip } from "../horse/HorseShell";
import { toStatus, toGender } from "../horse/horseApi";
import { getTrainerTeam, initialsOf, statusTone, statusLabel } from "./staffApi";
import "../common/appDialog.css";
import "./staff.css";

function Thumb({ src, name }) {
  return src
    ? <img className="tt-thumb" src={src} alt="" />
    : <span className="tt-thumb tt-thumb--text" aria-hidden="true">{initialsOf(name)}</span>;
}

export default function TrainerTeamDialog({ row, focus = "grooms", onClose }) {
  const navigate = useNavigate();
  const [team, setTeam] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let alive = true;
    getTrainerTeam(row.userId)
      .then((t) => { if (alive) setTeam(t); })
      .catch((err) => {
        if (handleAuthError(err, navigate)) return;
        if (alive) setError(err.message);
      });
    return () => { alive = false; };
  }, [row.userId, navigate]);

  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const activeGrooms = team ? team.grooms.filter((g) => g.status === "ACTIVE").length : 0;
  const maxHorses = team?.trainer?.maxHorses ?? row.maxHorses ?? 10;

  const groomSection = () => (
    <section className="tt-section" key="grooms">
      <h4 className="tt-heading">
        Grooms <span className="tt-count">{activeGrooms}/3 active</span>
      </h4>
      {team.grooms.length === 0 ? (
        <p className="tt-empty">No groom is assigned to this Head Trainer yet.</p>
      ) : (
        <ul className="tt-list">
          {team.grooms.map((g) => (
            <li key={g.userId} className="tt-item">
              <Thumb src={g.avatarUrl} name={g.fullName} />
              <div className="tt-main">
                <p className="tt-name">{g.fullName}</p>
                <p className="tt-sub">@{g.username}{g.phone ? ` · ${g.phone}` : ""}</p>
              </div>
              <StatusChip tone={statusTone(g.status)} label={statusLabel(g.status)} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );

  const horseSection = () => (
    <section className="tt-section" key="horses">
      <h4 className="tt-heading">
        Horses <span className="tt-count">{team ? team.horses.length : 0}/{maxHorses}</span>
      </h4>
      {team.horses.length === 0 ? (
        <p className="tt-empty">No club horse is in this Head Trainer's charge.</p>
      ) : (
        <ul className="tt-list">
          {team.horses.map((h) => {
            const status = toStatus(h.status);
            return (
              <li key={h.horseId} className="tt-item">
                <Thumb src={h.photoUrl} name={h.horseName} />
                <div className="tt-main">
                  <Link className="tt-name tt-link" to={`/manager/horses/${h.horseId}`} onClick={onClose}>
                    {h.horseName}
                  </Link>
                  <p className="tt-sub">
                    {[h.registrationCode, toGender(h.horseGender), h.stallNo ? `Stall ${h.stallNo}` : null]
                      .filter((v) => v && v !== "—").join(" · ") || "—"}
                  </p>
                </div>
                <StatusChip tone={status.tone} label={status.label} title={status.hint} />
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );

  return ReactDOM.createPortal(
    <div className="app-dialog-backdrop" onClick={onClose}
         role="dialog" aria-modal="true" aria-labelledby="tt-title">
      <div className="app-dialog-card tt-card" onClick={(e) => e.stopPropagation()}>
        <h3 id="tt-title" className="app-dialog-title">{row.fullName}'s team</h3>
        <p className="app-dialog-message">@{row.username} · Head Trainer</p>

        {error && <p className="app-dialog-error" role="alert">{error}</p>}
        {!error && team === null && <p className="tt-empty">Loading…</p>}
        {!error && team !== null && (
          <div className="tt-body">
            {focus === "horses" ? [horseSection(), groomSection()] : [groomSection(), horseSection()]}
          </div>
        )}

        <div className="app-dialog-actions">
          <button type="button" className="app-dialog-btn app-dialog-btn--cancel" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

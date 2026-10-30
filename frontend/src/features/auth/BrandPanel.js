// src/features/auth/BrandPanel.js
import { formatStat, useClubStats } from "../landingpage/clubStatsApi";

export default function BrandPanel() {
  // Số thật từ GET /api/public/club-stats (xem clubStatsApi.js).
  const club = useClubStats();
  const STATS = [
    { value: formatStat(club?.horsesInTraining), label: "Horses", color: "var(--pink-500)" },
    { value: formatStat(club?.activePlans), label: "Active plans", color: "var(--gold-500)" },
    { value: formatStat(club?.racesThisSeason), label: `Races in ${club?.season ?? new Date().getFullYear()}`, color: "var(--rose-500)" },
  ];

  return (
    <aside className="brand">
      <img src="/logo.png" alt="Tracen Club" className="brand__logo" />
      <h1 className="brand__title">TRACEN CLUB</h1>
      <div className="brand__rule" />
      <p className="brand__sub">Racehorse training management system</p>

      <ul className="stats">
        {STATS.map((s) => (
          <li key={s.label} className="stat" style={{ "--accent": s.color }}>
            <strong>{s.value}</strong>
            <span>{s.label}</span>
          </li>
        ))}
      </ul>
    </aside>
  );
}

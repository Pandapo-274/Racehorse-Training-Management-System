// src/features/auth/BrandPanel.js


const STATS = [
  { value: 48, label: "Horses",           color: "var(--pink-500)" },
  { value: 12, label: "Active plans",     color: "var(--gold-500)" },
  { value: 7,  label: "G1 races in 2026", color: "var(--rose-500)" },
];

export default function BrandPanel() {
  return (
    <aside className="brand">
      <h1 className="brand__title">TENMA ACADEMY</h1>
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
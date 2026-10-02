// src/features/auth/BrandPanel.js


const STATS = [
  { value: 48, label: "Chiến mã",          color: "var(--teal-500)" },
  { value: 12, label: "Giáo án đang chạy", color: "var(--gold-500)" },
  { value: 7,  label: "Giải G1 mùa 2026",  color: "var(--pink-500)" },
];

export default function BrandPanel() {
  return (
    <aside className="brand">
      <h1 className="brand__title">HỌC VIỆN THIÊN MÃ</h1>
      <div className="brand__rule" />
      <p className="brand__sub">Hệ thống quản lý huấn luyện ngựa đua</p>

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
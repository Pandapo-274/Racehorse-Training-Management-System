// src/components/ui/EyeIcon.js
// Con mắt cho nút hiện/ẩn mật khẩu. `off` = mắt gạch chéo (mật khẩu đang hiện,
// bấm để ẩn). Dùng currentColor để màu đổi theo CSS của nút.
export default function EyeIcon({ off = false }) {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
      {off && <path d="M4 4l16 16" />}
    </svg>
  );
}
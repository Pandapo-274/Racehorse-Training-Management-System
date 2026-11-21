// src/components/ui/Button.js
// Nút dùng chung: variant -> lớp CSS "btn btn--primary" / "btn--secondary"... (định nghĩa trong ui.css).
// ...props chuyển mọi thuộc tính còn lại (onClick, disabled, type...) xuống thẻ <button>.
export default function Button({ variant = "primary", children, ...props }) {
  return (
    <button type="button" className={`btn btn--${variant}`} {...props}>
      <span className="btn__label">{children}</span>
    </button>
  );
}

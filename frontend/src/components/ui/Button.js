// src/components/ui/Button.js
export default function Button({ variant = "primary", children, ...props }) {
  return (
    <button type="button" className={`btn btn--${variant}`} {...props}>
      <span className="btn__label">{children}</span>
    </button>
  );
}

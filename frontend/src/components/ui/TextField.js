// src/components/ui/TextField.js
export default function TextField({ label, id, error, ...inputProps }) {
  return (
    <div className="field">
      <label htmlFor={id} className="field__label">{label}</label>
      <input
        id={id}
        className={`field__input${error ? " field__input--error" : ""}`}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        {...inputProps}
      />
      {error && <p id={`${id}-error`} className="field__error">{error}</p>}
    </div>
  );
}
// src/components/ui/TextField.js
import { useState } from "react";
import EyeIcon from "./EyeIcon";

export default function TextField({ label, id, error, type, ...inputProps }) {
  const isPassword = type === "password";
  const [visible, setVisible] = useState(false);

  const input = (
    <input
      id={id}
      className={`field__input${error ? " field__input--error" : ""}${isPassword ? " field__input--with-toggle" : ""}`}
      type={isPassword && visible ? "text" : type}
      aria-invalid={!!error}
      aria-describedby={error ? `${id}-error` : undefined}
      {...inputProps}
    />
  );

  return (
    <div className="field">
      <label htmlFor={id} className="field__label">{label}</label>

      {isPassword ? (
        <div className="field__control">
          {input}
          <button
            type="button"
            className="field__toggle"
            onClick={() => setVisible((v) => !v)}
            /* Nhãn mô tả việc nút sẽ làm khi bấm, không phải trạng thái hiện tại. */
            aria-label={visible ? "Hide password" : "Show password"}
            aria-pressed={visible}
            title={visible ? "Hide password" : "Show password"}
          >
            <EyeIcon off={visible} />
          </button>
        </div>
      ) : (
        input
      )}

      {error && <p id={`${id}-error`} className="field__error">{error}</p>}
    </div>
  );
}
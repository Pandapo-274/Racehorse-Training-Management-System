// src/features/password/PasswordField.js
// Ô nhập mật khẩu dùng chung cho cả ba màn của UC5.
//
// Có nút hiện/ẩn vì mật khẩu mới phải vừa có chữ vừa có số và dài ít nhất 8 ký
// tự - gõ mò trong một chuỗi chấm tròn là nguồn gốc của phần lớn lần "sai mật
// khẩu" ngay sau khi vừa đổi.
import { useId, useState } from "react";
import { passwordRules } from "./passwordApi";

export default function PasswordField({
  id, label, value, onChange, error, hint, autoComplete, showRules = false,
}) {
  const [visible, setVisible] = useState(false);
  const describedBy = useId();

  return (
    <div className="pw-field">
      <label htmlFor={id}>{label}</label>

      <div className="pw-input">
        <input
          id={id}
          name={id}
          type={visible ? "text" : "password"}
          value={value}
          onChange={onChange}
          autoComplete={autoComplete}
          maxLength={72}
          aria-invalid={!!error}
          aria-describedby={showRules ? describedBy : undefined}
        />

        <button
          type="button"
          className="pw-toggle"
          onClick={() => setVisible((v) => !v)}
          /* Nhãn mô tả việc nút sẽ làm, không mô tả trạng thái hiện tại -
             người dùng trình đọc màn hình cần biết bấm vào thì được gì. */
          aria-label={visible ? "Hide password" : "Show password"}
          aria-pressed={visible}
        >
          {visible ? "Hide" : "Show"}
        </button>
      </div>

      {error ? <p className="pw-error">{error}</p>
        : hint ? <p className="pw-hint">{hint}</p> : null}

      {/* Checklist sống: hiện ngay trong lúc gõ, không chờ bấm nút mới báo.
          aria-live để trình đọc màn hình đọc ra khi một luật vừa đạt. */}
      {showRules && (
        <ul className="pw-rules" id={describedBy} aria-live="polite">
          {passwordRules(value).map((r) => (
            <li key={r.id} className={r.ok ? "ok" : ""}>
              {/* Ký hiệu đi kèm chữ, không để màu một mình mang nghĩa. */}
              <span aria-hidden="true">{r.ok ? "✓" : "○"}</span>
              {r.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

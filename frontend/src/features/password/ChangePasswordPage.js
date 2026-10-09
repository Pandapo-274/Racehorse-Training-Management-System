// src/features/password/ChangePasswordPage.js
// UC5 - đổi mật khẩu khi đang đăng nhập.
//
// Điểm quyết định toàn bộ cách màn này cư xử: đổi xong, backend huỷ MỌI phiên
// đăng nhập, kể cả token vừa dùng để gọi chính lời gọi đó
// (blacklist.revoke + revokeAllSessions trong PasswordService).
//
// Nghĩa là ngay sau khi thành công, token trong localStorage đã chết. Nếu màn
// hình cứ ở lại, lời gọi API kế tiếp sẽ trả 401 và người dùng bị văng ra trang
// đăng nhập mà không hiểu vì sao - trông như vừa đổi mật khẩu xong thì hệ
// thống lỗi. Nên ở đây tự đăng xuất và đưa về /login kèm một câu giải thích,
// biến một cú văng ra khó hiểu thành một bước có chủ đích.
import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { handleAuthError, logout } from "../auth/authService";
import HorseShell, { Card } from "../horse/HorseShell";
import PasswordField from "./PasswordField";
import { changePassword, checkNewPassword } from "./passwordApi";
import "./password.css";

const EMPTY = { currentPassword: "", newPassword: "", confirmPassword: "" };

export default function ChangePasswordPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [saving, setSaving] = useState(false);

  const change = (e) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
    if (errors[name]) setErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  const validate = () => {
    const e = {};
    if (!form.currentPassword) e.currentPassword = "Current password is required";

    const problem = checkNewPassword(form.newPassword);
    if (problem) e.newPassword = problem;
    else if (form.newPassword === form.currentPassword) {
      // Backend cũng chặn, nhưng chặn luôn ở đây thì đỡ một vòng mạng và người
      // dùng biết ngay thay vì chờ phản hồi.
      e.newPassword = "New password must be different from the current one";
    }

    if (!form.confirmPassword) e.confirmPassword = "Repeat the new password";
    else if (form.confirmPassword !== form.newPassword) {
      e.confirmPassword = "The two passwords do not match";
    }

    return e;
  };

  const submit = async (e) => {
    e.preventDefault();

    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSaving(true);
    setSubmitError("");

    try {
      await changePassword(form);

      // Token vừa bị huỷ ở phía máy chủ. Dọn phía client cho khớp rồi đưa về
      // trang đăng nhập; `state` để LoginPage hiện lý do, nếu sau này ai muốn
      // đọc nó - không đọc cũng không sao, màn hình vẫn hoạt động bình thường.
      logout();
      navigate("/login", {
        replace: true,
        state: { notice: "Password changed. Please sign in with your new password." },
      });
    } catch (err) {
      // Sai mật khẩu hiện tại về đây dưới dạng 400 kèm errors.currentPassword,
      // không phải 401, nên handleAuthError không nuốt mất nó.
      if (handleAuthError(err, navigate)) return;

      const fieldErrors = err.fieldErrors || {};
      if (Object.keys(fieldErrors).length > 0) setErrors(fieldErrors);
      else setSubmitError(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <HorseShell
      title="Change password"
      subtitle="You will be signed out everywhere once it is changed"
    >
      <form onSubmit={submit} noValidate className="pw-page">
        <Card title="New password">
          {/* Nói trước hậu quả, không để nó là một bất ngờ sau khi bấm nút.
              Ai đang đăng nhập trên điện thoại cần biết mình sắp bị đăng xuất
              ở đó nữa. */}
          <div className="pw-warning">
            <span className="pw-warning__mark" aria-hidden="true">!</span>
            <div>
              <strong>Every device will be signed out</strong>
              <p>
                Changing your password ends all sessions, including this one. You will be
                asked to sign in again straight away.
              </p>
            </div>
          </div>

          <div className="pw-grid">
            <PasswordField
              id="currentPassword"
              label="Current password"
              value={form.currentPassword}
              onChange={change}
              error={errors.currentPassword}
              autoComplete="current-password"
            />

            <PasswordField
              id="newPassword"
              label="New password"
              value={form.newPassword}
              onChange={change}
              error={errors.newPassword}
              autoComplete="new-password"
              showRules
            />

            <PasswordField
              id="confirmPassword"
              label="Repeat new password"
              value={form.confirmPassword}
              onChange={change}
              error={errors.confirmPassword}
              autoComplete="new-password"
            />
          </div>

          {submitError && <p className="pw-form-error" role="alert">{submitError}</p>}

          <div className="pw-actions">
            <button type="submit" className="hz-btn" disabled={saving}>
              {saving ? "Changing…" : "Change password"}
            </button>

            <Link className="hz-btn hz-btn--ghost pw-link-btn" to="/profile">
              Cancel
            </Link>
          </div>
        </Card>
      </form>
    </HorseShell>
  );
}

// src/features/password/ResetPasswordPage.js
// UC5 - đặt lại mật khẩu từ liên kết trong thư. Công khai.
//
// Đường dẫn do backend tự sinh ra, không phải tôi chọn:
//     {app.frontend-base-url}/reset-password?token=...
// mặc định là http://localhost:3000/reset-password?token=... Nên tuyến phải
// đúng tên /reset-password và phải đọc token từ query, nếu không mọi liên kết
// gửi đi đều dẫn tới trang trắng.
import { useState } from "react";
import { useNavigate, useSearchParams, Link } from "react-router-dom";
import BrandPanel from "../auth/BrandPanel";
import Button from "../../components/ui/Button";
import PasswordField from "./PasswordField";
import { resetPassword, checkNewPassword } from "./passwordApi";
import "../auth/auth.css";
import "./password.css";

export default function ResetPasswordPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get("token") || "";

  const [form, setForm] = useState({ newPassword: "", confirmPassword: "" });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(false);

  const change = (e) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
    if (errors[name]) setErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  const submit = async (e) => {
    e.preventDefault();

    const found = {};
    const problem = checkNewPassword(form.newPassword);
    if (problem) found.newPassword = problem;
    if (!form.confirmPassword) found.confirmPassword = "Repeat the new password";
    else if (form.confirmPassword !== form.newPassword) {
      found.confirmPassword = "The two passwords do not match";
    }

    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSaving(true);
    setSubmitError("");

    try {
      await resetPassword({ token, newPassword: form.newPassword });
      setDone(true);
    } catch (err) {
      // Liên kết hết hạn hoặc đã dùng rồi: 400 kèm errors.token. Hiện ở mức
      // toàn trang chứ không gắn vào ô nào, vì không ô nào trên màn này sửa
      // được nó - người dùng phải đi xin một liên kết mới.
      const fieldErrors = err.fieldErrors || {};
      if (fieldErrors.token) setSubmitError(fieldErrors.token);
      else if (Object.keys(fieldErrors).length > 0) setErrors(fieldErrors);
      else setSubmitError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const shell = (children) => (
    <main className="auth">
      <div className="auth__band auth__band--teal" aria-hidden="true" />
      <div className="auth__band auth__band--pink" aria-hidden="true" />

      <BrandPanel />

      <section className="auth__right">
        <div className="login-card">
          <span className="corner corner--tl" />
          <span className="corner corner--tr" />
          <span className="corner corner--bl" />
          <span className="corner corner--br" />
          {children}
        </div>
      </section>
    </main>
  );

  // Vào thẳng /reset-password mà không có token thì không có gì để làm cả.
  // Hiện biểu mẫu rồi mới báo lỗi sau khi bấm là bắt người ta gõ không công.
  if (!token) {
    return shell(
      <>
        <h2 className="login-card__title">Link is incomplete</h2>
        <p className="login-card__hint">
          This page needs the link from your reset email. Open that link directly, or ask
          for a new one.
        </p>
        <Button type="button" onClick={() => navigate("/forgot-password")}>
          Request a new link
        </Button>
      </>
    );
  }

  if (done) {
    return shell(
      <>
        <h2 className="login-card__title">Password reset</h2>
        <p className="login-card__hint">
          Your new password is in place, and every other session has been signed out.
        </p>
        <Button type="button" onClick={() => navigate("/login")}>
          Go to sign in
        </Button>
      </>
    );
  }

  return shell(
    <>
      <h2 className="login-card__title">Set a new password</h2>
      <p className="login-card__hint">
        Choose something you have not used here before. The link works once.
      </p>

      <form onSubmit={submit} noValidate>
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

        {submitError && (
          <div className="pw-token-error" role="alert">
            <strong>{submitError}</strong>
            <Link to="/forgot-password">Request a new link</Link>
          </div>
        )}

        <Button type="submit" disabled={saving}>
          {saving ? "Saving…" : "Set new password"}
        </Button>
      </form>

      <small className="login-card__foot pw-foot">
        <Link to="/login">Back to sign in</Link>
      </small>
    </>
  );
}

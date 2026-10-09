// src/features/password/ForgotPasswordPage.js
// UC5 - quên mật khẩu. Công khai, không cần đăng nhập.
//
// Dùng lại khung của trang đăng nhập (dải màu chéo, BrandPanel, thẻ trắng bốn
// góc vàng) vì người dùng tới đây từ trang đăng nhập và sẽ quay lại đó.
import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import BrandPanel from "../auth/BrandPanel";
import Button from "../../components/ui/Button";
import TextField from "../../components/ui/TextField";
import { forgotPassword } from "./passwordApi";
import "../auth/auth.css";
import "./password.css";

export default function ForgotPasswordPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(null);

  const submit = async (e) => {
    e.preventDefault();

    const value = email.trim();
    if (!value) return setError("Please enter your email");
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(value)) {
      return setError("This does not look like an email address");
    }
    if (value.length > 100) return setError("Email must be 100 characters or fewer");

    setSending(true);
    setError("");

    try {
      const res = await forgotPassword(value);
      setSent(res?.message || "");
    } catch (err) {
      setError(err.message);
    } finally {
      setSending(false);
    }
  };

  return (
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

          {sent !== null ? (
            /* Hiện đúng câu máy chủ trả về, không diễn giải thêm.
               Máy chủ cố ý trả cùng một câu dù email có tài khoản hay không,
               để người ngoài không dò được email nào đã đăng ký. Thêm vào một
               câu kiểu "chúng tôi không tìm thấy email này" là phá đúng cái
               đang được bảo vệ. */
            <>
              <h2 className="login-card__title">Check your inbox</h2>

              <p className="pw-sent">{sent}</p>

              <p className="pw-sent-note">
                The link works once and expires after 30 minutes. If nothing arrives,
                check that you typed the address you registered with.
              </p>

              <Button type="button" onClick={() => navigate("/login")}>
                Back to sign in
              </Button>
            </>
          ) : (
            <>
              <h2 className="login-card__title">Forgot your password</h2>

              <p className="login-card__hint">
                Enter the email on your academy account and we will send a link to set a
                new password.
              </p>

              <form onSubmit={submit} noValidate>
                <TextField
                  id="email"
                  name="email"
                  type="email"
                  label="Email"
                  value={email}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    if (error) setError("");
                  }}
                  error={error}
                  autoComplete="email"
                  maxLength={100}
                />

                <Button type="submit" disabled={sending}>
                  {sending ? "Sending…" : "Send reset link"}
                </Button>
              </form>

              <small className="login-card__foot pw-foot">
                Remembered it? <Link to="/login">Back to sign in</Link>
              </small>
            </>
          )}
        </div>
      </section>
    </main>
  );
}

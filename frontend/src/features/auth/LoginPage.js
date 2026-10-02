// src/features/auth/LoginPage.js
import BrandPanel from "./BrandPanel";
import LoginForm from "./LoginForm";
import Button from "../../components/ui/Button";
import "./auth.css";

export default function LoginPage() {
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

          <h2 className="login-card__title">Đăng nhập</h2>
          <p className="login-card__hint">Tài khoản được cấp theo vai trò trong học viện.</p>

          <LoginForm />

          <small className="login-card__foot">Quên mật khẩu? Liên hệ quản lý học viện.</small>
        </div>

        <Button variant="secondary" type="button" className="portal-btn">Trang cổng</Button>
      </section>
    </main>
  );
}
// src/features/auth/LoginPage.js
import { Link, useNavigate } from "react-router-dom";
import BrandPanel from "./BrandPanel";
import LoginForm from "./LoginForm";
import Button from "../../components/ui/Button";
import "./auth.css";

export default function LoginPage() {
  const navigate = useNavigate();

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

          <h2 className="login-card__title">Sign in</h2>

          <p className="login-card__hint">
            Accounts are issued by role within the academy.
          </p>

          <LoginForm />

          {/* UC5 đã có luồng tự đặt lại mật khẩu, không phải đi nhờ quản lý nữa. */}
          <small className="login-card__foot">
            <Link to="/forgot-password">Forgot your password?</Link>
          </small>

          <small className="login-card__foot">
            No account yet?{" "}
            <Link to="/register">Register as a horse owner</Link>
          </small>
        </div>

        {/* Back to Home button */}
        <Button
          variant="secondary"
          type="button"
          className="portal-btn"
          onClick={() => navigate("/")}
        >
          Back to Home Screen
        </Button>
      </section>
    </main>
  );
}
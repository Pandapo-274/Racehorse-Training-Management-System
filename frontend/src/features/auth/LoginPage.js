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

          <h2 className="login-card__title">Sign in</h2>
          <p className="login-card__hint">Accounts are issued by role within the academy.</p>

          <LoginForm />

          <small className="login-card__foot">Forgot your password? Contact the academy manager</small>
        </div>

        <Button variant="secondary" type="button" className="portal-btn">Landing page</Button>
      </section>
    </main>
  );
}
// src/features/auth/RegisterPage.js  (UC1) - cùng khung với LoginPage
import { useNavigate } from "react-router-dom";
import BrandPanel from "./BrandPanel";
import RegisterForm from "./RegisterForm";
import Button from "../../components/ui/Button";
import "./auth.css";
import "./register.css";

export default function RegisterPage() {
  const navigate = useNavigate();
  return (
    <main className="auth">
      <div className="auth__band auth__band--teal" aria-hidden="true" />
      <div className="auth__band auth__band--pink" aria-hidden="true" />

      <BrandPanel />

      <section className="auth__right">
        <div className="login-card register-card">
          <span className="corner corner--tl" />
          <span className="corner corner--tr" />
          <span className="corner corner--bl" />
          <span className="corner corner--br" />

          <h2 className="login-card__title">Create an account</h2>
          <p className="login-card__hint">
            Register as a horse owner to follow your horses through the academy.
          </p>

          <RegisterForm />
        </div>

        <Button variant="secondary" type="button" className="portal-btn"
          onClick={() => navigate("/")}>Landing page</Button>
      </section>
    </main>
  );
}

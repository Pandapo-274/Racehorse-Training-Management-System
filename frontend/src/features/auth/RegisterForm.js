// src/features/auth/RegisterForm.js  (UC1)
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import TextField from "../../components/ui/TextField";
import Button from "../../components/ui/Button";
import { ApiError } from "./authService";
import { registerAccount } from "./registerService";

const EMPTY = {
  fullName: "", username: "", email: "", phone: "", password: "", repeatPassword: "",
};

/** Luật khớp RegisterRequest.java + bảng APP_USER. Trả { field: message } cho các ô sai. */
function validate(f) {
  const e = {};
  const fullName = f.fullName.trim();
  const username = f.username.trim();
  const email = f.email.trim();
  const phone = f.phone.trim();

  if (!fullName) e.fullName = "Please enter your full name";
  else if (fullName.length > 100) e.fullName = "Use at most 100 characters";

  if (!username) e.username = "Please choose a username";
  else if (!/^[A-Za-z0-9._]+$/.test(username)) e.username = "Use letters, digits, dots and underscores only";
  else if (username.length < 3 || username.length > 50) e.username = "Username must be 3-50 characters";

  if (!email) e.email = "Please enter your email";
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) e.email = "This does not look like an email address";
  else if (email.length > 100) e.email = "Use at most 100 characters";

  if (phone && !/^0\d{9}$/.test(phone)) e.phone = "Enter 10 digits starting with 0, or leave this empty";

  if (!f.password) e.password = "Please choose a password";
  else if (f.password.length < 8) e.password = "Use at least 8 characters";
  else if (f.password.length > 72) e.password = "Use at most 72 characters";
  else if (!/[A-Za-z]/.test(f.password) || !/\d/.test(f.password))
    e.password = "Password must contain at least one letter and one digit";

  if (!f.repeatPassword) e.repeatPassword = "Please repeat the password";
  else if (f.password !== f.repeatPassword) e.repeatPassword = "The two passwords do not match";

  return e;
}

export default function RegisterForm() {
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [loading, setLoading] = useState(false);
  const [created, setCreated] = useState(null);

  // Sửa ô nào thì chỉ xoá lỗi của ô đó, lỗi các ô khác giữ nguyên.
  const handleChange = (e) => {
    const { name, value } = e.target;
    setForm((f) => ({ ...f, [name]: value }));
    setErrors((er) => ({ ...er, [name]: undefined }));
    setSubmitError("");
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSubmitError("");
    const found = validate(form);
    setErrors(found);
    if (Object.keys(found).length) return;

    setLoading(true);
    try {
      setCreated(await registerAccount(form));
    } catch (err) {
      if (!(err instanceof ApiError)) {
        setSubmitError("Cannot reach the server. Check that the backend is running and try again.");
      } else if (err.status === 409 || err.status === 400) {
        // Backend trả errors theo field (username/email trùng, hoặc @Valid fail) -> tô đúng ô.
        const byField = { ...err.fieldErrors };
        const { roleName, ...inputs } = byField;     // roleName không có ô nhập, đưa xuống cuối form
        const hasInput = Object.keys(inputs).length > 0;
        if (hasInput) setErrors(inputs);
        // 409 mà không nói ô nào -> mặc định coi là username
        else if (err.status === 409) setErrors({ username: err.message });
        if (roleName || (!hasInput && err.status === 400)) setSubmitError(roleName || err.message);
      } else {
        setSubmitError(`${err.message} (HTTP ${err.status})`);
      }
    } finally {
      setLoading(false);
    }
  };

  if (created) {
    return (
      <div className="register-done" role="status">
        <h3 className="register-done__title">Welcome to the academy, {created.fullName}</h3>
        <p className="register-done__text">
          Your username is <strong>{created.username}</strong>. Sign in with it and the password you just chose.
        </p>
        <Button type="button" onClick={() => navigate("/login")}>Go to sign in</Button>
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <TextField id="fullName" name="fullName" label="Full name"
        value={form.fullName} onChange={handleChange}
        error={errors.fullName} autoComplete="name" />
      <TextField id="username" name="username" label="Username"
        value={form.username} onChange={handleChange}
        error={errors.username} autoComplete="username" />
      <TextField id="email" name="email" type="email" label="Email"
        value={form.email} onChange={handleChange}
        error={errors.email} autoComplete="email" />
      <TextField id="phone" name="phone" type="tel" label="Phone (optional)"
        value={form.phone} onChange={handleChange}
        error={errors.phone} autoComplete="tel" />

      <div className="register-row">
        <TextField id="password" name="password" type="password" label="Password"
          value={form.password} onChange={handleChange}
          error={errors.password} autoComplete="new-password" />
        <TextField id="repeatPassword" name="repeatPassword" type="password" label="Repeat password"
          value={form.repeatPassword} onChange={handleChange}
          error={errors.repeatPassword} autoComplete="new-password" />
      </div>

      {/* Chỉ để đọc, không phải ô chọn: xem quyết định thiết kế ở tài liệu UC1 */}
      <div className="register-role">
        <span className="register-role__label">Account type</span>
        <div className="register-role__box">
          <strong>Horse Owner</strong>
          <span>Trainer, veterinarian, groom and manager accounts are issued by the academy manager.</span>
        </div>
      </div>

      {submitError && <p className="field__error form-error" role="alert">{submitError}</p>}

      <Button type="submit" disabled={loading}>
        {loading ? "Creating account..." : "Create account"}
      </Button>

      <small className="register-foot">
        Already have an account? <Link to="/login">Sign in</Link>
      </small>
    </form>
  );
}

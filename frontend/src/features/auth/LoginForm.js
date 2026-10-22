// src/features/auth/LoginForm.js
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import TextField from "../../components/ui/TextField";
import Button from "../../components/ui/Button";
import { GoogleLogin } from "@react-oauth/google";
import { login, loginWithGoogle, HOME_BY_ROLE, GOOGLE_CLIENT_ID } from "./authService";

/**
 * Lời nhắn sau khi đăng nhập Google, theo `outcome` của backend.
 * LOGIN thì không có gì để nói, vào thẳng. LINKED bắt buộc phải báo: mật khẩu
 * cũ của tài khoản vừa bị vô hiệu, không nói thì lần sau người dùng đăng nhập
 * thường sẽ bị báo sai mật khẩu mà không hiểu vì sao.
 */
const GOOGLE_NOTICE = {
  REGISTERED: {
    title: "Welcome to the club",
    text: "Your account has been created with Google.",
  },
  LINKED: {
    title: "Google is now linked to your account",
    text: "Your old password was disabled; use Forgot password to set a new one.",
  },
};

export default function LoginForm() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ username: "", password: "" });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [loading, setLoading] = useState(false);
  // Sau đăng nhập Google: { outcome, home } để hiện lời nhắn trước khi vào trang.
  const [googleDone, setGoogleDone] = useState(null);

  const handleChange = (e) =>
    setForm({ ...form, [e.target.name]: e.target.value });

  const validate = () => {
    const next = {};
    if (!form.username.trim()) next.username = "Please enter your username";
    if (!form.password) next.password = "Please enter your password";
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const signInAs = async (username, password) => {
    setLoading(true);
    setSubmitError("");
    try {
      const user = await login(username, password); // login() đã lưu token + user
      navigate(HOME_BY_ROLE[user.role] || "/access-denied", { replace: true });
    } catch (err) {
      setSubmitError(err.message);
    } finally {
      setLoading(false);
    }
  };

  /* credential là token của Google: chỉ chuyển cho backend, không lưu, không log. */
  const handleGoogle = async ({ credential }) => {
    setLoading(true);
    setSubmitError("");
    try {
      const { user, outcome } = await loginWithGoogle(credential);
      const home = HOME_BY_ROLE[user.role] || "/access-denied";
      if (GOOGLE_NOTICE[outcome]) {
        setGoogleDone({ outcome, home });
      } else {
        navigate(home, { replace: true });
      }
    } catch (err) {
      // 401 (token Google sai/hết hạn): nút Google vẫn còn đó để bấm lại.
      setSubmitError(err.message || "Google sign-in failed");
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    if (validate()) signInAs(form.username.trim(), form.password);
  };

  if (googleDone) {
    const notice = GOOGLE_NOTICE[googleDone.outcome];
    return (
      <div className="google-notice" role="status">
        <strong>{notice.title}</strong>
        <p>{notice.text}</p>
        <Button type="button" onClick={() => navigate(googleDone.home, { replace: true })}>
          Continue
        </Button>
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <TextField id="username" name="username" label="Username"
        value={form.username} onChange={handleChange}
        error={errors.username} autoComplete="username" />
      <TextField id="password" name="password" type="password" label="Password"
        value={form.password} onChange={handleChange}
        error={errors.password} autoComplete="current-password" />

      {submitError && <p className="field__error form-error" role="alert">{submitError}</p>}

      <Button type="submit" disabled={loading}>
        {loading ? "Signing in..." : "Enter club"}
      </Button>

      {/* Chỉ hiện khi đã cấu hình REACT_APP_GOOGLE_CLIENT_ID: GoogleLogin cần
          GoogleOAuthProvider bọc ngoài (index.js), mà provider chỉ có khi có ID. */}
      {GOOGLE_CLIENT_ID && (
        <>
          <div className="login-divider" aria-hidden="true"><span>or</span></div>
          <div className="google-login">
            <GoogleLogin
              onSuccess={handleGoogle}
              onError={() => setSubmitError("Google sign-in failed")}
              text="signin_with"
              shape="pill"
            />
          </div>
          <p className="login-google-hint">
            Registered with Google? Use the Google button, or Forgot password.
          </p>
        </>
      )}
    </form>
  );
}
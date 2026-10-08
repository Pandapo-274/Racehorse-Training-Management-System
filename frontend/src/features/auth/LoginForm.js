// src/features/auth/LoginForm.js
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import TextField from "../../components/ui/TextField";
import Button from "../../components/ui/Button";
import { login, HOME_BY_ROLE } from "./authService";

export default function LoginForm() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ username: "", password: "" });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState("");
  const [loading, setLoading] = useState(false);

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

  const handleSubmit = (e) => {
    e.preventDefault();
    if (validate()) signInAs(form.username.trim(), form.password);
  };

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
        {loading ? "Signing in..." : "Enter academy"}
      </Button>

      
    </form>
  );
}
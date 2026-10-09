// src/features/profile/ProfilePage.js
// UC4 - Profile. Màn này mở được với mọi vai trò, vì ai cũng có hồ sơ của mình.
//
// Backend chỉ nhận userId từ token, không bao giờ nhận từ client, nên không có
// đường dẫn kiểu /profile/:id - một người chỉ sửa được hồ sơ của chính mình.
// Giao diện phản ánh đúng điều đó: không có ô nào chọn người khác.
//
// Dùng lại HorseShell làm khung. File đó mang tên "Horse" nhưng thực chất đã
// thành khung chung của ứng dụng; đổi tên thành AppShell sẽ đúng hơn, nhưng để
// lần khác - đổi bây giờ là đụng vào ba màn UC7/UC8 mà người khác vừa sắp lại.
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { handleAuthError, updateStoredUser } from "../auth/authService";
import HorseShell, { Card, StateBlock, StatusChip } from "../horse/HorseShell";
import {
  getProfile, updateProfile, uploadAvatar, checkAvatar,
  toRoleLabel, toAccountStatus, formatJoined, toInitials,
} from "./profileApi";
import "./profile.css";
import "../password/password.css"; // cho .pw-link-btn trên nút Change password

// Ba luật dưới đây là bản sao của UpdateProfileRequest. Kiểm ở trình duyệt chỉ
// để báo sớm; máy chủ vẫn kiểm lại và nó mới là chốt.
//
//   fullName  @NotBlank  @Size(max = 100)
//   email     @NotBlank  @Email  @Size(max = 100)
//   phone     @Pattern("^(\+?[0-9]{9,15})?$")   <- rỗng là hợp lệ
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const PHONE_RE = /^(\+?[0-9]{9,15})?$/;

function validate(form) {
  const e = {};

  if (!form.fullName.trim()) e.fullName = "Full name is required";
  else if (form.fullName.trim().length > 100) e.fullName = "100 characters at most";

  const email = form.email.trim();
  if (!email) e.email = "Email is required";
  else if (!EMAIL_RE.test(email)) e.email = "This does not look like an email address";
  else if (email.length > 100) e.email = "100 characters at most";

  // Khoảng trắng bị cắt trước khi so khớp, vì mẫu của backend không chấp nhận
  // khoảng trắng và người dùng hay dán số có kèm dấu cách.
  if (!PHONE_RE.test(form.phone.trim())) {
    e.phone = "Use 9 to 15 digits, optionally starting with +, or leave it empty";
  }

  return e;
}

function Field({ id, label, error, hint, children }) {
  return (
    <div className="pf-field">
      <label htmlFor={id}>{label}</label>
      {children}
      {error ? <p className="pf-field__error">{error}</p>
        : hint ? <p className="pf-field__hint">{hint}</p> : null}
    </div>
  );
}

function Avatar({ url, fullName, size = "lg" }) {
  // Khi chưa có ảnh thì hiện chữ viết tắt, không hiện ô xám trống: ô trống
  // trông như ảnh đang tải hỏng, còn chữ viết tắt thì rõ ràng là "chưa đặt".
  return url ? (
    <img className={`pf-avatar pf-avatar--${size}`} src={url} alt={`${fullName} avatar`} />
  ) : (
    <div className={`pf-avatar pf-avatar--${size} pf-avatar--empty`} aria-hidden="true">
      {toInitials(fullName)}
    </div>
  );
}

export default function ProfilePage() {
  const navigate = useNavigate();
  const fileInput = useRef(null);

  const [profile, setProfile] = useState(null);
  const [form, setForm] = useState({ fullName: "", email: "", phone: "" });
  const [errors, setErrors] = useState({});
  const [loadError, setLoadError] = useState("");
  const [saveError, setSaveError] = useState("");
  const [avatarError, setAvatarError] = useState("");
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [saved, setSaved] = useState(false);

  // Nhận hồ sơ từ máy chủ và đổ vào biểu mẫu. Gọi cả lúc mở trang lẫn sau mỗi
  // lần lưu, vì máy chủ có chuẩn hoá dữ liệu (email bị hạ về chữ thường), nên
  // thứ hiện trên màn phải là thứ máy chủ đã ghi, không phải thứ người dùng gõ.
  const adopt = useCallback((p) => {
    setProfile(p);
    setForm({ fullName: p.fullName || "", email: p.email || "", phone: p.phone || "" });
  }, []);

  const load = useCallback(
    (signal) => {
      setLoadError("");
      getProfile(signal)
        .then(adopt)
        .catch((err) => {
          if (err.name === "AbortError") return;
          if (handleAuthError(err, navigate)) return;
          setLoadError(err.message);
        });
    },
    [adopt, navigate]
  );

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    return () => ctrl.abort();
  }, [load]);

  const change = (e) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
    setSaved(false);
    if (errors[name]) setErrors((prev) => ({ ...prev, [name]: undefined }));
  };

  const submit = async (e) => {
    e.preventDefault();

    const found = validate(form);
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    setSaving(true);
    setSaveError("");
    setSaved(false);

    try {
      const updated = await updateProfile(form);
      adopt(updated);

      // Thanh bên lấy tên từ user trong localStorage, không gọi lại API. Không
      // đồng bộ ở đây thì vừa đổi tên xong, sidebar vẫn hiện tên cũ cho tới
      // lần đăng nhập sau - trông như việc lưu đã thất bại.
      updateStoredUser({
        fullName: updated.fullName,
        email: updated.email,
        avatarUrl: updated.avatarUrl,
      });

      setSaved(true);
    } catch (err) {
      if (handleAuthError(err, navigate)) return;

      // 409 trùng email trả về errors.email; 400 trả về lỗi từng trường.
      const fieldErrors = err.fieldErrors || {};
      if (Object.keys(fieldErrors).length > 0) setErrors(fieldErrors);
      else setSaveError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const pickAvatar = async (e) => {
    const file = e.target.files?.[0];
    // Xoá giá trị ngay, để chọn lại đúng file vừa rồi vẫn kích hoạt onChange.
    e.target.value = "";
    if (!file) return;

    setAvatarError("");

    const problem = await checkAvatar(file);
    if (problem) {
      setAvatarError(problem);
      return;
    }

    setUploading(true);
    try {
      const updated = await uploadAvatar(file);
      adopt(updated);
      updateStoredUser({ avatarUrl: updated.avatarUrl });
    } catch (err) {
      if (handleAuthError(err, navigate)) return;
      setAvatarError(err.fieldErrors?.file || err.message);
    } finally {
      setUploading(false);
    }
  };

  const dirty =
    profile &&
    (form.fullName !== (profile.fullName || "") ||
      form.email !== (profile.email || "") ||
      form.phone !== (profile.phone || ""));

  if (loadError) {
    return (
      <HorseShell title="My profile">
        <StateBlock kind="error" title={loadError} onRetry={() => load()}>
          Check that the backend is running on port 8080 and that you are still signed in.
        </StateBlock>
      </HorseShell>
    );
  }

  if (!profile) {
    return (
      <HorseShell title="My profile">
        <StateBlock title="Loading your profile…" />
      </HorseShell>
    );
  }

  const account = toAccountStatus(profile.status);

  return (
    <HorseShell
      title="My profile"
      subtitle="Your own account. Nobody else's details are reachable from here."
      actions={
        /* UC5. Đặt ở đây chứ không nhét một ô mật khẩu vào biểu mẫu dưới:
           đổi mật khẩu kết thúc mọi phiên đăng nhập, nên nó không thể nằm
           chung với ba ô lưu xong là ở lại trang. */
        <Link className="hz-btn hz-btn--ghost pw-link-btn" to="/change-password">
          Change password
        </Link>
      }
    >
      <div className="pf-grid">
        <Card title="Photo">
          <div className="pf-photo">
            <Avatar url={profile.avatarUrl} fullName={profile.fullName} />

            <div className="pf-photo__side">
              <button
                type="button"
                className="hz-btn"
                disabled={uploading}
                onClick={() => fileInput.current?.click()}
              >
                {uploading ? "Uploading…" : profile.avatarUrl ? "Replace photo" : "Upload a photo"}
              </button>

              {/* Giới hạn viết ra thành chữ, không giấu trong thông báo lỗi -
                  biết trước thì đỡ phải thử rồi mới biết sai. */}
              <p className="pf-photo__rules">JPEG, PNG or WebP · up to 2 MB</p>

              <input
                ref={fileInput}
                type="file"
                className="pf-file"
                accept="image/jpeg,image/png,image/webp"
                onChange={pickAvatar}
              />
            </div>
          </div>

          {avatarError && (
            <p className="pf-error" role="alert">{avatarError}</p>
          )}
        </Card>

        <Card title="Account">
          <div className="pf-row">
            <span>Username</span>
            <div className="hz-mono">{profile.username}</div>
          </div>

          <div className="pf-row">
            <span>Role</span>
            <div>{toRoleLabel(profile.role)}</div>
          </div>

          <div className="pf-row">
            <span>Status</span>
            <div>
              <StatusChip tone={account.tone} label={account.label} title={account.hint} />
            </div>
          </div>

          <div className="pf-row">
            <span>Member since</span>
            <div>{formatJoined(profile.createdAt)}</div>
          </div>

          {/* Nói thẳng vì sao ba thứ trên không sửa được. Không có dòng này thì
              người dùng sẽ đi tìm nút sửa và nghĩ giao diện bị thiếu. */}
          <p className="pf-note">
            Username, role and account status are not editable here. Changing a role or
            suspending an account is the academy manager's job, not your own.
          </p>
        </Card>
      </div>

      <form onSubmit={submit} noValidate>
        <Card title="Your details" hint="These are the only three things you can change">
          <div className="pf-form-grid">
            <Field id="fullName" label="Full name" error={errors.fullName}>
              <input
                id="fullName" name="fullName" value={form.fullName} onChange={change}
                maxLength={100} autoComplete="name" aria-invalid={!!errors.fullName}
              />
            </Field>

            <Field
              id="email"
              label="Email"
              error={errors.email}
              hint="Stored in lower case, and no two accounts may share one"
            >
              <input
                id="email" name="email" type="email" value={form.email} onChange={change}
                maxLength={100} autoComplete="email" aria-invalid={!!errors.email}
              />
            </Field>

            <Field
              id="phone"
              label="Phone"
              error={errors.phone}
              hint="Optional. 9 to 15 digits, may start with +"
            >
              <input
                id="phone" name="phone" type="tel" value={form.phone} onChange={change}
                maxLength={16} autoComplete="tel" aria-invalid={!!errors.phone}
              />
            </Field>
          </div>

          {saveError && <p className="pf-error" role="alert">{saveError}</p>}

          <div className="pf-actions">
            <button type="submit" className="hz-btn" disabled={saving || !dirty}>
              {saving ? "Saving…" : "Save changes"}
            </button>

            <button
              type="button"
              className="hz-btn hz-btn--ghost"
              disabled={saving || !dirty}
              onClick={() => {
                adopt(profile);
                setErrors({});
                setSaveError("");
                setSaved(false);
              }}
            >
              Discard
            </button>

            {/* Xác nhận hiện cạnh nút, không phải một hộp thoại: việc đã xong,
                không cần ai bấm tiếp để đóng. Biến mất ngay khi gõ tiếp. */}
            {saved && !dirty && (
              <span className="pf-saved" role="status">
                <StatusChip tone="good" label="Saved" />
              </span>
            )}
          </div>
        </Card>
      </form>
    </HorseShell>
  );
}

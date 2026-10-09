// src/features/profile/ProfileLink.js
// Hai lối vào màn hồ sơ, dùng chung cho cả năm dashboard và HorseShell:
// vòng tròn ở góc phải trên, và khối tên ở đáy thanh bên.
//
// Gom vào một file thay vì chép mười dòng giống nhau vào năm chỗ. Mỗi
// dashboard vẫn giữ lớp CSS riêng của nó (manager-profile, vet-avatar, ...)
// qua prop className, nên kích thước và vị trí không đổi - chỉ hành vi và
// nguồn dữ liệu là chung.
//
// Mỗi thành phần tự gọi useNavigate và getUser, nên nơi dùng không phải
// truyền gì xuống. Nhờ vậy gắn vào một dashboard chỉ là thay một thẻ div.
import { useNavigate } from "react-router-dom";
import { getUser } from "../auth/authService";
import "./profile-link.css";

const ROLE_LABEL = {
  CLUB_MANAGER: "Club Manager",
  HEAD_TRAINER: "Head Trainer",
  VETERINARIAN: "Veterinarian",
  GROOM: "Groom",
  HORSE_OWNER: "Horse Owner",
};

function initials(fullName) {
  if (!fullName) return "??";
  return fullName.trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
}

/** Ảnh đại diện nếu UC4 đã tải lên, không thì chữ viết tắt. */
function Face({ user, className }) {
  const name = user?.fullName || "";
  return user?.avatarUrl ? (
    <img className={className} src={user.avatarUrl} alt="" />
  ) : (
    <div className={className}>{initials(name)}</div>
  );
}

/**
 * Vòng tròn ở góc phải trên thanh tiêu đề.
 *
 * Dùng thẻ button chứ không phải div có onClick: bàn phím Tab tới được, Enter
 * và Space kích hoạt được, và trình đọc màn hình đọc ra là nút. Một div gắn
 * onClick thì không có thứ nào trong số đó.
 */
export function ProfileCircle({ className = "" }) {
  const navigate = useNavigate();
  const user = getUser();
  const name = user?.fullName || "your account";

  return (
    <button
      type="button"
      className={`profile-link profile-link--circle ${className}`}
      onClick={() => navigate("/profile")}
      title={`${name} — open your profile`}
      aria-label="Open your profile"
    >
      {user?.avatarUrl ? (
        <img src={user.avatarUrl} alt="" />
      ) : (
        <span>{initials(user?.fullName)}</span>
      )}
    </button>
  );
}

/**
 * Khối tên ở đáy thanh bên.
 *
 * `role` để ghi đè nhãn vai trò khi màn hình muốn chữ riêng; bỏ trống thì lấy
 * theo vai trò thật của người đang đăng nhập. Dashboard của bác sĩ và của
 * người chăm ngựa trước đây ghi cứng tên một người cụ thể, nên ai đăng nhập
 * cũng thấy tên người đó.
 */
export function ProfileCard({ className = "", avatarClassName = "", role }) {
  const navigate = useNavigate();
  const user = getUser();

  const fullName = user?.fullName || "Signed out";
  const roleLabel = role || ROLE_LABEL[user?.role] || "—";

  return (
    <button
      type="button"
      className={`profile-link profile-link--card ${className}`}
      onClick={() => navigate("/profile")}
      title="Open your profile"
    >
      <Face user={user} className={avatarClassName} />

      <div className="profile-link__who">
        <strong>{fullName}</strong>
        <span>{roleLabel}</span>
      </div>
    </button>
  );
}

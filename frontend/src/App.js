import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import LoginPage from "./features/auth/LoginPage";
import AccessDeniedPage from "./features/access/AccessDeniedPage";
import ManagerDashboard from "./features/manager/ManagerDashboard"
import TrainerDashboard from "./features/trainer/TrainerDashboard"
import TrainingPlanPage from "./features/trainer/TrainingPlanPage";
import SchedulePage from "./features/trainer/SchedulePage";
import VetDashboard from "./features/vet/VetDashboard";
import VetExaminationPage from "./features/vet/VetExaminationPage";
import VetLockIssuedPage from "./features/vet/VetLockIssuedPage";
import VetRecoveryPage from "./features/vet/VetRecoveryPage";
import GroomDashboard from "./features/groom/GroomDashboard";
import HorseOwnerDashboard from "./features/horse-owner/HorseOwnerDashboard";
import LandingPage from "./features/landingpage/LandingPage";
import RegisterPage from "./features/auth/RegisterPage";
import { getUser } from "./features/auth/authService";
import HorseListPage from "./features/horse/HorseListPage";
import HorseDetailPage from "./features/horse/HorseDetailPage";
import HorseFormPage from "./features/horse/HorseFormPage";
import HorseRequestPage from "./features/horse/HorseRequestPage";
import HorseReviewPage from "./features/horse/HorseReviewPage";
import ProfilePage from "./features/profile/ProfilePage";
import ChangePasswordPage from "./features/password/ChangePasswordPage";
import ForgotPasswordPage from "./features/password/ForgotPasswordPage";
import ResetPasswordPage from "./features/password/ResetPasswordPage";
import LiveSessionMonitorPage from "./features/trainer/LiveSessionMonitorPage";
import SessionEvaluationPage from "./features/trainer/SessionEvaluationPage"

/* UC4 - mọi vai trò đều có hồ sơ của mình, nên liệt kê cả năm thay vì thêm một
   loại guard thứ hai. Backend lấy userId từ token nên không có /profile/:id:
   một người chỉ đọc và sửa được hồ sơ của chính họ. */
const EVERY_ROLE = ["CLUB_MANAGER", "HEAD_TRAINER", "VETERINARIAN", "GROOM", "HORSE_OWNER"];
function RoleRoute({ roles, children }) {
  const user = getUser();
  if (!user) return <Navigate to="/login" replace />;
  if (!roles.includes(user.role)) return <Navigate to="/access-denied" replace />;
  return children;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Landing Page */}
        <Route path="/" element={<LandingPage />} />
        {/* Authentication */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/access-denied" element={<AccessDeniedPage />} />

        {/* UC5 - công khai: người đang quên mật khẩu thì chưa đăng nhập được,
            bọc RoleRoute vào là đá họ về đúng trang họ không vào nổi.
            Tên /reset-password do backend quyết định, nó sinh liên kết
            {app.frontend-base-url}/reset-password?token=... */}
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />

        {/* UC4 và UC5 - cần đăng nhập, mọi vai trò đều có hồ sơ của mình.
            Bốn dòng này từng bị mất trong một lần gộp nhánh: phần import vẫn
            còn nên không ai thấy lỗi, nhưng /profile rơi vào catch-all ở cuối
            và bị đá về /login. */}
        <Route path="/profile" element={<RoleRoute roles={EVERY_ROLE}><ProfilePage /></RoleRoute>} />
        <Route path="/change-password" element={<RoleRoute roles={EVERY_ROLE}><ChangePasswordPage /></RoleRoute>} />

        {/* Club Manager */}
        <Route path="/manager" element={<ManagerDashboard/>} />
        {/* Head Trainer */}
        <Route path="/trainer" element={<TrainerDashboard />} />
        <Route path="/trainer/training-plans" element={<RoleRoute roles={["HEAD_TRAINER"]}><TrainingPlanPage /></RoleRoute>} />
        <Route path="/trainer/schedule" element={<RoleRoute roles={["HEAD_TRAINER"]}><SchedulePage /></RoleRoute>} />
        <Route path="/trainer/live-session" element={<RoleRoute roles={["HEAD_TRAINER"]}><LiveSessionMonitorPage /></RoleRoute>} />
        <Route path="/trainer/session-review" element={<RoleRoute roles={["HEAD_TRAINER"]}><SessionEvaluationPage /></RoleRoute>} />
        {/* Veterinarian */}
        <Route path="/veterinarian" element={<VetDashboard />} />
        {/* Groom */}
        <Route path="/groom" element={<GroomDashboard/>} />
        {/* Horse Owner */}
        <Route path="/horse-owner" element={<HorseOwnerDashboard/>} />

        {/* UC7 + UC8 - shared horse feature, entered from each role dashboard */}
        <Route path="/manager/horses" element={<RoleRoute roles={["CLUB_MANAGER"]}><HorseListPage /></RoleRoute>} />
        {/* Manager chỉ xem ngựa và duyệt yêu cầu đăng ký, không tạo / sửa hồ sơ ngựa. */}
        <Route path="/manager/horse-requests" element={<RoleRoute roles={["CLUB_MANAGER"]}><HorseReviewPage /></RoleRoute>} />
        <Route path="/manager/horses/:id" element={<RoleRoute roles={["CLUB_MANAGER"]}><HorseDetailPage /></RoleRoute>} />

        <Route path="/trainer/horses" element={<RoleRoute roles={["HEAD_TRAINER"]}><HorseListPage /></RoleRoute>} />
        <Route path="/trainer/horses/:id" element={<RoleRoute roles={["HEAD_TRAINER"]}><HorseDetailPage /></RoleRoute>} />

        {/* Hai mục trên sidebar của Head Trainer. Thiếu hai route này thì navigate()
            rơi vào path="*" ở cuối và bị đá về /login. */}
        <Route path="/trainer/training-plans" element={<RoleRoute roles={["HEAD_TRAINER"]}><TrainingPlanPage /></RoleRoute>} />
        <Route path="/trainer/schedule" element={<RoleRoute roles={["HEAD_TRAINER"]}><SchedulePage /></RoleRoute>} />

        <Route path="/veterinarian/horses" element={<RoleRoute roles={["VETERINARIAN"]}><HorseListPage /></RoleRoute>} />
        <Route path="/veterinarian/horses/:id" element={<RoleRoute roles={["VETERINARIAN"]}><HorseDetailPage /></RoleRoute>} />

        {/* Flow 3 - bác sĩ thú y: khám (UC14/15/16), kết quả khoá tập, hồi phục (UC18).
            Không có :horseId thì hiện trang chọn ngựa. */}
        <Route path="/veterinarian/examination" element={<RoleRoute roles={["VETERINARIAN"]}><VetExaminationPage /></RoleRoute>} />
        <Route path="/veterinarian/examination/:horseId" element={<RoleRoute roles={["VETERINARIAN"]}><VetExaminationPage /></RoleRoute>} />
        <Route path="/veterinarian/examination/:horseId/lock-issued" element={<RoleRoute roles={["VETERINARIAN"]}><VetLockIssuedPage /></RoleRoute>} />
        <Route path="/veterinarian/recovery" element={<RoleRoute roles={["VETERINARIAN"]}><VetRecoveryPage /></RoleRoute>} />
        <Route path="/veterinarian/recovery/:horseId" element={<RoleRoute roles={["VETERINARIAN"]}><VetRecoveryPage /></RoleRoute>} />

        <Route path="/groom/horses" element={<RoleRoute roles={["GROOM"]}><HorseListPage /></RoleRoute>} />
        <Route path="/groom/horses/:id" element={<RoleRoute roles={["GROOM"]}><HorseDetailPage /></RoleRoute>} />

        <Route path="/horse-owner/horses" element={<RoleRoute roles={["HORSE_OWNER"]}><HorseListPage /></RoleRoute>} />
        {/* Chủ ngựa xin học viện đăng ký ngựa. Không phải /horse-owner/horses/new:
            đó sẽ là biểu mẫu tạo ngựa, mà POST /api/horses từ chối chủ ngựa. */}
        <Route path="/horse-owner/request" element={<RoleRoute roles={["HORSE_OWNER"]}><HorseRequestPage /></RoleRoute>} />
        <Route path="/horse-owner/horses/:id" element={<RoleRoute roles={["HORSE_OWNER"]}><HorseDetailPage /></RoleRoute>} />
        {/* Chủ ngựa sửa thông tin cơ bản của ngựa mình (mã đăng ký và chuồng bị khoá). */}
        <Route path="/horse-owner/horses/:id/edit" element={<RoleRoute roles={["HORSE_OWNER"]}><HorseFormPage /></RoleRoute>} />

        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
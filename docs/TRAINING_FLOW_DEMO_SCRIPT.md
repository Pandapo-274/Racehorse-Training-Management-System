# KỊCH BẢN BÁO CÁO & TRÌNH DIỄN PHÂN HỆ HUẤN LUYỆN (TRAINING FLOW)
**Dự án**: Racehorse Training Management System (RTMS)  
**Phân hệ**: Quản lý Huấn luyện Ngựa đua (Training Plan, Scheduling, Live Telemetry & Evaluation)  
**Tài liệu dành cho**: Buổi bảo vệ đồ án trước Hội đồng đánh giá (Thesis Defense Presentation)

---

## 📋 THÔNG TIN BỘ DỮ LIỆU DEMO (`create_training_demo_data.sql`)

Dữ liệu demo được thiết kế theo 3 trục thời gian bao phủ 100% các trạng thái và nghiệp vụ của hệ thống:

| Mã ngựa | Tên ngựa | Trục thời gian | Trạng thái & Nghiệp vụ bao phủ |
| :--- | :--- | :--- | :--- |
| **`DEMO-TR-01`** | **[DEMO] Thunder Bolt** | **Quá khứ & Hiện tại** | • Giáo án quá khứ `COMPLETED` + Buổi chạy thử (Trial Run) có video.<br>• Buổi tập quá hạn 48h tự động lưu trữ (`ARCHIVED_AUTO`).<br>• Buổi tập dừng sớm khẩn cấp (`STOPPED_EARLY`) kèm cảnh báo vượt trần tim (`THRESHOLD_ALERT`) và báo cáo sự cố (`INCIDENT_REPORT`).<br>• Buổi tập hôm qua đã hoàn thành đang chờ chấm điểm (`PENDING` trong hạn 48h).<br>• Buổi tập thời gian thực đang chạy ngay lúc demo (`RUNNING`).<br>• Lịch tập hôm nay & tương lai (`SCHEDULED`). |
| **`DEMO-TR-02`** | **[DEMO] Silver Arrow** | **Tương lai & Phê duyệt** | • Khởi tạo giáo án mới 2 giai đoạn (Build & Peak).<br>• Gán Groom phụ trách trực thuộc HLV.<br>• Giáo án ở trạng thái chờ Chủ ngựa duyệt (`PENDING_APPROVAL`). |
| **`DEMO-TR-03`** | **[DEMO] Golden Wind** | **Nghiệp vụ nâng cao** | • Giáo án đang hoạt động (`ACTIVE`) nhưng HLV gửi yêu cầu điều chỉnh trần nhịp tim (`PENDING UPDATE`).<br>• Chủ ngựa thẩm định và phê duyệt sửa đổi giáo án đang chạy. |

---

## 🎭 DANH SÁCH TÀI KHOẢN VẬN HÀNH DEMO

1. **Head Trainer**: `thang.nd` (Nguyễn Đức Thắng) — Chịu trách nhiệm lập kế hoạch, lên lịch, giám sát live và chấm điểm.
2. **Horse Owner**: `tuan.ta` (Trần Anh Tuấn) — Chủ sở hữu ngựa, người chi trả và phê duyệt giáo án.
3. **Groom**: `binh.lv` (Lê Văn Bình) — Nhân viên chăm sóc & nài tập phụ trách trực tiếp trên sân.
4. **Club Manager**: `minh.dh` (Đỗ Hoàng Minh) — Quản lý cấp phát ngựa và nhân sự.

---

# PHẦN 1: KỊCH BẢN TRÌNH DIỄN HAPPY CASE (LUỒNG CHUẨN)

### CẢNH 1: HEAD TRAINER KHỞI TẠO GIÁO ÁN HUẤN LUYỆN
* **Tài khoản**: `HEAD_TRAINER` (Nguyễn Đức Thắng)
* **URL**: `/trainer/training-plans`

#### 🎬 Thao tác thực hiện:
1. Đăng nhập tài khoản Head Trainer, chọn mục **Training Plans** trên thanh điều hướng.
2. Quan sát bảng tổng quan:
   - Các cột hiển thị ngăn nắp: **Plan**, **Horse**, **Assigned Groom**, **Period** (`white-space: nowrap`), **Phases** (chip xám bo góc), **Status**, **Actions**.
3. Bấm nút **`+ Create new plan`** tại góc phải header để chuyển sang form lập kế hoạch.
4. Nhập thông tin cho ngựa `[DEMO] Silver Arrow (DEMO-TR-02)`:
   - **Plan name**: `[DEMO] Giáo án Tốc độ Mùa giải Mới - Silver Arrow`
   - **Horse**: Chọn `[DEMO] Silver Arrow · DEMO-TR-02 (ELIGIBLE)`
   - **Assigned Groom**: Mở menu chọn Groom. Hệ thống hiển thị rõ: `Lê Văn Bình (0/2 active plans)` -> Chọn Groom Bình.
   - **Start date**: Ngày mai.
   - **End date**: 45 ngày sau.
   - **Phases**:
     - *Phase 1*: `Khởi động & Thể lực`, Cự ly: `1,800m`, Sân: `DIRT`, Số buổi/tuần: `3`, Trần tim: `175 bpm`, Vận tốc: `15.0 m/s`.
     - *Phase 2*: `Chạy bứt tốc`, Cự ly: `1,400m`, Sân: `TURF`, Số buổi/tuần: `4`, Trần tim: `188 bpm`, Vận tốc: `17.2 m/s`.
5. Bấm nút **`Save plan`** -> Kế hoạch được tạo với trạng thái `DRAFT`.
6. Tại bảng danh sách, bấm **`Send to owner`** -> Kế hoạch chuyển sang `PENDING_APPROVAL`.

#### 🎤 Lời thoại thuyết minh:
> *"Kính thưa Hội đồng, em xin bắt đầu ở vai trò Huấn luyện viên trưởng. Để chuẩn bị cho mùa giải, HLV cần lập một lộ trình phân kỳ bài bản.  
> Tại trang Training Plans, hệ thống ràng buộc chặt chẽ theo Quy tắc V40: **HLV bắt buộc phải chọn một Groom trực thuộc và Groom đó hiện không được tiếp nhận quá 2 giáo án đồng thời**.  
> Em hoàn tất cấu hình 2 giai đoạn gồm cự ly, mặt sân, trần nhịp tim và bấm Send to owner. Hệ thống ghi nhận trạng thái `PENDING_APPROVAL` và tức thời gửi thông báo đến tài khoản Chủ ngựa."*

---

### CẢNH 2: CHỦ NGỰA THẨM ĐỊNH KẾ HOẠCH & DUYỆT ĐIỀU CHỈNH
* **Tài khoản**: `HORSE_OWNER` (Trần Anh Tuấn)
* **URL**: `/horses` và `/horse-owner/training`

#### 🎬 Thao tác thực hiện:
1. Đăng xuất Trainer, đăng nhập tài khoản **Horse Owner**.
2. Mở tab **Horses** (My Horses):
   - Thấy nút **`+ Request a horse registration`** được bố trí đồng bộ ngay trong thanh công cụ của bảng **Registry**, giúp trải nghiệm tìm kiếm và gửi yêu cầu liền mạch.
3. Chuyển sang menu **Training**:
   - Thấy giáo án `[DEMO] Giáo án Tốc độ Mùa giải Mới - Silver Arrow` đang ở trạng thái `PENDING_APPROVAL`.
   - Xem chi tiết từng Phase và trần nhịp tim an toàn.
   - Bấm nút xanh **`Approve`** -> Giáo án được thông qua (`APPROVED`).
4. Kéo xuống mục **Pending Plan Modifications**:
   - Thấy kế hoạch của ngựa **`[DEMO] Golden Wind (DEMO-TR-03)`** có yêu cầu điều chỉnh: *"Tăng ngưỡng nhịp tim lên 190 bpm cho giai đoạn Peak"*.
   - Chủ ngựa đọc ghi chú chuyên môn và bấm **`Approve Update`**.

#### 🎤 Lời thoại thuyết minh:
> *"Bây giờ em chuyển sang góc nhìn của Chủ ngựa. Về mặt giao diện, nút gửi yêu cầu cấp mã ngựa mới đã được tinh chỉnh đặt gọn gàng trong bảng Registry.  
> Trong tab Training, Chủ ngựa có quyền kiểm soát toàn diện lộ trình luyện tập trên tài sản của mình. Em bấm Duyệt cho giáo án của Silver Arrow. Đồng thời, với chú ngựa Golden Wind đang có giáo án chạy thực tế nhưng HLV muốn tăng cường độ nhịp tim ở giai đoạn Peak, hệ thống lưu vết dưới dạng `pending_update` để Chủ ngựa thẩm định trước khi áp dụng."*

---

### CẢNH 3: HLV KÍCH HOẠT GIÁO ÁN & XẾP LỊCH BUỔI TẬP (SCHEDULE)
* **Tài khoản**: `HEAD_TRAINER`
* **URL**: `/trainer/training-plans` và `/trainer/schedule`

#### 🎬 Thao tác thực hiện:
1. Đăng nhập lại **Head Trainer**, vào **Training Plans**.
2. Giáo án của Silver Arrow đã sẵn sàng nút **`Activate`**. Bấm kích hoạt -> Giáo án chuyển sang `Active`.
3. Chuyển sang menu **Schedule**:
   - Bấm **`+ New session`**:
   - Chọn Plan: `[DEMO] Giáo án Tốc độ Mùa giải Mới - Silver Arrow`, Phase 1.
   - Hệ thống tự động điền Groom phụ trách là `Lê Văn Bình`.
   - Chọn giờ tập: Ngày mai lúc `07:00 AM`, thời lượng `60 phút`, cự ly `1,800m`.
   - Bấm **`Create session`** -> Buổi tập được lên lịch thành công (`SCHEDULED`).

#### 🎤 Lời thoại thuyết minh:
> *"Sau khi chủ ngựa đồng ý, HLV tiến hành kích hoạt giáo án. Tại thời điểm này, backend tự động kiểm tra xem ngựa có đang bị lệnh khoá y tế `TRAINING_LOCK` không. Nếu hoàn toàn bình thường, plan chính thức chuyển sang `ACTIVE`.  
> HLV tiếp tục lên lịch buổi tập đầu tiên trên trang Schedule. Hệ thống tự động kiểm tra: Groom không bị trùng lịch, thời lượng là bội số của 5 phút, và thời điểm kết thúc không vượt sang ngày hôm sau."*

---

### CẢNH 4: THỰC THI SÂN TẬP & GIÁM SÁT TRỰC TIẾP (LIVE TELEMETRY)
* **Tài khoản**: `HEAD_TRAINER` & `GROOM`
* **URL**: `/trainer/live-sessions` và `/groom`

#### 🎬 Thao tác thực hiện:
1. Trên màn hình của Trainer, vào **Live Session Monitor**:
   - Thấy buổi tập của chú ngựa **`[DEMO] Thunder Bolt (DEMO-TR-01)`** đang ở trạng thái `RUNNING` trực tiếp.
   - Thẻ theo dõi hiển thị sống động: Tốc độ hiện tại `15.8 m/s`, nhịp tim đo được `168 bpm`, trần nhịp tim an toàn `185 bpm`.
2. Mở giao diện Groom (hoặc giải thích luồng Groom hoàn tất bài chạy):
   - Groom ghi nhận kết quả cuối bài: Cự ly đạt `1,600m`, nhịp tim đỉnh `168 bpm`, tốc độ đỉnh `15.8 m/s`.
   - Bấm **`Finish session`** -> Buổi tập kết thúc (`COMPLETED`), tự động chuyển sang hàng đợi chờ HLV đánh giá (`review_status = 'PENDING'`).

#### 🎤 Lời thoại thuyết minh:
> *"Tại thời điểm demo thực tế, chú ngựa Thunder Bolt đang thực hiện bài chạy ngoài sân tập. HLV trưởng quan sát từ xa qua màn hình Live Monitor với các chỉ số đo lường Telemetry liên tục.  
> Khi buổi chạy kết thúc đạt cự ly, Groom bấm Finish. Buổi tập hoàn tất và đồng hồ đếm ngược thời hạn đánh giá 48 giờ bắt đầu được kích hoạt."*

---

### CẢNH 5: THẨM ĐỊNH BUỔI TẬP CHUYÊN MÔN (SESSION EVALUATION)
* **Tài khoản**: `HEAD_TRAINER`
* **URL**: `/trainer/session-evaluation`

#### 🎬 Thao tác thực hiện:
1. Vào trang **Session Review**:
   - Thanh tiêu đề đã được dọn sạch các nút bấm thừa, tập trung vào công việc đánh giá.
   - Thấy buổi tập hôm qua của **Thunder Bolt** đang ở trạng thái `PENDING` (trong hạn 48 giờ).
   - Chọn buổi tập này, bên phải hiển thị toàn bộ đồ thị nhịp tim và tốc độ do Groom ghi lại.
   - Nhập đánh giá: Chọn `EXCELLENT`, nhận xét: *"Ngựa giữ nhịp tim ổn định ở 168 bpm, guồng chân thanh thoát, sẵn sàng cho cự ly dài hơn."*
   - Bấm **`Submit review`** -> Trạng thái đổi thành `REVIEWED`, thông báo báo cáo hoàn chỉnh được đẩy cho Chủ ngựa.
2. Mở dropdown bộ lọc, chọn **`Archive (1)`**:
   - Chỉ vào buổi tập cách đây 5 ngày của Thunder Bolt có badge tím `Archived · Auto-reported (>48h)`:
   - Giải thích: Buổi tập này trước đây bị quá hạn 48h nên hệ thống đã tự động kích hoạt tính năng **Auto Preliminary Report**, tổng hợp số liệu của Groom gửi cho Chủ ngựa để bảo đảm tính minh bạch, sau đó đưa vào Archive.

#### 🎤 Lời thoại thuyết minh:
> *"Bước cuối cùng là thẩm định chuyên môn. HLV có thời hạn 48 giờ để chấm điểm bài tập. Khi HLV gửi đánh giá `EXCELLENT`, báo cáo chính thức sẽ phát hành cho Chủ ngựa.  
> Nếu vì lý do bất khả kháng mà HLV quên chấm quá 48h, hệ thống kích hoạt cơ chế dự phòng an toàn: tự sinh Báo cáo sơ bộ gửi Chủ ngựa và lưu vào Archive (như Thầy Cô thấy trong mục Archive 1 buổi tập). HLV vẫn có thể thẩm định lại để cập nhật đè lên báo cáo sơ bộ bất cứ lúc nào."*

---

# PHẦN 2: DANH SÁCH & TRÌNH DIỄN UNHAPPY CASE (CƠ CHẾ PHÒNG VỆ)

Hệ thống thiết lập ma trận kiểm soát đa tầng ngăn chặn mọi rủi ro về chuyên môn và an toàn sinh mạng ngựa đua:

| Mã Case | Tình huống vi phạm | Cơ chế phát hiện & Phản hồi của Hệ thống | Trạng thái trong Demo Data |
| :--- | :--- | :--- | :--- |
| **U1** | **Groom quá tải** (> 2 Active Plans) | Dropdown vô hiệu hoá Groom `(2/2 active plans — Full)`. Backend quăng `ApiException(400)`. | Kiểm tra tại `TrainingPlanService.java`. |
| **U2** | **Chuyển Groom đang kẹt plan** | Manager cố chuyển Groom có plan active sang Trainer khác -> Báo lỗi đỏ `409 Conflict`. | Đã chặn tại `StaffService.java`. |
| **U3** | **Cự ly nhập quá trần** (> 5,000m) | Nhập `6,000m` -> Form báo lỗi tức thì: *"Planned distance must be maximum 5,000 meters"*. | Frontend chặn form + Backend `ConstraintViolation`. |
| **U4** | **Buổi tập xuyên đêm** (> 12h hoặc qua ngày) | Đặt giờ tập 23:00 kéo dài 90 phút -> Báo lỗi: *"Session duration cannot cross into the next calendar day"*. | Đã kiểm tra tính toán thời gian `sameDay`. |
| **U5** | **Trùng lịch 1 ngựa / 1 ngày** | Cố tạo thêm 1 buổi tập hôm nay cho Thunder Bolt -> Báo lỗi: *"1 horse can only have 1 session per day"*. | Backend kiểm tra ngày của `HORSE_ID`. |
| **U6** | **Dừng khẩn cấp trên sân (Incident)** | Ngựa nhịp tim vọt lên 196 bpm (trần 185) -> Groom bấm **Stop Early** -> Kích hoạt `THRESHOLD_ALERT` & `INCIDENT_REPORT`. | Có sẵn dữ liệu buổi tập dừng sớm của Thunder Bolt. |
| **U7** | **Khoá y tế ngăn tập nặng** | Ngựa bị chấn thương có `TRAINING_LOCK` -> Hệ thống chặn kích hoạt giáo án hoặc cấm xếp bài tập nặng. | Kiểm tra qua `vitalsRepository.findActiveLock`. |
| **U8** | **Bỏ quên buổi tập không Start** | Buổi tập `SCHEDULED` đã quá giờ kết thúc dự kiến -> Job nền tự quét đổi sang `CANCELLED`. | Dữ liệu demo có 1 buổi `CANCELLED` ngày hôm kia. |

---

### KỊCH BẢN THỰC THI 2 UNHAPPY CASE TRỰC TIẾP:

#### 🚨 DEMO UNHAPPY CASE 1: Sự cố dừng khẩn cấp do vượt trần nhịp tim (Emergency Stop & Threshold Alert)
* **Thao tác**:
  1. Tại trang **Session Review**, chọn filter `All sessions`.
  2. Mở buổi tập có trạng thái đỏ **`STOPPED_EARLY`** của `[DEMO] Thunder Bolt`.
* **Thuyết minh**:
  > *"Thưa Hội đồng, đây là minh chứng cho cơ chế bảo vệ an toàn sinh mạng cho ngựa đua. Trong buổi tập cách đây 3 ngày, ngựa Thunder Bolt chạy đến mét thứ 1,200 thì nhịp tim vọt lên **196 bpm**, vượt xa ngưỡng an toàn 185 bpm.  
  > Groom lập tức bấm **Stop Early**. Hệ thống ghi nhận trạng thái dừng sớm, tự động kích hoạt một bản ghi cảnh báo nguy hiểm trong bảng `THRESHOLD_ALERT` (mức độ HIGH) và tạo ngay một phiếu khám khẩn cấp `INCIDENT_REPORT` gửi đến Bác sĩ thú y để đưa ngựa vào diện theo dõi đặc biệt."*

#### 🚨 DEMO UNHAPPY CASE 2: Chặn vi phạm xếp lịch tập vượt quá giới hạn (Duration & Distance Validation)
* **Thao tác**:
  1. Vào menu **Schedule**, bấm **`+ New session`**.
  2. Tại ô **Planned distance**, nhập thử: `999999` -> Hệ thống báo lỗi đỏ: *"Planned distance must be between 1 and 5,000 meters"*.
  3. Tại ô **Planned duration**, nhập: `123` (số lẻ không chia hết cho 5) -> Hệ thống yêu cầu: *"Planned duration must be an integer divisible by 5 (e.g. 15, 20, 25... up to 720 minutes)"*.
  4. Đặt giờ tập `11:00 PM` với thời lượng `90 phút` (kết thúc lúc 00:30 sáng hôm sau) -> Hệ thống hiển thị cảnh báo đỏ và khoá nút Submit: *"Session duration cannot cross into the next calendar day"*.
* **Thuyết minh**:
  > *"Tại giao diện xếp lịch tập, hệ thống áp dụng cơ chế phòng vệ đa lớp. Không chỉ chặn lỗi tràn số và ép cự ly thực tế dưới 5,000m, hệ thống còn chuẩn hoá thời lượng thành các block 5 phút và kiểm soát chặt chẽ khung thời gian sinh học: tuyệt đối không cho phép xếp lịch kéo dài xuyên đêm sang ngày hôm sau để đảm bảo thời gian nghỉ ngơi của ngựa."*

---

## 🎯 LỜI KẾT LUẬN TRƯỚC HỘI ĐỒNG
> **🎤 [Lời kết]:**  
> *"Kính thưa Hội đồng, thông qua kịch bản demo vừa rồi, nhóm chúng em đã chứng minh phân hệ Training Flow đáp ứng trọn vẹn:
> 1. **Tính khép kín và liên kết chặt chẽ**: Từ HLV lập kế hoạch, Chủ ngựa phê duyệt chi phí/mục tiêu, Groom thực thi ngoài sân tập đến Bác sĩ thú y giám sát an toàn.
> 2. **Tính chịu lỗi và tự động hoá cao**: Tự động huỷ buổi tập quá hạn, tự động xuất báo cáo sơ bộ khi trễ hạn 48h, và chặn đứng mọi xung đột về nhân sự và thiết bị.
> Chúng em xin chân thành cảm ơn Quý Thầy Cô và rất mong nhận được những câu hỏi, ý kiến đóng góp từ Hội đồng!"*

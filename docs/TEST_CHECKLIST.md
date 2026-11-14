# TRACEN - Checklist kiểm thử 5 flow

Checklist để cả nhóm tự bấm kiểm thử hệ thống trên giao diện, như một người dùng thật của câu lạc bộ. Mọi màn hình, nút bấm và luật dưới đây được đối chiếu với code ngày 13/11/2026; tên nút giữ nguyên tiếng Anh như trên giao diện.

## 0. Cách dùng

### Bốn lớp kiểm thử, làm theo thứ tự

| Lớp | Hỏi điều gì | Ở đâu trong tài liệu |
|---|---|---|
| **1. Workflow** | Câu chuyện có chạy hết từ vai trò này sang vai trò khác không? Người tiếp theo có nhận **chuông thông báo** và thấy **đúng dữ liệu** không? | Bảng "Happy case" của mỗi flow |
| **2. Luật nghiệp vụ** | Cố tình làm sai thì hệ thống có chặn, kèm thông báo dễ hiểu không? | Bảng "Luật nghiệp vụ" của mỗi flow |
| **3. Theo vai trò** | Mỗi vai trò làm được đúng phần của mình, không làm được phần của người khác | Mục 6 |
| **4. Trơn tru** | Màn hình nhỏ, zoom, tốc độ, F5, Back, thông báo lỗi | Mục 7 |

Đi Lớp 1 theo thứ tự **Flow 1 → 2 → 3 → 4 → 5**, vì flow sau dùng dữ liệu flow trước. Gặp bước nào thì thử luôn các luật Lớp 2 của bước đó.

### Chuẩn bị

1. Chạy backend (`backend`: `.\mvnw spring-boot:run`), **chờ VS Code build xong rồi mới chạy** (nếu không sẽ gặp lỗi "No qualifying bean"), và frontend (`frontend`: `npm start`).
2. Mở **5 trình duyệt hoặc profile riêng**, mỗi cái một vai trò. Hai tab cùng trình duyệt dùng chung phiên đăng nhập.
3. Tài khoản demo, mật khẩu chung `123456`:

| Vai trò | Tài khoản | Ghi chú |
|---|---|---|
| Club Manager | `hoa.tm` | |
| Head Trainer | `thang.nd` | đang quản lý 7 ngựa và 3 Groom |
| Head Trainer 2 | `lam.tb` | dùng để kiểm tra "chỉ thấy ngựa của mình" |
| Groom | `binh.lv`, `nam.tq`, `hieu.lm` | đều thuộc đội `thang.nd` |
| Veterinarian | `ha.pt` | |
| Horse Owner | `anh.dq` | sở hữu Symboli Rudolf, Deep Impact |

4. Với Flow 4, chạy `backend/dev/seed_flow4_demo.sql` để có dữ liệu quá khứ, hiện tại, tương lai.
5. Ghi kết quả vào cột **KQ**: `P` (đạt), `F` (lỗi, ghi lại theo mẫu ở mục 9), `-` (chưa thử).

### Ký hiệu
- 🔔 = kiểm tra chuông thông báo của người nhận.
- Các số giới hạn (14 ngày, 48 giờ, 800 m…) lấy từ code và `application.properties`; nhóm đổi cấu hình thì đổi theo.

---

## 1. Flow 1 - Hồ sơ & lý lịch ngựa (bắt buộc)

**Mục tiêu theo đặc tả:** ngựa vào câu lạc bộ qua yêu cầu đăng ký của chủ ngựa, Club Manager duyệt; chủ ngựa xem hồ sơ, phả hệ; ngựa rời câu lạc bộ qua yêu cầu rút ngựa.

**Màn hình:** Owner `/horse-owner/request`, `/horse-owner/horses/:id` · Manager `/manager/horse-requests`, `/manager/withdrawals`, `/manager/horses/:id` · mọi vai trò `…/horses`.

### 1A. Happy case

| # | Ai | Thao tác | Kết quả mong đợi | KQ |
|---|---|---|---|---|
| 1.1 | Owner | **Register a horse**: nhập Name, Sex, ngày sinh (ngựa 3 tuổi), Breed, chọn "What is this record for?" = ngựa ở câu lạc bộ, chọn Sire/Dam (nếu có) → **Send request** | Yêu cầu hiện trong "Your requests" trạng thái chờ. 🔔 Manager: "New horse registration request" | |
| 1.2 | Manager | **Horse registrations** → thẻ yêu cầu: nhập Registration code, Stall, chọn **Assign Head Trainer** = `thang.nd` → **Approve** | Yêu cầu chuyển "Already handled". 🔔 Owner: "Horse registration approved". 🔔 HLV: "New horse assigned to your management" | |
| 1.3 | Owner | **My horses** | Ngựa mới có trong danh sách, mở được hồ sơ, tab **Pedigree** hiện cha mẹ đã khai | |
| 1.4 | HLV `thang.nd` | **Horses** | Thấy ngựa mới. Đăng nhập `lam.tb`: **không** thấy | |
| 1.5 | Vet, Groom | **Horses** | Thấy ngựa mới, hồ sơ chỉ đọc | |
| 1.6 | Manager | Hồ sơ ngựa → **Club records & Trainer allocation**: đổi Stall → **Save** | Lưu được; chuồng mới hiện ở mọi vai trò | |
| 1.7 | Owner | Hồ sơ ngựa → thẻ **Leave the club** → nhập lý do → **Request withdrawal** | 🔔 Manager có yêu cầu rút ngựa | |
| 1.8 | Manager | **Withdrawals** → **Approve** | Ngựa thành "withdrawn": buổi tập SCHEDULED bị huỷ (🔔 Groom), giáo án chưa xong bị kết thúc, trả chuồng. Ngựa biến khỏi lịch Groom, bảng sức khoẻ, giáo án | |
| 1.9 | Manager | Hồ sơ ngựa đã rút → **Re-admit to the club** (gán chuồng) → **Re-admit** | Ngựa trở lại câu lạc bộ | |
| 1.10 | Manager | Hồ sơ ngựa → **Transfer to another owner** → chọn chủ mới → **Transfer** | Ngựa sang tên chủ mới; chủ cũ không còn thấy | |

### 1B. Luật nghiệp vụ

| # | Luật | Cách thử | Mong đợi | KQ |
|---|---|---|---|---|
| 1.11 | Không gửi trùng tên khi yêu cầu cũ còn chờ | Gửi lại yêu cầu cùng tên ngựa | Chặn: "already have a pending request for a horse with this name" | |
| 1.12 | Ngựa câu lạc bộ phải có ngày sinh, không quá 25 tuổi | Ngày sinh 30 năm trước | Chặn, gợi ý đăng ký dạng **pedigree record** | |
| 1.13 | Cha phải là ngựa đực, mẹ là ngựa cái, sinh trước con ít nhất 2 năm | Chọn Sire là ngựa cái, hoặc cha mẹ sinh sau con | Báo lỗi ở ô Sire/Dam | |
| 1.14 | Cha và mẹ không trùng nhau | Chọn cùng một con cho Sire và Dam | "Sire and dam cannot be the same horse" | |
| 1.15 | Owner chỉ chọn cha mẹ là hồ sơ pedigree hoặc ngựa của chính mình | Thử chọn ngựa của chủ khác | "Choose a pedigree record or one of your own horses" | |
| 1.16 | Duyệt phải có mã đăng ký; ngựa câu lạc bộ phải có chuồng | **Approve** khi để trống Registration code / Stall | Báo lỗi đúng ô | |
| 1.17 | Mã đăng ký và chuồng không trùng | Duyệt với chuồng đang có ngựa khác | "Another horse is already in this stall" | |
| 1.18 | Từ chối phải ghi lý do | **Reject** khi để trống "Note to the owner" | "A reason is required when rejecting" | |
| 1.19 | Yêu cầu còn chờ thì chủ ngựa tự huỷ, không cần Manager | Owner → **Cancel request** | Trạng thái CANCELLED; Manager không phải xử lý | |
| 1.20 | HLV có giới hạn số ngựa (mặc định 10) | Gán ngựa cho HLV đã đủ | "has reached their maximum limit of … horses" | |
| 1.21 | Không rút ngựa đang có buổi tập RUNNING | Manager **Approve** rút khi ngựa đang tập | "is in a training session right now" | |
| 1.22 | Không gửi hai yêu cầu rút cho cùng một ngựa | Gửi lần hai | "already waiting for the club" | |
| 1.23 | Giới tính, ngày sinh, cha mẹ bị khoá khi ngựa đã có con hoặc đã có buổi tập | Owner sửa giới tính một ngựa đã từng tập | Báo khoá | |
| 1.24 | Chỉ Manager sửa cha mẹ, phải ghi lý do | Manager → **Correct parents** để trống lý do | "Say why the parents are corrected" | |
| 1.25 | Hai Manager cùng duyệt một yêu cầu | Mở hai trình duyệt, bấm **Approve** gần cùng lúc | Người sau nhận "already been handled by someone else" | |

---

## 2. Flow 2 - Lập & thực hiện giáo án huấn luyện (bắt buộc)

**Mục tiêu:** HLV lập giáo án theo giai đoạn (cự ly, khối lượng, mặt sân), chủ ngựa đồng ý, HLV xếp buổi tập cho Groom; Groom thực hiện và nhập chỉ số; HLV xem cảnh báo, xử lý, rồi đánh giá; chủ ngựa xem nhận xét và video chạy thử.

**Màn hình:** HLV `/trainer/training-plans`, `/trainer/schedule`, `/trainer/live-session`, `/trainer/session-review` · Groom `/groom` (Today) · Owner `/horse-owner/training` · Vet `/veterinarian/vet-calls`.

**Trạng thái giáo án:** Draft → (**Send to owner**) Waiting for owner → (Owner **Approve plan**) Approved by owner → (**Activate**) Active ⇄ (**Pause** / **Resume**) Paused → (**Complete**) Completed. Owner **Reject** đưa về Draft.

### 2A. Happy case

| # | Ai | Thao tác | Kết quả mong đợi | KQ |
|---|---|---|---|---|
| 2.1 | HLV | **+ Create new plan**: chọn ngựa, Groom, tên, ngày bắt đầu hôm nay, 2 giai đoạn (cự ly, workload, mặt sân TURF/DIRT/SYNTHETIC, số buổi/tuần, trần nhịp tim, nghỉ giữa buổi, vận tốc mục tiêu) → **Save plan** | Giáo án trạng thái **Draft** | |
| 2.2 | HLV | **Send to owner** | Trạng thái "Waiting for owner". 🔔 Owner | |
| 2.3 | Owner | **Training** → xem giáo án → **Approve plan** | Trạng thái "Approved by owner". 🔔 HLV | |
| 2.4 | HLV | **Activate** | Trạng thái **Active** | |
| 2.5 | HLV | **Schedule** → **Create session**: giai đoạn, Groom, giờ (hôm nay, vài phút nữa), thời lượng 30 phút, cự ly 1000 m | Buổi tập SCHEDULED. 🔔 Groom | |
| 2.6 | Groom | **Today** → buổi tập → **Start session** (đúng giờ) | Buổi chuyển RUNNING; hiện trên **Live session** của HLV | |
| 2.7 | Groom | Nhập nhịp tim, vận tốc bình thường → **Send reading** | Live session của HLV cập nhật sau khoảng 4 giây, không có cảnh báo | |
| 2.8 | Groom | Nhập nhịp tim vượt trần giai đoạn ≥ 10% → **Send reading** | Live hiện cảnh báo **HIGH**. 🔔 HLV (chỉ HIGH mới đổ chuông; LOW, MEDIUM chỉ hiện trên Live) | |
| 2.9 | HLV | Live → cảnh báo → chọn **REDUCE_INTENSITY** (hoặc CONTINUE) | Cảnh báo đã xử lý | |
| 2.10 | HLV | Cảnh báo mới → **Call vet**: lý do, dấu hiệu, mức khẩn, có dừng ngay không | 🔔 mọi Vet; khung "Vet calls" trên Live | |
| 2.11 | Vet | **Vet calls** → **Answer →** → chọn CONTINUE_LIGHT → **Send advice** | 🔔 HLV và Groom nhận câu trả lời | |
| 2.12 | Groom | **Finish session** khi hết giờ | Buổi COMPLETED, vào hàng chờ đánh giá | |
| 2.13 | HLV | **Session review** → buổi vừa xong: cự ly thực, thời lượng, nhịp tim TB, đỉnh, hồi phục, nhận xét; đánh dấu chạy thử + link video → **Submit official review & notify owner** | 🔔 Owner | |
| 2.14 | Owner | **Training** | Thấy lịch tập, nhận xét của HLV, video chạy thử | |
| 2.15 | HLV | **Pause** → **Resume** → **Complete** giáo án | Trạng thái đổi đúng; Complete xong không sửa được nữa | |

### 2B. Luật nghiệp vụ

| # | Luật | Cách thử | Mong đợi | KQ |
|---|---|---|---|---|
| 2.16 | Một ngựa chỉ có một giáo án đang chạy | Tạo giáo án thứ hai cho cùng ngựa | "already has an ongoing training plan" | |
| 2.17 | Ngày bắt đầu không ở quá khứ; ngày kết thúc ≥ ngày bắt đầu | Chọn ngày hôm qua | Báo lỗi ô ngày | |
| 2.18 | Giai đoạn nằm trong khoảng giáo án và không chồng nhau | Giai đoạn 2 bắt đầu trước khi giai đoạn 1 kết thúc | "Phase 2 starts before Phase 1 ends" | |
| 2.19 | Giới hạn giai đoạn: 1-14 buổi/tuần, trần nhịp tim 100-260, nghỉ 15-720 phút, vận tốc 1-25 m/s, tối đa 12 giai đoạn | Nhập ngoài khoảng | Báo lỗi đúng ô | |
| 2.20 | Groom phải thuộc đội HLV, tối đa 2 giáo án đang chạy | Chọn Groom đã có 2 giáo án Active | "already assigned to 2 active training plans" | |
| 2.21 | Ngựa phải ELIGIBLE hoặc MONITOR, không bị khoá FULL, không phải pedigree, chưa rút | Lập giáo án cho ngựa INJURED | "Horse status is INJURED…" | |
| 2.22 | Owner từ chối phải ghi lý do | **Reject** để trống ghi chú | "Tell the head trainer why you are rejecting" | |
| 2.23 | Chỉ xoá được giáo án chưa từng Activate | Xoá giáo án Active | Không có nút / bị chặn | |
| 2.24 | Không xếp buổi tập khi giáo án chưa Active | Create session cho giáo án Draft | "Training plan is not active" | |
| 2.25 | Buổi tập không ở quá khứ, nằm trong giai đoạn, cự ly ≤ 5000 m, thời lượng 15-720 phút chia hết cho 5 | Nhập 17 phút, hoặc cự ly 6000 | Báo lỗi đúng ô | |
| 2.26 | Một ngựa một buổi mỗi ngày; đủ khoảng nghỉ của giai đoạn | Xếp buổi thứ hai cùng ngày cho cùng ngựa | "A horse cannot have 2 sessions on the same day" | |
| 2.27 | Không vượt số buổi/tuần của giai đoạn | Xếp quá số buổi/tuần | "This phase allows N sessions per week…" | |
| 2.28 | Groom không bị xếp hai buổi trùng giờ | Xếp Groom vào hai ngựa cùng giờ | "is already handling … at …" | |
| 2.29 | Chỉ Start trong đúng ngày, từ đúng giờ, trong khung giờ của buổi | Groom Start trước giờ | "can only be started at or after the scheduled time" | |
| 2.30 | Một ngựa / một Groom không có hai buổi RUNNING | Start buổi thứ hai khi buổi đầu chưa xong | Chặn | |
| 2.31 | Khoá HEAVY_WORK_ONLY chỉ cho buổi ≤ 800 m; FULL chặn hết | Xếp buổi 1200 m cho ngựa khoá HEAVY_WORK_ONLY | "limited to light work … at most 800 m" | |
| 2.32 | Ngựa có báo cáo sự cố chờ Vet thì không tập | Start buổi của ngựa có sự cố mở | "Horse has an open incident report…" | |
| 2.33 | Kết thúc sớm phải có ít nhất một lần nhập cả nhịp tim lẫn vận tốc, và chọn lý do | **Confirm & End session early** khi chưa nhập chỉ số | Chặn | |
| 2.34 | Vận tốc > 18 m/s sinh cảnh báo VELOCITY | Nhập 19 m/s | Cảnh báo speed | |
| 2.35 | Mức cảnh báo: vượt < 5% LOW, 5-10% MEDIUM, ≥ 10% HIGH | Nhập lần lượt ba mức | Đúng mức, chỉ HIGH đổ chuông | |
| 2.36 | STOP_SESSION dừng buổi và mở báo cáo sự cố | Live → **STOP_SESSION** | Buổi STOPPED_EARLY; Vet có ca mới (sang Flow 3) | |
| 2.37 | "Call vet" chỉ qua form, mỗi cảnh báo một lần | Gọi lần hai cùng cảnh báo | Chặn | |
| 2.38 | Vet trả lời một lần; TREAT_ON_SITE bắt buộc ghi thuốc | Chọn TREAT_ON_SITE để trống thuốc | "Say which medication to give now" | |
| 2.39 | Đánh giá: đỉnh ≥ TB, hồi phục ≤ đỉnh, link video http(s), buổi dừng sớm không đánh dấu Completed | Nhập đỉnh < TB | Báo lỗi ô | |
| 2.40 | Quá 48 giờ chưa đánh giá: tự sinh báo cáo sơ bộ (ARCHIVED_AUTO), báo Owner | Xem buổi đã kết thúc hơn 48 giờ | Nhãn "Auto preliminary report"; HLV vẫn **Update evaluation** được | |
| 2.41 | HLV chỉ thao tác ngựa được giao | `lam.tb` mở giáo án / buổi tập ngựa của `thang.nd` | "not assigned to you" | |
| 2.42 | Đêm 00:15: giáo án quá hạn tự Completed, buổi SCHEDULED lỡ ngày tự huỷ | Xem sáng hôm sau | Đúng trạng thái | |

---

## 3. Flow 3 - Y tế & xử lý chấn thương (bắt buộc)

**Mục tiêu:** Vet xem sơ đồ sức khoẻ đàn ngựa, nhận ca, khám, chẩn đoán, kê đơn, đánh dấu chấn thương, khoá tập; theo dõi hồi phục; nhắc tiêm phòng, tẩy giun, móng định kỳ.

**Màn hình:** Vet `/veterinarian` (Herd health), `/veterinarian/examination/:horseId`, `/veterinarian/recovery`, `/veterinarian/prescriptions`, `/veterinarian/preventive-care`, `/veterinarian/vet-calls` · Groom `/groom/incidents` · HLV, Owner xem tình trạng trên hồ sơ ngựa.

**Đầu vào của Vet** có 3 nguồn: Groom báo sự cố tại chuồng (Flow 4), buổi tập bị dừng (Flow 2: STOP_SESSION, dừng do chấn thương), và trả lời Call vet với EMERGENCY_STOP.

### 3A. Happy case

| # | Ai | Thao tác | Kết quả mong đợi | KQ |
|---|---|---|---|---|
| 3.1 | Groom | **Incident reports**: chọn ngựa, triệu chứng "Khập khiễng chân trước trái", mức HIGH, ảnh → gửi | 🔔 Vet, HLV, Owner. Ngựa vào hàng chờ khám | |
| 3.2 | Vet | **Herd health**: sơ đồ chuồng có 4 màu trạng thái (Eligible / Monitor / Injured / Quarantine); ca mới trong hàng chờ | Thấy đúng ngựa, đúng màu | |
| 3.3 | Vet | **Herd health** → hàng chờ → **Take case** → mở **Examination** | Ca chuyển RECEIVED (Vet khác thấy đã có người nhận) | |
| 3.4 | Vet | Ghi hồ sơ khám: loại EXAM, chẩn đoán, xác nhận chấn thương, số ngày nghỉ dự kiến, tình trạng INJURED, cân nặng | Ngựa thành INJURED ở mọi vai trò | |
| 3.5 | Vet | Đánh dấu chấn thương trên **mô hình ngựa** (bấm vào vị trí) | Dấu hiện đúng vị trí, xem lại được ở tab bệnh sử | |
| 3.6 | Vet | Kê đơn: chọn thuốc từ kho, liều, ngày bắt đầu/kết thúc | Kho thuốc bị trừ; đơn hiện ở **Prescriptions** | |
| 3.7 | Vet | Khoá tập **FULL** (gắn với chấn thương) | Trang "lock issued". HLV không xếp được buổi tập (sang 2.31) | |
| 3.8 | HLV | Thử **Create session** cho ngựa đó | Bị chặn: "under a full training lock" | |
| 3.9 | Vet | Hết đợt điều trị → **Prescriptions** → **End treatment today** | Đơn kết thúc | |
| 3.10 | Vet | **Recovery** → đánh giá theo giai đoạn (**Approve & Advance to Light Work** → **Complete Assessment & Advance to Rating**) → **Confirm recovery** → **Release training lock** | Ngựa về ELIGIBLE, khoá được gỡ, HLV xếp buổi tập lại được | |
| 3.11 | Owner | Hồ sơ ngựa → sức khoẻ, bệnh sử | Thấy tình trạng, cân nặng, lịch sử khám | |
| 3.12 | Vet | **Preventive care**: ghi VACCINATION có ngày đến hạn kế tiếp → **Send reminders now** | Bảng nhắc cập nhật; 🔔 nhắc Vet và Owner | |
| 3.13 | Vet | Ca đang giữ → **Handover** → **Confirm Handover** sang Vet khác (Vet khác cũng có thể **Take over**) | 🔔 Vet nhận, HLV, Groom | |
| 3.14 | Vet | **Examination** → chuyển viện ngoài (**Confirm External Referral**) | Ngựa hiện ở khu "đang ở viện ngoài" trên dashboard; **Complete Discharge & Return** khi về | |

### 3B. Luật nghiệp vụ

| # | Luật | Cách thử | Mong đợi | KQ |
|---|---|---|---|---|
| 3.15 | Hồ sơ pedigree và ngựa đã rút không khám, không khoá | Mở khám hồ sơ pedigree | Chặn | |
| 3.16 | Chấn thương xác nhận thì tình trạng phải INJURED; số ngày nghỉ chỉ cho chấn thương | Xác nhận chấn thương nhưng để ELIGIBLE | Báo lỗi | |
| 3.17 | Ngựa INJURED còn chấn thương mở không chuyển MONITOR | Đổi sang MONITOR khi chưa hồi phục | "still has injuries under treatment…" | |
| 3.18 | Hồ sơ VACCINATION/DEWORMING/FARRIER phải có ngày đến hạn kế tiếp | Để trống | Báo lỗi | |
| 3.19 | Mỗi ca chỉ một Vet khám | Hai Vet cùng khám một báo cáo | "Another veterinarian has already examined this report" | |
| 3.20 | Không khoá trùng | Khoá ngựa đang bị khoá | "already locked by…" | |
| 3.21 | Không gỡ khoá khi còn điều trị hoặc chấn thương chưa lành | **Release this lock** khi đơn còn chạy | "still under active medical treatment" | |
| 3.22 | Hồi phục sớm hơn số ngày nghỉ dự kiến phải ghi lý do | Confirm recovery sớm, để trống ghi chú | "expected to rest until … explain why" | |
| 3.23 | Hồi phục phải xử lý mọi báo cáo sự cố còn chờ | Confirm khi còn báo cáo mở chưa tick | "still has incident reports waiting…" | |
| 3.24 | Đơn thuốc: phải có tên thuốc, thuốc phải là MEDICINE, không vượt tồn kho, ngày kết thúc ≥ ngày bắt đầu | Kê nhiều hơn tồn kho | "exceeds available stock" | |
| 3.25 | Không kết thúc đơn chưa tới ngày bắt đầu | **End treatment today** với đơn bắt đầu ngày mai | "only starts on …" | |
| 3.26 | Dấu chấn thương: không trùng vị trí đang mở; không xoá khi đang có khoá dựa trên nó | Xoá dấu đang được khoá tham chiếu | "Release the lock before removing the marker" | |
| 3.27 | HLV chỉ bỏ qua (false alarm) báo cáo từ buổi tập bị dừng, và chỉ khi Vet chưa nhận | Live → **False alarm** sau khi Vet đã Take over | Chặn | |
| 3.28 | Tái phát trong lúc phục hồi | Recovery → **Confirm Relapse & Revert to Treatment** | Ngựa quay lại điều trị | |

---

## 4. Flow 4 - Chăm sóc chuồng trại & dinh dưỡng (tuỳ chọn)

Chi tiết đầy đủ (dữ liệu seed, 12 luật, 50 ca test API) nằm ở [FLOW4_DEMO_SCRIPT.md](FLOW4_DEMO_SCRIPT.md). Tóm tắt để bấm:

| # | Ai | Thao tác | Kết quả mong đợi | KQ |
|---|---|---|---|---|
| 4.1 | Vet / HLV | **Feeding** / **Stable care**: khẩu phần theo bữa, mỗi dòng chọn món FEED trong kho | Lưu được, có người duyệt | |
| 4.1b | Vet | **Rations** → sửa khẩu phần một ngựa có HLV và có việc chăm sóc hôm nay | 🔔 "Ration changed" cho HLV phụ trách, các bác sĩ khác, Groom có việc với con ngựa; bấm vào mở trang khẩu phần | |
| 4.1c | HLV | Ngựa **INJURED** có khẩu phần do bác sĩ duyệt → **Edit** → **Approve ration** | 409 "… Ask the vet to change it"; khẩu phần không đổi. Ngựa khoẻ thì lưu được và bác sĩ đã duyệt khẩu phần cũ nhận chuông | |
| 4.2 | HLV | **Stable care**: giao việc (Feeding, Cleaning, Bathing, Ice bath) cho Groom, một hoặc nhiều ngựa, tối đa 14 ngày | 🔔 Groom | |
| 4.2b | HLV | **Stable care** → bảng việc → **Mark done** một việc của Groom, rồi **Reopen** | "ticked by" tên HLV; Reopen trả về To do; việc hôm qua thành Done late; việc 2 ngày trước không có nút | |
| 4.3 | Groom | **Stable map**: bấm xong việc Feeding | Kho tự trừ khẩu phần cả ngày (xem **History** ở Supplies) | |
| 4.4 | Groom | Bấm lại để bỏ đánh dấu | Kho được trả lại đúng lượng | |
| 4.5 | Groom | **Supplies** → **Count** một món thức ăn | Sổ kho có dòng ADJUST; thuốc không có nút Count | |
| 4.5a | Groom | **Count** một món ra **nhiều hơn** sổ (vd. timothy 47.5 → 48) | Phải chọn lý do ("Taken by the system but not used"…); thiếu lý do thì bị chặn; lưu xong sổ ghi ADJUST kèm lý do, 🔔 Manager "Count higher than record". Đếm ra **ít hơn** sổ thì không cần lý do | |
| 4.5b | Groom | **Supplies** → **Report** một món thuốc (Damaged) và một món thức ăn (Running low, số lượng 30) | Thuốc: 🔔 Manager **và** bác sĩ; thức ăn: 🔔 Manager. Kho không đổi. Lý do "Something else" để trống ghi chú thì bị chặn | |
| 4.5c | Vet | Kê đơn thuốc đã hết hạn (Dexamethasone sau khi chạy seed) | Thuốc hiện "[Expired …]" mờ, không chọn được; gọi API thì 409 | |
| 4.6 | Manager | **Supplies & feed** → **Restock**, **Throw away**, **History**, **Count**; **Edit** (đơn giá giữ nguyên) | Đúng | |
| 4.6b | Vet | Menu **Medicine** | Chỉ thấy thuốc, chỉ có nút **Report**; không Count, Restock, Throw away, History. Gọi API `/api/supplies/{id}/receive` bằng token bác sĩ thì 403 | |
| 4.7 | Groom | **Incident reports** (xem 3.1) | Nối sang Flow 3 | |
| 4.8 | Luật | Kho thiếu không chặn cho ăn; quá 12 việc/ngày chỉ cảnh báo; giao trùng bị bỏ qua; không giao ngày đã qua; không đánh dấu việc ngày mai; việc bỏ lỡ hiện ở tab **Missed** | Như FLOW4_DEMO_SCRIPT mục 3 | |

---

## 5. Flow 5 - Đăng ký thi đấu & báo cáo thành tích (tuỳ chọn)

**Mục tiêu:** HLV chọn ngựa đăng ký giải; chủ ngựa đồng ý (phí đăng ký thành chi phí của ngựa); nhập kết quả, tiền thưởng; chủ ngựa xem thành tích, chi phí, doanh thu; Manager xem báo cáo tổng.

**Màn hình:** HLV `/trainer/races` · Manager `/manager/races`, `/manager/expenses`, `/manager` (Club overview) · Owner `/horse-owner/races`, dashboard **Costs & prizes**.

**Thẻ giải đua:** Entries (lọc All / Upcoming / Pending Owner / Result needed / Finished / Scratched / Declined) · Race calendar · Performance by horse.

### 5A. Happy case

| # | Ai | Thao tác | Kết quả mong đợi | KQ |
|---|---|---|---|---|
| 5.1 | HLV hoặc Manager | **Race calendar** → **Create race**: tên, ngày (trong 1 năm tới), hạn đăng ký, cự ly, địa điểm, số ngựa tối đa, phí | Giải hiện trên lịch | |
| 5.2 | HLV | Chọn giải → chọn ngựa đủ điều kiện → **Enter horse** | Mục đăng ký "Pending Owner". 🔔 Owner | |
| 5.3 | Owner | **Races** → **Approve** | Mục chuyển "Upcoming"; phí đăng ký thành một dòng chi phí của ngựa. 🔔 HLV | |
| 5.4 | HLV | Sau ngày đua → **Record result**: thứ hạng, WON/PLACED/UNPLACED, tiền thưởng | Mục "Finished". 🔔 Owner | |
| 5.5 | Owner | **Races**, **Costs & prizes** | Thấy kết quả, tiền thưởng, phí đăng ký trong chi phí | |
| 5.6 | Manager | **Races** → thẻ thống kê (Entries, Wins, Placed, Prize money) và **Performance by horse** | Số liệu khớp | |
| 5.7 | Manager | **Costs** → ghi chi phí (FEEDING, VETERINARY, TRAINING…) cho một ngựa → **Save cost** | Hiện ở Costs của Owner và Club overview | |
| 5.8 | Manager | **Club overview** | Số ngựa, giáo án đang chạy, khoá tập, chi phí quý, tiền thưởng mùa, công việc tuần, sức khoẻ đàn đúng với dữ liệu thật | |

### 5B. Luật nghiệp vụ

| # | Luật | Cách thử | Mong đợi | KQ |
|---|---|---|---|---|
| 5.9 | Ngày đua sau hôm nay, tối đa 1 năm; hạn đăng ký không ở quá khứ, không sau ngày đua; tên + ngày không trùng | Tạo giải hôm qua | Báo lỗi ô | |
| 5.10 | Ngựa đủ điều kiện: ngựa câu lạc bộ, chưa rút, 2-10 tuổi, ELIGIBLE, không khoá tập, không có sự cố chờ khám, tiêm phòng không quá hạn | Enter ngựa INJURED hoặc 12 tuổi | Ngựa bị chặn kèm lý do | |
| 5.11 | Hai giải của một ngựa cách nhau ít nhất 14 ngày | Đăng ký ngựa vào hai giải cách 7 ngày | "races must be at least 14 days apart" | |
| 5.12 | Chỉ cảnh báo (không chặn): chưa có tiêm phòng nào; chưa chạy thử trong 30 ngày | Enter ngựa chưa chạy thử | Có cảnh báo, vẫn đăng ký được | |
| 5.13 | Đã hết hạn đăng ký hoặc giải đủ số ngựa thì không đăng ký | Enter sau hạn | "Entries … closed on …" / "is full" | |
| 5.14 | Owner từ chối: ngựa không thi; HLV không nhập kết quả cho mục bị từ chối | Owner từ chối, HLV Record result | Chặn | |
| 5.15 | Owner chỉ duyệt khi ngựa vẫn đủ điều kiện | Ngựa bị khoá sau khi HLV đăng ký, Owner **Approve** | "can no longer be approved: …" | |
| 5.16 | Chỉ nhập kết quả sau ngày đua; WON = hạng 1, PLACED = 2-3, UNPLACED ≥ 4 và thưởng 0 | WON với hạng 3 | "Position 3 is PLACED, not WON" | |
| 5.17 | Sau 7 ngày kết quả bị khoá với HLV; Manager sửa phải ghi lý do | HLV **Edit result** giải đã qua 8 ngày | "The result was locked 7 days after the race" | |
| 5.18 | HLV **Scratch** / **Withdraw** chỉ trước khi có kết quả; phí đăng ký bị huỷ theo | Scratch mục đã có kết quả | Chặn | |
| 5.19 | Manager huỷ giải chưa có kết quả | **Cancel race** | Giải "cancelled"; không đăng ký thêm | |
| 5.20 | Chi phí: không trước ngày sinh, không sau ngày ngựa rút, không cho hồ sơ pedigree; **Void** phải có lý do | Ghi chi phí sau ngày ngựa rút | Báo lỗi ô ngày | |
| 5.21 | Owner chỉ thấy ngựa và chi phí của mình | `anh.dq` xem Races, Costs | Không thấy ngựa chủ khác | |

---

## 6. Lớp 3 - Kiểm tra theo vai trò

Đăng nhập từng vai trò, đi hết menu, rồi **gõ thẳng URL** của vai trò khác: phải bị đưa sang `/access-denied`.

| Vai trò | Làm được (theo đặc tả) | Thử URL bị cấm | KQ |
|---|---|---|---|
| **Head Trainer** | Dashboard tiến độ, thể lực; giáo án; lịch tập, chạy thử; Live và cảnh báo; đánh giá; đăng ký giải; giao việc chuồng | `/manager/staff`, `/veterinarian/examination` | |
| **Veterinarian** | Herd health; khám, chẩn đoán, đơn thuốc; mô hình chấn thương; khoá tập; nhắc định kỳ; trả lời Call vet; duyệt khẩu phần; xem thuốc trong kho (menu Medicine) | `/trainer/training-plans`, `/manager/audit-log` | |
| **Groom** | Sơ đồ chuồng, việc trong ngày; khẩu phần; đánh dấu xong việc; báo sự cố kèm ảnh; vật tư; thực hiện buổi tập | `/trainer/schedule`, `/manager/supplies` | |
| **Horse Owner** | Hồ sơ, phả hệ, thành tích; sức khoẻ, cân nặng; lịch tập, video, nhận xét; chi phí, tiền thưởng; gửi đăng ký, rút ngựa; duyệt giáo án, duyệt đăng ký giải | `/manager/horses`, `/trainer/races` | |
| **Club Manager** | Danh mục ngựa, nhân sự, vật tư; xem phân quyền (**Permissions**); báo cáo tổng (**Club overview**); **Audit log** | `/groom/stable` | |

Kiểm tra thêm:
- **Phạm vi dữ liệu:** Owner chỉ thấy ngựa của mình; HLV chỉ thấy ngựa được giao (`thang.nd` khác `lam.tb`); Groom chỉ thấy việc, buổi tập của mình.
- **Nhân sự** (Manager → **Staff**): một HLV tối đa 3 Groom, có giới hạn số ngựa (**Max horses**); **Suspend** một tài khoản thì người đó không đăng nhập được; **Reset password**.
- **Đăng nhập:** sai mật khẩu 5 lần liên tiếp thì bị khoá tạm (429); đổi mật khẩu thì các phiên khác bị đăng xuất.
- **Audit log:** mỗi thao tác quan trọng ở trên đều có dòng nhật ký (ai, làm gì, lúc nào, kết quả); truy cập bị chặn cũng được ghi.

---

## 7. Lớp 4 - Trơn tru, mượt mà

| # | Kiểm tra | Cách làm | Mong đợi | KQ |
|---|---|---|---|---|
| 7.1 | Màn hình laptop | Cửa sổ 1366 px | Không cột nào bị cắt, nút thao tác luôn thấy | |
| 7.2 | Zoom | Màn hình lớn zoom 125%, 150% (Ctrl +) | Như trên | |
| 7.3 | Điện thoại | DevTools, 390 px | Đọc được, menu thu gọn | |
| 7.4 | Tốc độ | Mở từng trang | Dưới khoảng 2 giây; có trạng thái "Loading…" | |
| 7.5 | Bấm hai lần | Bấm nhanh hai lần nút lưu | Chỉ tạo một bản ghi | |
| 7.6 | F5 / Back | Tải lại giữa form, bấm Back | Không lỗi trắng trang; vẫn đăng nhập | |
| 7.7 | Hết phiên | Để trang mở quá 8 giờ (hoặc đăng xuất ở tab khác) rồi thao tác | Đưa về trang đăng nhập, không lỗi khó hiểu | |
| 7.8 | Hai người cùng sửa | Hai trình duyệt cùng xử lý một mục | Người sau nhận thông báo "đã bị người khác thay đổi" | |
| 7.9 | Thông báo lỗi | Mọi ca ở Lớp 2 | Câu chữ hiểu được, gắn đúng ô; không có "Unexpected server error" | |
| 7.10 | Chuông | Mọi bước có 🔔 | Số chưa đọc tăng; bấm vào mở đúng trang; **read all** hoạt động | |
| 7.11 | Giờ hiển thị | So giờ trên trang với giờ máy | Đúng giờ Việt Nam, không lệch 7 tiếng | |

---

## 8. Chỗ hệ thống khác đặc tả (chuẩn bị câu trả lời khi bảo vệ)

| Đặc tả | Hiện trạng trong code | Ghi chú |
|---|---|---|
| Đánh dấu chấn thương trên "mô hình cơ/xương **3D**" | Mô hình ngựa **2D** (SVG), bấm để đặt dấu theo toạ độ %; nút có tiêu đề "3D musculoskeletal model" | Nói rõ là mô hình 2D, hoặc đổi chữ trên nút |
| Cảnh báo dựa trên nhịp tim, vận tốc "**realtime**" | Groom **nhập tay** từng lần đo; màn Live của HLV **tự tải lại mỗi 4 giây** | Không có thiết bị đo nối trực tiếp |
| Chủ ngựa đồng ý các điều trị lớn (V29, `PUT /api/treatments/{id}/consent`) | Backend có, **frontend không có màn hình**; form kê đơn cũng không có ô "cần chủ ngựa đồng ý" | Chưa test được trên giao diện |
| Chủ ngựa khiếu nại một khoản chi phí (V30, `PUT /api/owner/expenses/{id}/dispute`) | Backend có, **frontend không có nút** | Chưa test được trên giao diện |
| Groom "đề xuất bổ sung" vật tư | Nút **Report** trên Supplies (sắp hết / hỏng / hết hạn / khác); hệ thống cũng tự gắn "Restock suggested" khi dưới định mức | Đáp ứng; chưa có danh sách đề xuất có trạng thái |

---

## 9. Mẫu ghi lỗi

```
Mã ca:        (ví dụ 2.29)
Tài khoản:    (ví dụ binh.lv - Groom)
Màn hình:     (URL)
Các bước:     1. … 2. … 3. …
Mong đợi:     …
Thực tế:      … (chụp màn hình, thông báo lỗi nguyên văn)
Mức độ:       Cao (chặn workflow) / Trung bình (sai luật) / Thấp (giao diện, câu chữ)
```

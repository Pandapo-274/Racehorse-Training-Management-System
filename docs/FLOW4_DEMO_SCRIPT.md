# Flow 4 - Chăm sóc chuồng trại & dinh dưỡng hằng ngày: kịch bản demo và test

Theo yêu cầu của giáo viên: **Bước 1** demo happy case theo đúng workflow; **Bước 2** dùng dữ liệu hiện tại và tương lai để cho thấy các luật nghiệp vụ chặn hoặc cảnh báo. Không demo CRUD (thêm/sửa danh mục vật tư, sửa tên...). Dành thời gian cho các phần hệ thống tự làm: trừ kho khi cho ăn, sổ xuất nhập, cảnh báo thiếu hàng, việc bỏ lỡ, quá tải Groom, báo sự cố chặn tập.

Thời lượng gợi ý cho Flow 4: **khoảng 5 phút** (Bước 1 khoảng 2,5 phút, Bước 2 khoảng 2,5 phút).

## 1. Chuẩn bị

1. Chạy `backend/dev/seed_flow4_demo.sql` trong SSMS hoặc Azure Data Studio (DB `rtms`) **ngay trước buổi demo**. Script tính ngày theo hôm nay và chạy lại được nhiều lần: mỗi lần chạy trả kho về như cũ rồi tạo lại dữ liệu, nên dùng nó để reset giữa các lần tập.
2. Mở **4 trình duyệt hoặc profile khác nhau**. Phiên đăng nhập lưu trong `localStorage`, nên hai tab cùng một trình duyệt sẽ dùng chung một tài khoản.

   | Cửa sổ | Tài khoản (mật khẩu `123456`) | Trang mở sẵn |
   |---|---|---|
   | A | `thang.nd` - Head Trainer | `/trainer/stable-care` |
   | B | `binh.lv` - Groom | `/groom/stable` |
   | C | `ha.pt` - Veterinarian | `/veterinarian/feeding` |
   | D | `hoa.tm` - Club Manager | `/manager/supplies` |

3. Báo sự cố (bước 1.5) sẽ chặn tập ngựa được báo. Chọn một con **không dùng** trong demo Flow 2/3, hoặc demo Flow 4 sau cùng.

### Dữ liệu script tạo ra (D = ngày demo)

| Nhóm | Dữ liệu | Dùng để demo |
|---|---|---|
| **Quá khứ** D-6..D-1 | Mỗi ngày cho ăn + dọn chuồng 7 ngựa của `thang.nd`; sổ kho có dòng "Nhập hàng đầu tuần" (D-7) và dòng CONSUME "Feeding …" từng ngày | Lịch sử kho đi xuống theo bữa ăn thật |
| | D-3 Air Groove dọn chuồng: **Done late**; D-1 Vodka dọn chuồng: **ticked by** HLV | Trạng thái DONE_LATE, người đánh dấu hộ |
| | D-4 Orfevre tắm, D-2 TM Opera O dọn chuồng, D-1 Almond Eye dọn chuồng: **Missed** | Tab Missed, báo cáo việc bỏ lỡ |
| **Hiện tại** D | `binh.lv` có 6 việc (cho ăn Symboli Rudolf, Air Groove, Daiwa Scarlet, Vodka; dọn chuồng 2 con); `hieu.lm` có 2 việc; `nam.tq` có **11** việc | Happy case; quá tải Groom |
| | Kho **Cà rốt** chỉ còn 3 kg, khẩu phần Vodka cần 4 kg/ngày | Thiếu hàng nhưng không chặn Groom |
| | Cỏ khô timothy 48/60 kiện (dưới định mức); Dexamethasone **đã hết hạn**, Xylazine **sắp hết hạn** | "Restock suggested", bản tổng hợp 07:05 |
| **Tương lai** D+1..D+3 | Dọn chuồng cả dãy 7 con (`hieu.lm`), cho ăn ngày mai (`binh.lv`), ngâm đá D+2 | Giao trùng bị bỏ qua, Hand over, xoá việc tương lai |
| Khẩu phần | 17 dòng cho 7 ngựa, **dòng nào cũng nối với một món FEED trong kho**; Air Groove và Almond Eye do bác sĩ duyệt | Khẩu phần -> kho |

## 2. Bước 1 - Happy case theo workflow

| # | Ai | Làm gì | Cho giáo viên thấy |
|---|---|---|---|
| 1.1 | C - Vet | Mở **Feeding**, chỉ vào Air Groove: "Cám hỗn hợp 2 kg, sáng; alfalfa 0.5 kiện, tối - duyệt bởi Phạm Thu Hà" | Khẩu phần theo bữa, có người duyệt; mỗi dòng là một món trong kho, đơn vị lấy từ kho |
| 1.2 | A - HLV | **Stable care**, form giao việc: chọn Daiwa Scarlet, Groom `binh.lv`, hôm nay, loại **Cleaning**, bấm giao | Chuông của B báo "New care tasks" |
| 1.2b | C → A, B | (Sau bước 1.1, khi bác sĩ lưu khẩu phần Air Groove) | 🔔 **A** (HLV Thắng phụ trách Air Groove) và **B** (Bình, Groom có việc với Air Groove): *"Ration changed: Air Groove – Phạm Thu Hà: …"*; bấm vào mở bảng khẩu phần |
| 1.3 | B - Groom | **Stable map**: sơ đồ chuồng theo dãy, mỗi ô có ngựa, khẩu phần hôm nay và việc của mình. Bấm xong **Feeding** của Symboli Rudolf | Việc chuyển sang Done kèm giờ |
| 1.4 | D - Manager | **Supplies**, Yến mạch, **History**: dòng mới nhất là `CONSUME -3.5 kg "Feeding Symboli Rudolf"` của `binh.lv`, bên dưới là các ngày trước | Cho ăn **tự trừ kho** theo khẩu phần (cả timothy 0.5 kiện và cám 2.5 kg); sổ kho có đủ lịch sử |
| 1.5 | B - Groom | **Incidents**: báo sự cố cho một ngựa: "Bỏ ăn, bụng chướng", mức HIGH, kèm ảnh | Bác sĩ, HLV và chủ ngựa nhận chuông; ca vào hàng chờ khám (nối sang Flow 3) |
| 1.6 | A - HLV | Lên lịch hoặc bắt đầu buổi tập cho đúng ngựa đó | Bị chặn: ngựa đang chờ bác sĩ khám |

## 3. Bước 2 - Luật nghiệp vụ, dùng dữ liệu hiện tại và tương lai

Với mỗi luật: nói tên luật, thao tác, rồi chỉ vào kết quả.

| # | Luật | Ai / thao tác | Kết quả mong đợi |
|---|---|---|---|
| 2.1 | **Thiếu hàng không chặn việc cho ăn** (ngựa không thể nhịn đói chỉ vì số trên sổ) | B: bấm xong **Feeding** của Vodka (cần 4 kg cà rốt, kho còn 3) | Việc vẫn Done; cà rốt về **0**; D nhận chuông **"Stock ran out … needed 4 kg, only 3 was in stock"** |
| 2.1b | **HLV không ghi đè khẩu phần điều trị của bác sĩ khi ngựa đang ốm** | Cần một ngựa INJURED có khẩu phần do bác sĩ duyệt (bác sĩ đặt khẩu phần sau khi khám). **A**: Rations → Edit ngựa đó → Approve ration | Lỗi *"… is INJURED and its ration was set by the vet (Phạm Thu Hà). Ask the vet to change it"* |
| 2.2 | **Bỏ đánh dấu trả lại đúng lượng đã lấy** (đọc từ sổ kho, không tính lại) | B: bấm lại Feeding của Vodka để bỏ đánh dấu | Cà rốt về **3** (không phải 4); sổ kho có dòng REVERSAL |
| 2.3 | **Quá tải Groom chỉ cảnh báo, không chặn** (ngưỡng 12 việc/ngày) | A: giao Daiwa Scarlet **Cleaning + Bathing** hôm nay cho `nam.tq` (đang có 11 việc) | Tạo được, kèm cảnh báo "Warning: 13 open tasks on … (more than 12)" |
| 2.4 | **Mỗi ngựa mỗi ngày chỉ một việc mỗi loại** (dữ liệu tương lai) | A: giao hàng loạt **Cleaning** cho cả 7 ngựa từ ngày mai tới D+5, cho `hieu.lm` | "14 created, 21 already planned and left as they were" (D+1..D+3 đã có sẵn) |
| 2.5 | **Giao hàng loạt tối đa 14 ngày** | A: chọn "đến ngày" quá 14 ngày | Ô ngày không cho chọn quá (`max`); API trả 400 "Period too long" |
| 2.6 | **Chuyển việc: cả hai Groom đều được báo** | A: chọn ngày mai, Hand over việc Feeding của Air Groove từ `binh.lv` sang `nam.tq` | B nhận "Task handed over", `nam.tq` nhận "New care tasks" |
| 2.6b | **Groom quên bấm thì HLV xác nhận hộ, nhưng ghi rõ người xác nhận** | **A**: Daily tasks, ngày hôm nay → một việc "To do" → **Mark done** → OK | Status "Done hh:mm" kèm *"ticked by Nguyễn Đức Thắng"*; nếu là Feeding thì kho cũng bị trừ. **Reopen** để hoàn tác. Việc của hôm qua thì thành **Done late** |
| 2.7 | **Việc đã qua ngày là bằng chứng, không xoá được**; việc tương lai xoá được | A: chọn ngày D-2: không còn nút Delete hay Hand over. Chọn ngày D+3: Delete một việc dọn chuồng | Quá khứ chỉ xem; tương lai xoá được |
| 2.8 | **Việc không làm thành MISSED** (job 00:15 mỗi đêm báo HLV) | A: tab **Missed** | 3 việc bỏ lỡ trong 7 ngày, kèm Groom phụ trách; D-3 Air Groove hiện **Done late** khi chọn ngày D-3 |
| 2.8b | **Hàng không tự sinh ra: đếm ra nhiều hơn sổ phải giải thích** | **B**: Count cỏ timothy, nhập 48 (sổ 47.5 vì mỗi bữa trừ 0.5 kiện) | Hiện ô *"Why is there more than on record?"*; chọn *"Taken by the system but not used"* → lưu; **D** nhận chuông *"Count higher than record"*; History ghi lý do. Nhắc: hàng mới về thì dùng **Restock** để có giá tiền |
| 2.9 | **Không đổi đơn vị của món đang dùng trong khẩu phần** (đổi kg sang bao sẽ làm mọi bữa ăn lấy sai lượng) | D: sửa Yến mạch, đổi đơn vị `kg` thành `bao` | 409 "… ration line(s) measure Yến mạch in kg. Change those rations first" |
| 2.10 | **Hàng hủy không vượt quá tồn kho** | D (Manager): Throw away cà rốt 10 kg | 409 "Only 3 kg … in stock" |
| 2.11 | **Kho thấp: chỉ báo một lần khi tụt dưới định mức, rồi nhắc trong bản tổng hợp mỗi sáng** | D: thẻ "Restock suggested", lọc Restock | Timothy và cà rốt nằm trong danh sách; Dexamethasone hiện hết hạn |
| 2.12b | **Groom chủ động báo vấn đề vật tư (đề xuất bổ sung)** | **B**: **Supplies** → dòng Phenylbutazone → **Report** → "Damaged or spoiled", ghi chú → **Send report** | **D** (Manager) và **C** (bác sĩ) cùng có chuông *"Stock problem: Phenylbutazone"*; bấm vào mở trang Supplies. Kho không đổi |
| 2.12c | **Không kê đơn thuốc đã hết hạn** (nối Flow 3) | **C**: Prescriptions → chọn thuốc | Dexamethasone hiện *"[Expired 2026-…]"*, bị làm mờ, không thêm vào đơn được |
| 2.12 | **Thuốc là kho kiểm soát: Groom không được đếm** | B: **Supplies**: thức ăn và dụng cụ có nút **Count**, thuốc thì không. Count cỏ timothy, nhập 45 | Sổ kho có dòng ADJUST -3; thuốc chỉ bác sĩ hoặc Manager đếm (API trả 403 nếu Groom cố gửi) |
Các luật sau chỉ chạy được qua API (giao diện chưa có nút hoặc đã ẩn sẵn, xem mục 5). Có thể nói miệng hoặc đưa vào slide:
- Ngựa không ở chuồng (hồ sơ PEDIGREE, ngựa đã rút) không có khẩu phần, việc hay báo sự cố: 409.
- Groom không đánh dấu được việc của ngày mai (409) và việc của Groom khác (403).
- Việc của hôm qua vẫn đánh dấu được và thành DONE_LATE; quá 1 ngày thì 409 "It stays as missed".

## 4. Checklist test toàn diện (đã chạy qua API ngày 13/11/2026)

Kết quả: **P** = đúng như mong đợi, **F** = lỗi (xem mục 5).

| # | Ca test | Kết quả mong đợi | KQ |
|---|---|---|---|
| S1 | Manager thêm món FEED | 201, có dòng RECEIVE "Opening quantity" | P |
| S2 | Trùng tên trong cùng khu vực | 409 | P |
| S3 | Hạn dùng cho món không phải thuốc | 400 | P |
| S4 | Đơn giá âm | 400 | **F** (được lưu) |
| S5 | Nhập hàng với đơn vị khác đơn vị kho | Bị chặn, hoặc quy đổi | **F** qua API (cộng thẳng, sổ ghi "+2 bao"); giao diện đã bỏ ô Unit nên không còn gặp |
| S6 | Hủy hàng nhiều hơn tồn / không ghi lý do | 409 / 400 | P |
| S7 | Groom đếm thuốc / đếm thức ăn / xem sổ / nhập hàng | 403 / 200 / 403 / 403 | P |
| S8 | HLV kiểm đếm | 403 | P |
| R1 | HLV đặt khẩu phần nối kho | 200, người duyệt = HLV | P |
| R2 | Khẩu phần chọn món không phải FEED | 400 "is not a feed" | P |
| R3 | Khẩu phần rỗng / bữa sai | 400 | P |
| R5 | Số lượng 100000 | 400 | **F** (500) |
| R6 | Ngựa PEDIGREE | 409 | P |
| R7 | HLV sửa khẩu phần ngựa không được giao cho mình | 403 | **F** (lọt qua) |
| R8 | Groom / Manager sửa khẩu phần | 403 | P |
| R10 | Chủ ngựa chỉ thấy khẩu phần ngựa mình | Chỉ ngựa của mình | P |
| R12 | Đổi đơn vị / loại của món đang dùng trong khẩu phần | 409 | P |
| R14 | Hai dòng giống hệt (cùng bữa, cùng món) | Gộp hoặc chặn | **F** nhẹ (lưu cả hai, trừ kho gấp đôi) |
| T1 | Giao việc hôm nay | 201, Groom nhận chuông | P |
| T2 | Giao trùng loại cùng ngày | skipped | P |
| T3 | Giao cho ngày đã qua | 400 | P |
| T4 | Giao cho người không phải Groom | 400 | P |
| T5 | Loại việc sai | 400 | P |
| T6 | HLV giao việc trên ngựa không phải của mình | 403 | **F** (lọt qua) |
| C0 | HLV khác giao việc cho Groom thuộc đội HLV này | 403 | **F** (lọt qua) |
| T7 | Giao hàng loạt quá 14 ngày / ngày ngược | 400 | P |
| T9 | Giao hàng loạt có ngựa PEDIGREE | Bỏ qua kèm ghi chú | P |
| T10 | Groom tự giao việc | 403 | P |
| T11 | Groom chỉ thấy việc của mình | Đúng | P |
| C1 | Groom đánh dấu việc của người khác | 403 | P |
| C2 | Xong FEEDING | Trừ đúng khẩu phần cả ngày (5 kg) | P |
| C3 | Bấm xong lần 2 | 409, không trừ kho lần 2 | P |
| C4 | Bỏ đánh dấu | Trả lại đúng 5 kg | P |
| C6 | Đánh dấu việc ngày mai | 409 | P |
| C7 | Kho thiếu khi cho ăn | Không chặn, kho về 0, Manager nhận "Stock ran out" | P |
| C9/C10 | Chuyển / xoá việc đã xong | 409 | P |
| C11 | Chuyển cho chính Groom đang giữ | 400 | P |
| C12 | Chuyển việc | Cả hai Groom nhận chuông; người cũ hết quyền đánh dấu (403) | P |
| C14 | HLV đánh dấu hộ | Done, "ticked by" HLV | P |
| C15 | Báo cáo việc bỏ lỡ, ngày ngược | 400 | P |
| C17 | Đánh dấu việc quá cửa sổ trễ | 409 | P |
| L1 | Đánh dấu việc hôm qua | DONE_LATE | P |
| L2 | Bỏ đánh dấu việc hôm qua | Thành MISSED | P |
| L3 | Xoá việc hôm qua | 409 | P |
| I1-I3 | Báo sự cố: thiếu triệu chứng / mức sai / file không phải ảnh | 400 | P |
| I4 | Báo sự cố ngựa PEDIGREE | 409 | P |
| I5 | Bác sĩ gửi báo sự cố | 403 | P |
| I6 | Ảnh lớn hơn 2 MB | 413 | P |

## 5. Lỗi còn tồn tại

| # | Mức | Lỗi | Tránh khi demo | Hướng sửa |
|---|---|---|---|---|
| 1 | ~~Cao~~ **Đã sửa (upstream)** | Thư mục migration từng có hai file `V40__…` và hai file `V41__…`. Nhóm đã đổi tên, giờ là V40, V41, V42 riêng biệt | Khởi động lại backend một lần trước buổi demo để chắc Flyway chạy được | - |
| 2 | Trung bình | Head Trainer sửa được khẩu phần, giao được việc trên ngựa **không được giao cho mình**, và giao cho Groom thuộc đội khác (Flow 2 có chặn theo V37, Flow 4 thì không). Giao diện chỉ liệt kê ngựa của mình nên chỉ lộ qua API; riêng danh sách việc trong Stable care vẫn hiện việc của mọi ngựa | Không ảnh hưởng | Thêm kiểm tra `trainer_id` trong `CareService` giống `TrainingPlanService` |
| 3 | ~~Trung bình~~ **Đã sửa (frontend)** | Form **Restock** cho nhập "Unit" và "Storage area" nhưng backend bỏ qua cả hai. Đã bỏ hai ô này; số lượng luôn tính theo đơn vị của món. Backend vẫn nhận `unit` qua API | - | Backend: dùng đơn vị của món khi ghi sổ |
| 4 | ~~Trung bình~~ **Đã sửa** | Trang Supplies mất nút kiểm đếm (từ commit `880540b`). Đã thêm lại **Count**: Groom đếm thức ăn và dụng cụ, Vet và Manager đếm mọi loại | - | - |
| 4b | ~~Trung bình~~ **Đã sửa** | Form **Edit** vật tư không gửi `unitPrice`, backend ghi `null`: mỗi lần sửa một món là **mất đơn giá** (Yến mạch, Lược chải lông, Móng sắt đang hiện "—" có thể vì vậy). Đã thêm ô "Unit price" vào form | Nhập lại đơn giá cho các món đang "—" | - |
| 5 | Trung bình | Stable map của Groom chỉ hiện việc **hôm nay**, Stable care của HLV không có nút đánh dấu. Vì vậy luật "hôm qua vẫn đánh dấu được (DONE_LATE)" không dùng được trên giao diện, dù tab Missed ghi "A task can still be ticked the day after" | Chỉ chỉ vào chip "Done late" có sẵn từ seed | Cho Groom xem việc hôm qua còn mở, hoặc thêm nút đánh dấu ở Stable care |
| 6 | Thấp | Số lượng khẩu phần quá lớn (≥ 10000) trả **500** "Unexpected server error" (cột `DECIMAL(6,2)`) | Nhập số hợp lý | `@DecimalMax("9999.99")` trong `RationItem` và `max` ở ô nhập |
| 7 | Thấp | Đơn giá vật tư âm vẫn được lưu | - | `@DecimalMin("0")` cho `unitPrice` |
| 8 | Thấp | Khẩu phần cho phép hai dòng trùng (cùng bữa, cùng món) và trừ kho cả hai | - | Gộp hoặc báo lỗi dòng trùng |

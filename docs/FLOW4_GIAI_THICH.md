# Hiểu Flow 4 từ đầu: Chăm sóc chuồng trại & dinh dưỡng hằng ngày

Tài liệu cho người chưa biết gì về Flow 4. Đọc theo thứ tự: phần 1-3 để hình dung, phần 4-8 để nắm từng luật và lý do, phần 9-12 để nối với code và các flow khác, phần 13 là những chỗ nghiệp vụ còn đáng nghi để sửa, phần 14 để tự kiểm tra đã hiểu chưa.

Mọi luật dưới đây được đối chiếu với code ngày 13/11/2026. Đường dẫn file backend tính từ `backend/src/main/java/racehorse/backend/` (cấu trúc theo lớp từ 14/11: `controller/`, `service/`, `repository/`, `entity/`…), frontend tính từ `frontend/src/features/`.

---

## 1. Flow 4 là gì, trong một câu

> **Mỗi ngày, mỗi con ngựa ở câu lạc bộ phải được ăn đúng khẩu phần, được dọn chuồng, tắm, chườm đá; ai làm việc gì phải rõ ràng; ăn hết bao nhiêu thức ăn thì kho phải giảm bấy nhiêu; thấy ngựa có vấn đề thì báo ngay cho bác sĩ.**

Flow 2 lo chuyện ngựa **tập** (trên đường đua). Flow 4 lo chuyện ngựa **sống** (trong chuồng): ăn, ở, vệ sinh, và đồ dùng để làm những việc đó.

## 2. Hình dung một ngày ở chuồng TRACEN

Hãy theo chân Groom **Bình** (`binh.lv`) trong một ngày:

1. **Hôm trước**, HLV **Thắng** (`thang.nd`) đã giao cho Bình: sáng mai cho ăn và dọn chuồng 3 con Symboli Rudolf, Air Groove, Daiwa Scarlet. Bình nhận chuông "New care tasks".
2. **00:15 đêm**, hệ thống tự kiểm tra: việc của những ngày trước mà chưa ai đánh dấu xong thì đóng dấu **bỏ lỡ (MISSED)**, rồi báo cho mọi HLV.
3. **07:05 sáng**, Manager **Hòa** (`hoa.tm`) nhận một tin tổng hợp: "Cỏ khô timothy dưới định mức, thuốc Dexamethasone đã hết hạn…".
4. **Sáng**, Bình mở **Stable map** (sơ đồ chuồng). Mỗi ô là một chuồng, trong đó có tên ngựa, **khẩu phần hôm nay** (sáng 3.5 kg yến mạch, trưa 0.5 kiện cỏ, tối 2.5 kg cám) và **việc của Bình** với con ngựa đó.
5. Bình cho Symboli Rudolf ăn rồi bấm **xong** việc Feeding. Ngay lúc đó kho **tự trừ** 3.5 kg yến mạch, 0.5 kiện cỏ timothy và 2.5 kg cám, tức khẩu phần cả ngày. Sổ kho ghi: "CONSUME −3.5 kg, Feeding Symboli Rudolf, Lê Văn Bình".
6. Bình thấy Air Groove bỏ ăn, bụng chướng. Bình mở **Incident reports**, chụp ảnh, chọn mức HIGH rồi gửi. Bác sĩ **Hà** (`ha.pt`), HLV và chủ ngựa nhận chuông. Từ lúc này Air Groove **bị chặn tập** cho tới khi bác sĩ khám (phần này chuyển sang Flow 3).
7. Bình ra kho, đếm thấy cỏ timothy chỉ còn 45 kiện trong khi sổ ghi 48. Bình mở **Supplies** → **Count** → nhập 45. Sổ kho ghi "ADJUST −3".
8. Chiều, Hòa nhận xe hàng: nhập **Restock** 30 kiện cỏ. Sổ ghi "RECEIVE +30", kèm thông báo tổng tiền.
9. Bác sĩ Hà thấy Air Groove cần ăn ít tinh bột, nên sửa **khẩu phần** của nó trên trang **Feeding**. Từ ngày mai, việc cho ăn Air Groove sẽ trừ kho theo khẩu phần mới.

Toàn bộ Flow 4 chỉ là 9 bước này, cộng các luật để không ai làm sai.

## 3. Bốn mảng của Flow 4

```
┌─────────────────┐   mỗi dòng khẩu phần    ┌──────────────────┐
│ 1. KHẨU PHẦN    │ ─── trỏ tới một món ──► │ 3. KHO VẬT TƯ    │
│ (ăn gì, bao     │      FEED trong kho     │ (thức ăn, thuốc, │
│  nhiêu, bữa nào)│                         │  dụng cụ + sổ)   │
└────────┬────────┘                         └────────▲─────────┘
         │ việc FEEDING xong thì                     │
         │ trừ kho theo khẩu phần cả ngày            │
┌────────▼────────┐                                  │
│ 2. VIỆC HẰNG    │ ─────────────────────────────────┘
│    NGÀY         │
│ (cho ăn, dọn    │        ┌────────────────────────┐
│  chuồng, tắm,   │        │ 4. BÁO SỰ CỐ TẠI CHUỒNG │ ──► Flow 3 (bác sĩ khám)
│  chườm đá)      │        │ (Groom thấy ngựa ốm)    │ ──► chặn tập (Flow 2), chặn thi (Flow 5)
└─────────────────┘        └────────────────────────┘
```

| Mảng | Bảng dữ liệu | Trang giao diện |
|---|---|---|
| 1. Khẩu phần | `FEEDING_SCHEDULE` | Vet `/veterinarian/feeding`, HLV `/trainer/stable-care` (phần khẩu phần), Groom `/groom/feeding` |
| 2. Việc hằng ngày | `DAILY_TASK` | HLV `/trainer/stable-care`, Groom `/groom/stable` |
| 3. Kho vật tư | `SUPPLY_STOCK` + `SUPPLY_MOVEMENT` | `/manager/supplies`, `/veterinarian/supplies`, `/groom/supplies` |
| 4. Báo sự cố | `INCIDENT_REPORT` | Groom `/groom/incidents` |

## 4. Ai làm gì

| Việc | Club Manager | Head Trainer | Veterinarian | Groom | Horse Owner |
|---|:-:|:-:|:-:|:-:|:-:|
| Xem khẩu phần | ✔ | ✔ | ✔ | ✔ | ✔ (ngựa mình) |
| **Đặt / duyệt khẩu phần** | | ✔ | ✔ | | |
| Xem việc hằng ngày | ✔ (tất cả) | ✔ (tất cả) | | ✔ (của mình) | |
| **Giao việc**, chuyển việc, xoá việc, xem việc bỏ lỡ | ✔ | ✔ | | | |
| **Đánh dấu xong / bỏ đánh dấu** | | ✔ (làm hộ: **Mark done** / **Reopen** ở Stable care) | | ✔ (việc của mình, ở Stable map) | |
| Xem kho | ✔ | ✔ | ✔ (**chỉ thuốc**) | ✔ | |
| Thêm món, sửa danh mục (tên, đơn vị, định mức, khu vực, hạn dùng, đơn giá) | ✔ | | | | |
| **Kiểm đếm** (Count) | ✔ | | | ✔ (trừ thuốc) | |
| **Nhập hàng** (Restock) | ✔ | | | | |
| **Huỷ hàng** (Throw away) | ✔ | | | | |
| Xem sổ kho (History) | ✔ | ✔ | | | |
| **Báo sự cố tại chuồng** | | ✔ | | ✔ | |
| **Báo vấn đề vật tư / đề xuất bổ sung** (Report: sắp hết, hỏng, hết hạn, khác) | | ✔ | ✔ | ✔ | |
| Nhận chuông | thiếu hàng, hết hàng, nhập hàng, Groom báo vật tư, tổng hợp 07:05 | việc bỏ lỡ, sự cố | sự cố; **thuốc** dưới định mức, Groom báo về thuốc, tổng hợp thuốc 07:05 | được giao việc, bị chuyển việc | sự cố |

**Vì sao chia như vậy?**
- **Khẩu phần** là quyết định chuyên môn về sức khoẻ và thể lực, nên chỉ HLV và bác sĩ được quyết. Groom chỉ làm theo.
- **Thuốc** là hàng kiểm soát (đắt, có hạn dùng, dễ thất thoát), nên Groom không được đếm thuốc. Chỉ Manager đếm.
- **Kho là việc của Club Manager** (đặc tả: Manager "quản lý danh mục vật tư y tế & thức ăn"): chỉ Manager nhập, huỷ, đếm thuốc. **Bác sĩ không quản lý kho**: trang **Medicine** chỉ cho xem thuốc (số lượng, định mức, hạn dùng) để biết còn gì mà kê đơn, và có nút **Report** để báo Manager. (Từ 14/11; trước đó bác sĩ có cả Count, Restock, Throw away cho mọi món.)

## 5. Mảng 1: Khẩu phần

### Khái niệm
Khẩu phần là **danh sách các dòng**, mỗi dòng gồm: **bữa** (MORNING / NOON / EVENING) + **món ăn** (một món loại FEED trong kho) + **số lượng** (tính theo đơn vị của món trong kho) + ghi chú. Mỗi con ngựa có một khẩu phần; ai lưu sau cùng là **người duyệt**.

Ví dụ Symboli Rudolf:

| Bữa | Món (trong kho) | Số lượng | Đơn vị (lấy từ kho) |
|---|---|---|---|
| MORNING | Yến mạch | 3.5 | kg |
| NOON | Cỏ khô timothy | 0.5 | kiện |
| EVENING | Cám hỗn hợp | 2.5 | kg |

### Luật và lý do

| Luật | Vì sao | Code |
|---|---|---|
| Lưu khẩu phần là **thay toàn bộ** khẩu phần cũ (xoá cũ, chèn mới, trong một giao dịch) | Đơn giản, không có chuyện nửa cũ nửa mới | `service/CareService.replaceRation` |
| Mọi dòng **phải chọn một món FEED có trong kho**; tên và đơn vị lấy theo món đó | Để khi cho ăn, hệ thống biết trừ món nào, theo đơn vị nào | như trên |
| Kiểm tra mọi dòng **trước** khi xoá khẩu phần cũ | Một dòng sai không làm con ngựa mất hết khẩu phần | như trên |
| 1 đến 15 dòng; số lượng > 0 | Khẩu phần rỗng thì ngựa không được ăn | `entity/CareModels.RationRequest` |
| Chỉ ngựa đang ở chuồng: không cho hồ sơ PEDIGREE (chỉ để làm tổ tiên) hay ngựa đã rút | Không có chuồng thì không có ai cho ăn | `CareService.requireStabledHorse` |
| Không được đổi **đơn vị** của món đang dùng trong khẩu phần (vd. kg sang bao); không đổi món FEED sang loại khác | Khẩu phần ghi "3 kg"; đổi đơn vị kho sang "bao" thì mỗi bữa sẽ trừ 3 **bao** | `service/SupplyService.update` |
| **HLV không được thay khẩu phần do bác sĩ duyệt khi ngựa đang INJURED hoặc QUARANTINE** (lỗi 409 "Ask the vet to change it"). HLV vẫn sửa được khẩu phần do chính HLV đặt | Khẩu phần bác sĩ đặt cho ngựa ốm thường là chế độ ăn điều trị (ăn kiêng sau đau bụng…) | `CareService.replaceRation` (`RATION_LOCKED_STATUSES`) |
| **Khẩu phần đổi thì báo** (trừ người vừa lưu): bác sĩ lưu → **HLV phụ trách ngựa** và các bác sĩ khác; HLV lưu → **bác sĩ đã duyệt khẩu phần cũ**, hoặc **mọi bác sĩ** nếu ngựa đang INJURED, MONITOR, QUARANTINE; luôn báo **Groom có việc với con ngựa từ hôm nay**. Bấm chuông mở trang khẩu phần của từng vai trò | HLV và bác sĩ không vô tình ghi đè nhau; Groom cho ăn theo khẩu phần mới | `CareService.notifyRationChanged` |
| Dòng khẩu phần cũ (trước V21) có thể chưa nối kho (`supplyId = null`) và sẽ **không bị trừ kho** | Dữ liệu cũ chỉ là chữ; nối sai còn tệ hơn không nối | `V21__supply_movement_and_ration_link.sql` |

## 6. Mảng 2: Việc hằng ngày

### Khái niệm
Một **việc** gồm: con ngựa + **loại việc** (FEEDING cho ăn, CLEANING dọn chuồng, BATHING tắm, ICE_BATH chườm đá) + **Groom phụ trách** + **ngày** + ghi chú.

### Vòng đời của một việc

```
   giao việc                       Groom bấm xong            Groom bấm lại (bỏ đánh dấu)
 ────────────►  OPEN (To do)  ──────────────────►  DONE  ─────────────────────────────►  OPEN
                    │                                ▲
                    │ qua 00:00 mà chưa xong          │ bấm xong trong ngày hôm sau
                    ▼                                 │ (trễ tối đa 1 ngày)
                 MISSED  ─────────────────────────► DONE_LATE
                    │
                    │ quá 1 ngày: không bấm được nữa
                    ▼
               MISSED vĩnh viễn (bằng chứng chuồng không được dọn)
```

Trạng thái **không lưu trong một cột**, mà được **tính ra khi đọc** (`repository/CareRepository.stateOf`):
- `is_completed = 1` và hoàn thành đúng ngày → **DONE**; hoàn thành sau ngày của việc → **DONE_LATE**.
- `is_completed = 0` và ngày đã qua → **MISSED**; ngày là hôm nay hoặc tương lai → **OPEN**.
- Cột `missed_at` do job 00:15 đóng dấu, dùng để biết đã báo HLV chưa.

### Giao việc

| Luật | Vì sao | Code |
|---|---|---|
| Chỉ HLV và Manager giao | Phân công lao động là việc của người quản lý | `@RequireRole` trong `CareController` |
| Người nhận phải là **Groom đang hoạt động** | Không giao cho người đã nghỉ | `CareService.assign` |
| Không giao cho **ngày đã qua** | Giao việc cho hôm qua là ghi khống | như trên |
| **Mỗi ngựa, mỗi ngày, mỗi loại chỉ một việc**; giao trùng thì **bỏ qua** (skipped), không báo lỗi | Hai Groom cùng cho một con ăn là ngựa ăn gấp đôi | `care.taskExists` |
| Groom có **hơn 12 việc mở** trong ngày thì chỉ **cảnh báo**, vẫn giao | Ngày bận vẫn phải có người làm; cảnh báo để HLV cân nhắc | `app.task.max-per-groom-per-day` |
| **Giao hàng loạt**: tối đa 80 ngựa, tối đa 14 ngày; ngựa không ở chuồng bị bỏ qua kèm ghi chú; Groom nhận **một** chuông tóm tắt | Dọn chuồng cả dãy mỗi sáng trong tuần mà giao lẻ thì 20 × 7 = 140 lần bấm | `CareService.assignBulk` |

### Đánh dấu xong, bỏ đánh dấu

| Luật | Vì sao | Code |
|---|---|---|
| Groom chỉ đánh dấu **việc của mình**; HLV được đánh dấu hộ bằng **Mark done** / **Reopen** trong bảng Daily tasks (việc hôm nay và hôm qua), bảng ghi "ticked by …" | Groom quên bấm thì HLV xác nhận thay, nhưng vẫn rõ ai là người xác nhận | `CareService.requireTask`, `care/StableCarePage.js` (`toggleDone`) |
| **Không đánh dấu việc ngày mai** | Chưa làm thì không thể xong | `CareService.complete` |
| Việc hôm qua **vẫn đánh dấu được** (thành DONE_LATE); quá 1 ngày thì không | Groom có thể quên bấm tối qua; nhưng một tuần sau mới bấm thì không còn đáng tin | `app.task.late-window-days=1` |
| Bấm hai lần (hoặc hai người cùng bấm) thì người sau nhận "already done", **kho chỉ bị trừ một lần** | Chống trừ kho gấp đôi | câu `UPDATE … WHERE is_completed = 0` trong `CareRepository.markDone` |
| Bỏ đánh dấu chỉ trong cùng khung thời gian; việc ngày đã qua bị bỏ đánh dấu sẽ quay về MISSED | Sửa bấm nhầm, không để sửa lịch sử | `CareService.undo` |

### Cho ăn thì trừ kho

Khi một việc **FEEDING** được đánh dấu xong (`CareService.consumeRation`):
1. Lấy khẩu phần **hiện tại** của con ngựa (cả 3 bữa).
2. Gộp theo món kho, ví dụ yến mạch sáng 3 kg + tối 2 kg = 5 kg.
3. Trừ từng món. Mỗi món ghi một dòng sổ **CONSUME**, kèm `ref_type = DAILY_TASK`, `ref_id` = mã việc.
4. **Kho không đủ** thì trừ hết phần còn lại (về 0), **không chặn Groom**, rồi báo mọi Manager "Stock ran out".
   *Lý do:* số trên sổ do người đếm tay nên có thể sai; không thể để ngựa nhịn đói chỉ vì sổ ghi hết hàng.
5. Món tụt xuống dưới định mức lần đầu thì báo Manager "Restock suggested" (nếu là thuốc thì báo cả bác sĩ).

Khi **bỏ đánh dấu** một việc FEEDING (`CareService.undo`): hệ thống **đọc sổ kho** xem việc này đã lấy bao nhiêu và trả lại đúng lượng đó (dòng **REVERSAL**). Nó **không tính lại theo khẩu phần**, vì khẩu phần có thể đã đổi trong lúc đó.
Ví dụ: kho cà rốt còn 3 kg, khẩu phần cần 4 kg → bấm xong thì trừ 3 (kho về 0) → bỏ đánh dấu thì trả lại **3**, không phải 4.

### Chuyển việc, xoá việc, báo cáo bỏ lỡ

| Luật | Vì sao |
|---|---|
| **Chuyển việc** (Hand over): chỉ việc chưa xong, của hôm nay trở đi, sang một Groom khác đang hoạt động; **cả hai Groom** nhận chuông | Groom ốm đột xuất thì chuyển cho người khác; người cũ phải biết mình không còn phụ trách |
| **Xoá việc**: chỉ việc chưa xong, của hôm nay trở đi | Việc của ngày đã qua (xong hoặc bỏ lỡ) là **bằng chứng**; xoá đi thì báo cáo bỏ lỡ không còn thấy |
| **Báo cáo bỏ lỡ** (tab Missed): mặc định 7 ngày gần nhất đến hôm qua | HLV, Manager theo dõi chất lượng chăm sóc |

## 7. Mảng 3: Kho vật tư

### Hai khái niệm: số dư và sổ cái
- `SUPPLY_STOCK.current_qty` = **số dư hiện tại**, giống số dư tài khoản ngân hàng.
- `SUPPLY_MOVEMENT` = **sổ cái**, giống sao kê: mỗi lần số dư đổi thì có một dòng, ghi đổi bao nhiêu, còn lại bao nhiêu, ai làm, vì sao.

**Quy tắc vàng:** số dư **chỉ** đổi qua một trong 5 loại giao dịch, và mỗi lần đổi đều có dòng sổ. Ngay cả form **Edit** của Manager cũng **không** đổi được số lượng.

| Loại | Khi nào | Ai | Dấu |
|---|---|---|---|
| **RECEIVE** | Nhập hàng (Restock), hoặc số lượng ban đầu khi thêm món | Manager | + |
| **CONSUME** | Việc FEEDING xong (Flow 4), hoặc bác sĩ kê đơn thuốc (Flow 3) | tự động | − |
| **REVERSAL** | Bỏ đánh dấu việc FEEDING | tự động | + |
| **ADJUST** | Kiểm đếm ra số khác sổ. **Ít hơn sổ**: không cần lý do (hao hụt là bình thường). **Nhiều hơn sổ**: bắt buộc chọn lý do (đếm trước sai, hàng được trả lại, tìm thấy hàng để nhầm chỗ, kho tự trừ nhưng chưa dùng, khác + ghi chú); lý do ghi vào dòng sổ; nếu người đếm không phải Manager thì Manager nhận chuông | Manager, Groom (trừ thuốc) | + hoặc − |
| **WASTE** | Huỷ hàng hỏng, hết hạn, vỡ; bắt buộc ghi lý do; không huỷ quá số đang có | Manager | − |

### Định mức và cảnh báo
- Mỗi món có **định mức tối thiểu** (min). Cột `needs_restock = current_qty < min_threshold` do database tự tính, và giao diện hiện là **"Restock suggested"**.
- Lần đầu một món **tụt** xuống dưới định mức, mọi Manager nhận chuông. Các lần trừ tiếp theo không báo lại, để tránh spam (`SupplyService.warnIfLow`).
- **07:05 mỗi sáng**: mỗi Manager nhận **một** tin tổng hợp các món đang dưới định mức, cùng các thuốc đã hết hạn hoặc hết hạn trong 30 ngày tới; **bác sĩ nhận phần thuốc** của bản tổng hợp đó. Mỗi món chỉ được nhắc một lần mỗi ngày (cột `last_reminded_on`).
- **Groom chủ động báo** một món (nút **Report**): sắp hết (kèm số lượng cần), hỏng, hết hạn, hoặc lý do khác (bắt buộc ghi chú). Manager nhận chuông ngay; nếu là thuốc thì cả bác sĩ. Số lượng **không đổi**; Manager quyết định nhập, đếm hay huỷ (`SupplyService.report`).
- **Thuốc đã hết hạn không kê đơn được** (Flow 3): form kê đơn làm mờ thuốc hết hạn, backend trả 409 (`TreatmentService.prescribe`).

### Luật danh mục
| Luật | Vì sao |
|---|---|
| Ba loại: FEED, MEDICINE, TOOL | Chỉ FEED được dùng trong khẩu phần; chỉ MEDICINE có hạn dùng và được kê đơn |
| Tên không trùng **trong cùng khu vực** | "Yến mạch" ở Kho A và Kho B là hai lô khác nhau |
| **Hạn dùng chỉ cho thuốc** | Có ràng buộc `CK_SUP_expiry` trong database |
| Đơn giá dùng để tính tiền khi nhập hàng | Manager nhận thông báo kèm tổng tiền |

## 8. Mảng 4: Báo sự cố tại chuồng

| Luật | Vì sao | Code |
|---|---|---|
| Groom hoặc HLV gửi; triệu chứng bắt buộc (≤ 255 ký tự), mô tả ≤ 2000, mức khẩn HIGH/MEDIUM/LOW | Bác sĩ cần đủ thông tin để xếp thứ tự ưu tiên | `service/IncidentFilingService` |
| Ảnh tuỳ chọn: chỉ JPEG/PNG/WebP (kiểm tra **nội dung file**, không chỉ đuôi), ≤ 2 MB | Chống tải file độc hại hoặc quá nặng | `util/ImageFiles` |
| Ngựa phải đang ở chuồng | Ngựa đã rút thì câu lạc bộ không chịu trách nhiệm | như trên |
| Gửi xong thì báo **mọi bác sĩ**, **chủ ngựa** và **mọi HLV** | Ai liên quan cũng phải biết | `notifyRole`, `notifyHorseWatchers` |
| Báo cáo vào **cùng hàng chờ khám** với báo cáo từ buổi tập bị dừng | Bác sĩ chỉ cần xem một nơi | |
| Khi báo cáo chưa được khám: **chặn tập** (`IncidentGuard`) và **chặn đăng ký thi đấu** | Ngựa có thể đang ốm, ép tập hoặc thi là nguy hiểm | `service/IncidentGuard`, `service/RaceService` |

## 9. Một cú bấm kéo theo những gì (chuỗi phản ứng)

| Thao tác | Hệ thống làm thêm |
|---|---|
| Groom bấm xong **FEEDING** | Trừ kho theo khẩu phần cả ngày → ghi sổ CONSUME → thiếu hàng thì báo Manager "Stock ran out" → tụt dưới định mức thì báo "Restock suggested" → ghi audit `DAILY_TASK_DONE` |
| Groom bỏ đánh dấu FEEDING | Trả kho theo sổ (REVERSAL) → ghi audit `DAILY_TASK_REOPEN` |
| HLV giao việc | Ghi audit `DAILY_TASK_ASSIGN` → chuông cho Groom → quá 12 việc thì trả về cảnh báo |
| Groom báo sự cố | Báo bác sĩ, HLV, chủ ngựa → ngựa bị chặn tập và chặn thi tới khi bác sĩ khám |
| Manager **duyệt rút ngựa** (Flow 1) | **Xoá mọi việc chưa làm** của con ngựa từ hôm nay (lịch sử giữ nguyên) → báo các Groom liên quan → trả chuồng → khẩu phần không còn hiện |
| Manager **khoá tài khoản Groom** (Staff) | Hỏi người thay thế → chuyển mọi việc chưa làm từ hôm nay sang người đó; không có người thay thì cảnh báo số việc còn treo |
| Bác sĩ **kê đơn thuốc** (Flow 3) | Trừ kho thuốc (CONSUME, `ref_type = TREATMENT`) → hết thuốc hoặc không đủ thì chặn kê đơn |
| 00:15 mỗi đêm | Đóng dấu MISSED cho việc chưa xong của ngày cũ → báo mọi HLV |
| 07:05 mỗi sáng | Tổng hợp kho cho Manager |

## 10. Flow 4 nối với các flow khác

| Flow | Mối nối |
|---|---|
| **Flow 1 - Hồ sơ ngựa** | Chỉ ngựa ACADEMY có chuồng mới có khẩu phần và việc. Rút ngựa thì xoá việc tương lai. Chuồng (`stall_no`) quyết định ô nào trên Stable map |
| **Flow 2 - Tập luyện** | Cùng những Groom ấy vừa đi tập vừa chăm chuồng. Chườm đá (ICE_BATH) thường làm sau buổi tập nặng, nhưng **hệ thống không tự tạo** việc này. Sự cố tại chuồng chặn tập |
| **Flow 3 - Y tế** | Báo sự cố → hàng chờ khám. Bác sĩ duyệt khẩu phần, đếm thuốc, nhập hoặc huỷ thuốc. Kê đơn trừ kho thuốc |
| **Flow 5 - Thi đấu, chi phí** | Sự cố chưa khám chặn đăng ký giải. Chi phí thức ăn (FEEDING) của ngựa **do Manager ghi tay** ở trang Costs, không lấy từ lượng đã ăn |
| **Staff / phân quyền** | Groom thuộc đội của một HLV (tối đa 3). Khoá Groom thì chuyển việc |
| **Thông báo, Audit log** | Mọi thao tác ghi audit; các mốc quan trọng gửi chuông |

## 11. Việc chạy tự động

| Giờ | Job | Làm gì | Cấu hình |
|---|---|---|---|
| 00:15 hằng ngày | `service/MissedTaskJob` | Đóng dấu MISSED, báo HLV (đồng thời huỷ buổi tập lỡ ngày, kết thúc giáo án quá hạn của Flow 2) | `app.task.missed.cron` |
| 07:05 hằng ngày | `service/SupplyDigestJob` | Tổng hợp kho cho Manager | `app.supply.digest.cron` |

Muốn thử ngay thì đổi cron trong `application.properties` sang vài phút tới rồi khởi động lại backend.

## 12. Bản đồ code

| Lớp | File | Làm gì |
|---|---|---|
| API khẩu phần, việc | `controller/CareController.java` | Danh sách endpoint, phân quyền `@RequireRole` |
| Luật khẩu phần, việc | `service/CareService.java` | **Đọc file này đầu tiên**: mọi luật mục 5 và 6 |
| SQL khẩu phần, việc | `repository/CareRepository.java` | Câu lệnh SQL, tính trạng thái OPEN/DONE/… |
| Job đêm | `service/MissedTaskJob.java` | |
| API kho | `controller/SupplyController.java` | |
| Luật kho | `service/SupplyService.java` | Mục 7: số dư, sổ cái, cảnh báo, tổng hợp |
| SQL kho | `repository/SupplyRepository.java` | Trừ kho bằng **một câu UPDATE** để hai người bấm cùng lúc không trừ sai |
| Báo sự cố | `service/IncidentFilingService.java`, `service/IncidentGuard.java` | |
| Bảng dữ liệu | `migration/V1` (bảng gốc), `V21` (sổ kho, nối khẩu phần), `V24` (tên theo khu vực, hạn dùng, nhắc), `V25` (người đánh dấu, bỏ lỡ) | |
| Gọi API (frontend) | `care/careApi.js` | Mọi hàm gọi `/api/...` của Flow 4, nhãn hiển thị |
| Trang Groom | `care/StableMapPage.js` (sơ đồ chuồng + việc), `care/FeedingPage.js`, `care/IncidentReportPage.js`, `care/SuppliesPage.js` | |
| Trang HLV | `care/StableCarePage.js` (giao việc, Missed, khẩu phần) | |
| Bảng khẩu phần | `care/FeedingBoard.js` (dùng chung cho HLV, Vet, Groom) | |
| Test | `BusinessRulesTest.java`, phần "Flow 4: phân công chăm sóc" | Unit test, không cần database |

**Cách lần một luật:** thấy thông báo lỗi trên giao diện, ví dụ "This task is for 2026-11-14, not today", thì tìm đúng câu đó trong `backend/src` (Ctrl+Shift+F). Nó dẫn thẳng tới chỗ kiểm tra.

## 13. Những chỗ nghiệp vụ còn đáng nghi (nên sửa hoặc chuẩn bị câu trả lời)

Xếp theo mức ảnh hưởng. Cột "Gợi ý" chỉ là hướng đi; cần nhóm thống nhất trước khi sửa.

| # | Vấn đề | Vì sao đáng nghi | Gợi ý |
|---|---|---|---|
| 1 | **HLV thao tác được ngựa và Groom không thuộc mình** trong Flow 4 (đặt khẩu phần, giao việc). Flow 2 thì có chặn | Không nhất quán; HLV khác có thể giao việc cho Groom của đội khác | Thêm kiểm tra `trainer_id` trong `CareService` giống `TrainingPlanService` |
| 2 | **Một việc FEEDING trừ khẩu phần cả ngày** (cả 3 bữa) | Groom mới cho ăn bữa sáng, bấm xong là kho đã trừ cả bữa tối; bữa tối bỏ lỡ thì kho vẫn bị trừ | Tách việc theo bữa, hoặc chỉ trừ bữa tương ứng (code đã ghi chú là "#13") |
| 3 | **Groom chỉ thấy việc hôm nay** trên Stable map | Luật "hôm qua vẫn đánh dấu được" không dùng được trên giao diện; tab Missed lại ghi "can still be ticked the day after" | Cho Groom xem việc hôm qua còn mở |
| 4 | **Thức ăn đã dùng không thành chi phí của chủ ngựa** | Có đơn giá và có lượng tiêu thụ theo từng ngựa, nhưng chi phí FEEDING phải ghi tay | Cuối tháng tự cộng CONSUME × đơn giá thành dòng chi phí, hoặc nói rõ là ngoài phạm vi |
| 5 | **Khẩu phần không cần bác sĩ duyệt** nếu HLV đặt | Đặc tả ghi "khẩu phần **được duyệt**"; hiện HLV tự đặt là xong | Thêm trạng thái "chờ bác sĩ duyệt", hoặc coi HLV là người duyệt hợp lệ |
| 6 | ~~Đổi khẩu phần không báo ai~~ **Đã sửa** | Khẩu phần đổi thì báo HLV phụ trách hoặc bác sĩ liên quan và Groom chăm con ngựa; HLV bị chặn thay khẩu phần của bác sĩ khi ngựa INJURED hoặc QUARANTINE | - |
| 7 | **Không có "khu vực phụ trách"** của Groom | Đặc tả: Groom theo dõi vật tư "tại khu vực phụ trách"; hiện Groom thấy và đếm mọi khu | Gắn Groom với khu vực, hoặc giải thích là cả chuồng dùng chung kho |
| 8 | ~~Không có phiếu "đề xuất bổ sung"~~ **Đã sửa** | Groom có nút **Report** trên Supplies: báo sắp hết, hỏng, hết hạn; Manager (thuốc: cả bác sĩ) nhận chuông. Chưa có danh sách đề xuất để theo dõi đã xử lý hay chưa (chỉ chuông và Audit log) | Nếu cần theo dõi: thêm bảng đề xuất có trạng thái |
| 9 | Ngựa **cách ly (QUARANTINE)** vẫn được giao cho bất kỳ Groom nào | Thực tế cách ly cần người riêng để tránh lây | Cảnh báo khi giao việc cho ngựa QUARANTINE |
| 10 | ~~Bác sĩ kê được thuốc đã hết hạn~~ **Đã sửa** | Form kê đơn làm mờ thuốc hết hạn; backend chặn 409 | - |
| 10b | ~~Bác sĩ không nhận cảnh báo thuốc~~ **Đã sửa** | Thuốc dưới định mức và bản tổng hợp 07:05 (phần thuốc) giờ gửi cả bác sĩ | - |
| 11 | Khẩu phần **cho phép hai dòng giống hệt** (cùng bữa, cùng món) | Kho bị trừ gấp đôi | Gộp hoặc báo lỗi |
| 12 | Số lượng khẩu phần ≥ 10000 trả **lỗi 500** | Cột `DECIMAL(6,2)` | Thêm giới hạn 9999.99 ở `RationItem` và ô nhập |
| 13 | Nhập hàng qua API với **đơn vị khác** vẫn cộng thẳng; **đơn giá âm** vẫn lưu | Giao diện đã chặn, nhưng API thì chưa | Kiểm tra ở `SupplyService` |
| 14 | Việc bỏ lỡ **chỉ báo HLV**, không báo Manager | Manager chịu trách nhiệm vận hành chuồng | Thêm Manager vào thông báo, hoặc đưa vào Club overview |

## 14. Tự kiểm tra (đáp án ở cuối)

1. Groom Bình cho Vodka ăn, khẩu phần cần 4 kg cà rốt nhưng kho chỉ còn 3 kg. Bấm xong thì chuyện gì xảy ra?
2. Sau câu 1, Bình bấm nhầm và bỏ đánh dấu. Kho cà rốt còn bao nhiêu?
3. Manager muốn đổi đơn vị "Yến mạch" từ kg sang bao. Được không?
4. Hôm nay là 13/11. Groom bấm xong việc của ngày 12/11 thì sao? Việc ngày 11/11 thì sao? Việc ngày 14/11 thì sao?
5. HLV giao Feeding cho Symboli Rudolf hôm nay cho Nam, trong khi Bình đã được giao việc đó. Kết quả?
6. Groom có đếm được thuốc Bute không? Vì sao?
7. Groom báo Air Groove bỏ ăn. HLV có xếp buổi tập cho Air Groove chiều nay được không?
8. Manager duyệt cho chủ ngựa rút Daiwa Scarlet. Các việc dọn chuồng tuần sau của Daiwa Scarlet đi đâu?
9. Vì sao số lượng trong kho không sửa được bằng nút Edit?

<details>
<summary>Đáp án</summary>

1. Việc vẫn xong; kho cà rốt về 0 (lấy 3 kg); Manager nhận "Stock ran out … needed 4 kg, only 3 was in stock". Không chặn Groom.
2. 3 kg. Bỏ đánh dấu trả lại đúng lượng đã lấy theo sổ (3), không phải theo khẩu phần (4).
3. Không, nếu đang có khẩu phần dùng Yến mạch: lỗi 409 "ration line(s) measure Yến mạch in kg. Change those rations first".
4. 12/11: được, thành DONE_LATE. 11/11: không, "can no longer be ticked. It stays as missed". 14/11: không, "This task is for 2026-11-14, not today".
5. Bị bỏ qua (skipped): mỗi ngựa, mỗi ngày, mỗi loại chỉ một việc. Muốn đổi người thì dùng **Hand over**.
6. Không (403). Thuốc là hàng kiểm soát, chỉ Manager đếm.
7. Không. Báo cáo sự cố chưa khám thì `IncidentGuard` chặn tập.
8. Bị xoá (việc chưa làm từ hôm nay trở đi); việc đã làm và việc của ngày cũ giữ nguyên làm lịch sử. Groom liên quan nhận thông báo.
9. Vì mọi thay đổi số lượng phải để lại dấu vết trong sổ cái (ai, bao nhiêu, vì sao). Muốn sửa số thì dùng **Count** (ghi ADJUST), **Restock** hoặc **Throw away**.
</details>

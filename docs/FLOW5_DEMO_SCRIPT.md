# Flow 5 - Đăng ký thi đấu & thành tích: kịch bản demo nhanh

**Bước 1** demo happy case theo đúng workflow (tạo giải → HLV đề xuất → chủ ngựa duyệt → nhập kết quả → thống kê). **Bước 2** cho thấy các luật nghiệp vụ chặn hoặc tự xử lý. Không demo CRUD.

Thời lượng gợi ý: **khoảng 5 phút** (Bước 1 khoảng 2,5 phút, Bước 2 khoảng 2,5 phút).

## 1. Chuẩn bị

1. **Kịch bản đúng cho ngày demo từ 14/11 đến 03/12/2026.** Ngựa của `thang.nd` có trial run ngày 03/11, mà luật bắt buộc trial run trong 30 ngày. Demo sau 03/12 thì cho Symboli Rudolf và Orfevre chạy một buổi trial run (Flow 2, buổi tập đánh dấu trial run, hoàn thành) trước buổi demo.
2. **Backup DB `rtms` trước khi tập** (SSMS → Tasks → Back Up), vì demo ghi dữ liệu thật và Flow 5 chưa có script reset. Restore trước buổi demo chính thức.
3. Khởi động lại backend một lần để Flyway áp các migration mới.
4. Mở **4 trình duyệt hoặc profile khác nhau** (phiên đăng nhập lưu trong `localStorage`):

   | Cửa sổ | Tài khoản (mật khẩu `123456`) | Trang mở sẵn |
   |---|---|---|
   | A | `thang.nd` - Head Trainer | `/trainer/races` |
   | B | `anh.dq` - Owner của Symboli Rudolf | `/horse-owner/races` |
   | C | `son.dv` - Owner của Orfevre | `/horse-owner/races` |
   | D | `hoa.tm` - Club Manager | `/manager/races` |

5. Bước 1.5 của Flow 4 báo sự cố sẽ chặn ngựa được báo. **Không dùng Symboli Rudolf hay Orfevre ở bước đó**, hoặc demo Flow 5 trước.

### Dữ liệu có sẵn

| Dữ liệu | Dùng để demo |
|---|---|
| **Tracen Cup 2026** (30/10): Symboli Rudolf, Deep Impact, Special Week đã được duyệt, chưa có kết quả → lọc "Result needed" | Nhập kết quả; kết quả khoá sau 7 ngày |
| Ngựa của `thang.nd` sẵn sàng đua: Symboli Rudolf, Orfevre, Air Groove, Vodka, Daiwa Scarlet, TM Opera O, Almond Eye | Happy case |
| Demo Star (khoá tập + sự cố chờ khám), Biwa Hayahide (INJURED), Hishi Amazon (QUARANTINE), ngựa 20+ tuổi, ngựa chưa trial run | Danh sách "cannot race" kèm lý do |
| Deep Impact do `kien.hm` phụ trách | Không có trong danh sách của `thang.nd` |
| Lịch JRA 2026–2027 (Japan Cup, Asahi Hai, Hopeful Stakes…) | Luật nghỉ 14 ngày giữa hai giải |

## 2. Bước 1 - Happy case theo workflow

| # | Ai | Làm gì | Cho giáo viên thấy |
|---|---|---|---|
| 1.1 | D - Manager | **Race calendar** → **Create race**: `Tracen Winter Cup 2026`, ngày `20/12/2026`, `1800` m, `Tracen Racecourse`, hạn đăng ký `10/12/2026`, phí `10000000`, tối đa `2` ngựa | Giải hiện trên lịch: Open, 0/2. Tên và ngày không sửa được sau khi tạo |
| 1.2 | A - HLV | **Enter a horse**: chọn Tracen Winter Cup → Symboli Rudolf, note "Target winter season" → **Enter horse** | Mục "Pending Owner". 🔔 **B**: *"Race entry proposal … (entry fee 10000000)"* |
| 1.3 | A - HLV | Làm tương tự với **Orfevre**, rồi chọn lại Tracen Winter Cup | Giải thành 2/2 (đề xuất chờ duyệt cũng giữ chỗ): *"This race is full (2 horses)."*, nút Enter bị khoá. 🔔 **C** |
| 1.4 | B - Owner | **Results & races** → Race entry proposals → **Approve** Symboli Rudolf | Mục chuyển sang Upcoming. **Costs & prizes** có thêm 10.000.000 (OTHER, "Race entry fee: …"). 🔔 **A**: *"Race entry approved"* |
| 1.5 | C - Owner | **Decline** Orfevre, ghi lý do "Chưa đủ thể lực" (lý do không bắt buộc) | Mục "Declined"; 🔔 **A** kèm lý do. Giải trở về 1/2 |
| 1.6 | A - HLV | Lọc **Result needed** → Tracen Cup 2026 → **Record result**: Symboli Rudolf hạng `1`, `2:24.6`, thưởng `300000000`; Deep Impact hạng `3`, thưởng `60000000`; Special Week hạng `6` | Gõ hạng thì kết quả tự chọn WON / PLACED / UNPLACED; UNPLACED khoá ô thưởng. 🔔 chủ ngựa *"Race result"* |
| 1.7 | D - Manager | Thẻ thống kê và tab **Performance by horse** | Entries chỉ đếm suất chủ đã đồng ý (đề xuất chờ duyệt ghi riêng "waiting for owner"). Tiền thưởng **thuộc chủ ngựa**, không phải doanh thu CLB |

## 3. Bước 2 - Luật nghiệp vụ

| # | Luật | Ai / thao tác | Kết quả mong đợi |
|---|---|---|---|
| 2.1 | **Chỉ ngựa đủ điều kiện mới đăng ký được**: ngựa CLB, 2–10 tuổi, ELIGIBLE, không khoá tập, không có sự cố chờ khám, có tiêm phòng và chưa quá hạn, có trial run trong 30 ngày | A: chọn một giải → mở "N horses cannot race" | Demo Star *"has an active training lock; … incident report waiting for a veterinarian"*, Biwa Hayahide *"health status INJURED"*, ngựa già *"racing age is 2 to 10"*, ngựa chưa chạy thử *"no completed trial run in the last 30 days"* |
| 2.2 | **HLV chỉ đăng ký / rút ngựa mình phụ trách** (hoặc ngựa chưa giao cho ai) | A: mở dropdown Horse | Không có Deep Impact (của `kien.hm`). Gọi API thẳng thì bị 403 |
| 2.3 | **Hai giải của một ngựa cách nhau ít nhất 14 ngày** | A: chọn **Hopeful Stakes 2026** (26/12) | Symboli Rudolf bị chặn: *"runs in Tracen Winter Cup 2026 on 20 Dec 2026; races must be at least 14 days apart"* |
| 2.4 | **Thứ hạng phải khớp kết quả** (WON = 1, PLACED = 2–3, UNPLACED ≥ 4) | A: lúc nhập kết quả ở 1.6, gõ hạng 2 rồi đổi kết quả thành WON → Save | *"Position 2 is PLACED, not WON"* |
| 2.5 | **Kết quả khoá sau 7 ngày; chỉ Manager sửa, phải ghi lý do** | A: các dòng Tracen Cup hiện **Locked**. D: **Edit result** Deep Impact → hạng 2, thưởng `120000000`, để trống lý do → Save; rồi nhập lý do "Photo-finish review" → Save | Không có lý do thì báo *"A reason is required to change a locked result"*; có lý do thì lưu. **Audit log**: `RACE_RESULT` ghi *"PLACED, 3rd, 60000000 -> PLACED, 2nd, 120000000 (after lock). Reason: …"* |
| 2.6 | **Rút trước hạn đăng ký thì hoàn phí** | A: **Scratch** Symboli Rudolf khỏi Tracen Winter Cup, lý do "Sore hoof" | Mục "Scratched". 🔔 **B**: *"… The entry fee has been refunded."*; khoản phí biến khỏi Costs & prizes |
| 2.7 | **Đề xuất bị từ chối có thể đề xuất lại** | A: Enter **Orfevre** vào Tracen Winter Cup lần nữa | Mục của Orfevre quay về "Pending Owner". 🔔 **C** |
| 2.8 | **Manager huỷ giải**: mọi suất còn lại bị rút, chủ ngựa được báo (phí đã thu thì hoàn) | D: **Race calendar** → **Cancel race** Tracen Winter Cup, lý do "Track flooded" | Giải "Cancelled". Đề xuất của Orfevre thành "Scratched"; **C** nhận 🔔 *"… Race cancelled: Track flooded"* và mục này rời khỏi danh sách chờ duyệt. Audit log có `RACE_CANCEL` |
| 2.9 | **Chủ ngựa chỉ thấy ngựa của mình** | B: Results & races | Chỉ có ngựa của `anh.dq` |

Các luật sau không dựng được trong 5 phút (cần chờ qua ngày), nên chỉ nói miệng:
- Đề xuất chủ chưa duyệt khi qua hạn đăng ký sẽ thành **Expired**: không duyệt được nữa, không giữ chỗ trong giải, không chặn luật 14 ngày.
- Lúc chủ bấm Approve, hệ thống kiểm tra lại điều kiện. Ngựa bị khoá tập sau khi được đề xuất thì báo *"can no longer be approved: …"*.
- Ngựa có suất đua sắp tới mà bị khoá tập, bị khám chuyển trạng thái hay có báo cáo sự cố: chủ ngựa, HLV và người gây ra sự kiện nhận 🔔 *"Race entry at risk"*.
- Rút sau hạn đăng ký thì **không hoàn phí**.

## 4. Lỗi còn tồn tại

| # | Mức | Lỗi | Tránh khi demo |
|---|---|---|---|
| 1 | Trung bình | Rút ngựa khỏi CLB (Withdrawal) tự rút các suất đua nhưng **không hoàn lệ phí** và không ghi audit `RACE_SCRATCH` | Không demo rút ngựa đang có suất đua đã duyệt |
| 2 | Thấp | Owner Dashboard, mục "next race", có thể hiện cả đề xuất chưa duyệt hoặc đã bị từ chối | - |
| 3 | Thấp | Giải chưa có điều kiện tuổi/giới tính (vd. Hanshin Juvenile Fillies), và chưa chặn hai ngựa cùng về nhất trong một giải | Không chọn giải dành cho ngựa non khi demo |

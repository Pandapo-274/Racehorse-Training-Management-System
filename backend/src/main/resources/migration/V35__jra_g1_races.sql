/* =============================================================================
   V35 - Lịch các trận GⅠ / J・GⅠ của JRA để Head Trainer đăng ký ngựa.

   VẤN ĐỀ
   Bốn trận có sẵn (V22 dựng lại từ dữ liệu cũ) đều đã qua, nên ô "Race" ở
   màn Enter a horse trống: màn đó chỉ hiện trận OPEN, ngày đua sau hôm nay
   và chưa quá hạn đăng ký.

   DỮ LIỆU
   - 8 trận GⅠ còn lại của mùa 2026 (theo danh sách trọng thưởng JRA 2026).
   - 26 trận GⅠ + J・GⅠ của mùa 2027 (lịch JRA công bố ngày 28/09/2026).
   Cự ly và trường đua theo điều kiện chuẩn của từng trận. Hai trận vượt rào
   ghi "(J-G1)" trong tên vì bảng RACE không có cột hạng / mặt đường.
   Hạn đăng ký = ngày đua - 7 ngày. Số ngựa tối đa 16 hoặc 18 tuỳ trận, gần
   với giới hạn của JRA. Lệ phí (VND) là số demo của CLB, không phải của JRA.
   Người tạo là Club Manager hoa.tm.

   Chạy lại được: bỏ qua trận đã có (UQ_RACE = tên + ngày).
   ============================================================================= */

DECLARE @manager INT = (SELECT user_id FROM APP_USER WHERE username = 'hoa.tm');

INSERT INTO RACE (race_name, race_date, distance_m, venue, entry_deadline, entry_fee, max_entries, status, created_by)
SELECT v.race_name, v.race_date, v.distance_m, v.venue, DATEADD(DAY, -7, v.race_date), v.entry_fee, v.max_entries,
       'OPEN', @manager
FROM (VALUES
    -- ---------- Mùa 2026 (phần còn lại) ----------
    (N'Queen Elizabeth II Cup 2026',          CAST('2026-11-15' AS DATE), 2200, N'Kyoto Racecourse (turf)',    30000000.00, 18),
    (N'Mile Championship 2026',               CAST('2026-11-22' AS DATE), 1600, N'Kyoto Racecourse (turf)',    30000000.00, 18),
    (N'Japan Cup 2026',                       CAST('2026-11-29' AS DATE), 2400, N'Tokyo Racecourse (turf)',    50000000.00, 18),
    (N'Champions Cup 2026',                   CAST('2026-12-06' AS DATE), 1800, N'Chukyo Racecourse (dirt)',   30000000.00, 16),
    (N'Hanshin Juvenile Fillies 2026',        CAST('2026-12-13' AS DATE), 1600, N'Hanshin Racecourse (turf)',  20000000.00, 18),
    (N'Asahi Hai Futurity Stakes 2026',       CAST('2026-12-20' AS DATE), 1600, N'Hanshin Racecourse (turf)',  20000000.00, 18),
    (N'Hopeful Stakes 2026',                  CAST('2026-12-26' AS DATE), 2000, N'Nakayama Racecourse (turf)', 20000000.00, 18),
    (N'Arima Kinen 2026',                     CAST('2026-12-27' AS DATE), 2500, N'Nakayama Racecourse (turf)', 50000000.00, 16),

    -- ---------- Mùa 2027 ----------
    (N'February Stakes 2027',                 CAST('2027-02-21' AS DATE), 1600, N'Tokyo Racecourse (dirt)',    30000000.00, 16),
    (N'Takamatsunomiya Kinen 2027',           CAST('2027-03-28' AS DATE), 1200, N'Chukyo Racecourse (turf)',   30000000.00, 18),
    (N'Osaka Hai 2027',                       CAST('2027-04-04' AS DATE), 2000, N'Hanshin Racecourse (turf)',  30000000.00, 16),
    (N'Oka Sho (Japanese 1000 Guineas) 2027', CAST('2027-04-11' AS DATE), 1600, N'Hanshin Racecourse (turf)',  30000000.00, 18),
    (N'Nakayama Grand Jump (J-G1) 2027',      CAST('2027-04-17' AS DATE), 4250, N'Nakayama Racecourse (jump)', 20000000.00, 16),
    (N'Satsuki Sho (Japanese 2000 Guineas) 2027', CAST('2027-04-18' AS DATE), 2000, N'Nakayama Racecourse (turf)', 30000000.00, 18),
    (N'Tenno Sho (Spring) 2027',              CAST('2027-05-02' AS DATE), 3200, N'Kyoto Racecourse (turf)',    40000000.00, 18),
    (N'NHK Mile Cup 2027',                    CAST('2027-05-09' AS DATE), 1600, N'Tokyo Racecourse (turf)',    30000000.00, 18),
    (N'Victoria Mile 2027',                   CAST('2027-05-16' AS DATE), 1600, N'Tokyo Racecourse (turf)',    30000000.00, 18),
    (N'Yushun Himba (Japanese Oaks) 2027',    CAST('2027-05-23' AS DATE), 2400, N'Tokyo Racecourse (turf)',    40000000.00, 18),
    (N'Tokyo Yushun (Japanese Derby) 2027',   CAST('2027-05-30' AS DATE), 2400, N'Tokyo Racecourse (turf)',    50000000.00, 18),
    (N'Yasuda Kinen 2027',                    CAST('2027-06-06' AS DATE), 1600, N'Tokyo Racecourse (turf)',    30000000.00, 18),
    (N'Takarazuka Kinen 2027',                CAST('2027-06-13' AS DATE), 2200, N'Hanshin Racecourse (turf)',  40000000.00, 18),
    (N'Sprinters Stakes 2027',                CAST('2027-09-26' AS DATE), 1200, N'Nakayama Racecourse (turf)', 30000000.00, 16),
    (N'Shuka Sho 2027',                       CAST('2027-10-17' AS DATE), 2000, N'Kyoto Racecourse (turf)',    30000000.00, 18),
    (N'Kikuka Sho (Japanese St. Leger) 2027', CAST('2027-10-24' AS DATE), 3000, N'Kyoto Racecourse (turf)',    40000000.00, 18),
    (N'Tenno Sho (Autumn) 2027',              CAST('2027-10-31' AS DATE), 2000, N'Tokyo Racecourse (turf)',    40000000.00, 18),
    (N'Queen Elizabeth II Cup 2027',          CAST('2027-11-14' AS DATE), 2200, N'Kyoto Racecourse (turf)',    30000000.00, 18),
    (N'Mile Championship 2027',               CAST('2027-11-21' AS DATE), 1600, N'Kyoto Racecourse (turf)',    30000000.00, 18),
    (N'Japan Cup 2027',                       CAST('2027-11-28' AS DATE), 2400, N'Tokyo Racecourse (turf)',    50000000.00, 18),
    (N'Champions Cup 2027',                   CAST('2027-12-05' AS DATE), 1800, N'Chukyo Racecourse (dirt)',   30000000.00, 16),
    (N'Hanshin Juvenile Fillies 2027',        CAST('2027-12-12' AS DATE), 1600, N'Hanshin Racecourse (turf)',  20000000.00, 18),
    (N'Asahi Hai Futurity Stakes 2027',       CAST('2027-12-19' AS DATE), 1600, N'Hanshin Racecourse (turf)',  20000000.00, 18),
    (N'Nakayama Daishogai (J-G1) 2027',       CAST('2027-12-25' AS DATE), 4100, N'Nakayama Racecourse (jump)', 20000000.00, 16),
    (N'Arima Kinen 2027',                     CAST('2027-12-26' AS DATE), 2500, N'Nakayama Racecourse (turf)', 50000000.00, 16),
    (N'Hopeful Stakes 2027',                  CAST('2027-12-28' AS DATE), 2000, N'Nakayama Racecourse (turf)', 20000000.00, 18)
) AS v(race_name, race_date, distance_m, venue, entry_fee, max_entries)
WHERE NOT EXISTS (SELECT 1 FROM RACE r WHERE r.race_name = v.race_name AND r.race_date = v.race_date);

PRINT CONCAT('V35 done: ', @@ROWCOUNT, ' JRA G1 races added.');
GO

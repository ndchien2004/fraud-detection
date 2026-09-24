# Đề tài: Hệ thống Phát hiện Gian lận Thời gian thực (Java) — bản chi tiết đầy đủ, có giao diện thao tác

## 1. Tóm tắt

Hệ thống nhận luồng giao dịch liên tục, với mỗi giao dịch phải trả lời trong vài chục mili-giây: **CHO_QUA / XEM_XET / CHAN**. Điểm khác biệt của bản mô tả này là: phần "giả lập giao dịch" là một **trang web thao tác được** — bạn tự tay bấm nút để bắn giao dịch, dựng kịch bản gian lận, và xem ngay kết quả hệ thống phản ứng ra sao. Điều này giúp demo trực quan hơn rất nhiều (cho người khác xem cũng dễ hiểu hơn là chỉ đọc log).

## 2. Kiến trúc tổng thể — luồng dữ liệu đi qua từng bước

```
[Simulator UI] --(1. gửi giao dịch)--> [Kafka: topic "transactions"]
                                              |
                                              v (2. đọc liên tục)
                                    [Feature Service]
                                     - tính 5 chỉ số theo cửa sổ thời gian
                                              |
                                              v (3. ghi chỉ số)
                                         [Redis]
                                              |
                                              v (4. đọc chỉ số khi cần chấm điểm)
[Simulator UI] --(5. gọi API hỏi kết quả)--> [Decision API] --> [Scoring Service]
                                                                  - đọc rule config
                                                                  - đọc chỉ số từ Redis
                                                                  - nếu cần, gọi ONNX model
                                                                  - trả quyết định
                                              |
                                              v (6. trả JSON quyết định)
                                     [Simulator UI hiển thị kết quả]
                                              |
                                              v (7. đồng thời)
                                        [Dashboard] đọc log quyết định, vẽ biểu đồ real-time
```

Bước (1) và (5) gộp lại: khi bạn bấm "Gửi giao dịch" trên Simulator UI, nó vừa đẩy giao dịch vào Kafka (để feature service học từ nó cho các lần sau), vừa gọi thẳng Decision API để lấy kết quả ngay lập tức hiển thị cho bạn xem (không cần đợi vòng lặp Kafka Streams xử lý xong mới biết kết quả — quan trọng để demo mượt).

## 3. Simulator UI — mô tả chi tiết giao diện

Một trang web đơn (single page), chạy tại `http://localhost:8080`, chia làm 4 khu vực:

### 3.1. Khu vực "Chế độ tự động" (Auto Mode)
- 1 công tắc bật/tắt: khi bật, hệ thống tự sinh giao dịch ngẫu nhiên liên tục cho khoảng 20 thẻ giả lập có sẵn.
- 1 thanh trượt (slider) chỉnh tốc độ: từ 1 đến 500 giao dịch/giây.
- Hiển thị số liệu ngay bên cạnh: tổng số giao dịch đã gửi, số bị CHAN, số bị XEM_XET.

### 3.2. Khu vực "Gửi giao dịch thủ công" (Manual Transaction)
Một form gồm:
- Dropdown chọn thẻ (card-0001 đến card-0020, dữ liệu lịch sử của các thẻ này được tạo sẵn khi khởi động để có "trung bình lịch sử" mà so sánh).
- Ô nhập số tiền (amount).
- Dropdown chọn merchant (Shopee, Grab, Circle K, ATM rút tiền, v.v. — vài lựa chọn có sẵn).
- Dropdown chọn địa điểm (thay vì nhập lat/lon phức tạp, cho chọn theo tên thành phố: Hà Nội, TP.HCM, Đà Nẵng, Singapore, v.v. — hệ thống tự map ra tọa độ).
- Nút "Gửi giao dịch" — sau khi bấm, hiển thị ngay bên dưới: kết quả quyết định (CHO_QUA/XEM_XET/CHAN), điểm rủi ro cụ thể, và rule nào (nếu có) đã kích hoạt.

### 3.3. Khu vực "Kịch bản gian lận dựng sẵn" (Preset Fraud Scenarios)
3 nút bấm nhanh, mỗi nút tự động bắn một chuỗi giao dịch mô phỏng đúng 1 kiểu gian lận kinh điển, để bạn không cần tự tay set up:
- **"Quẹt dồn dập"**: tự động gửi 10 giao dịch liên tiếp trong 10 giây, cùng 1 thẻ — kỳ vọng: giao dịch thứ 6 trở đi bắt đầu bị CHAN vì vượt ngưỡng `so_giao_dich_5_phut`.
- **"Impossible travel"**: gửi giao dịch tại Hà Nội, sau đó 2 phút gửi tiếp giao dịch cùng thẻ tại TP.HCM (cách nhau 1160km, không ai di chuyển kịp trong 2 phút) — kỳ vọng: giao dịch thứ 2 bị CHAN ngay theo rule `khoang_cach_bat_thuong`.
- **"Chi tiêu bất thường"**: gửi 1 giao dịch có số tiền gấp 20 lần trung bình lịch sử của thẻ đó — kỳ vọng: bị đẩy sang XEM_XET hoặc CHAN tùy điểm ML.

Mỗi nút khi bấm sẽ hiện animation/log từng bước ("Đang gửi giao dịch 1/10...", "Đang gửi giao dịch 2/10...") và bảng kết quả để bạn thấy rõ tại giao dịch nào hệ thống bắt đầu phát hiện ra bất thường.

### 3.4. Khu vực "Nhật ký thời gian thực" (Live Feed)
Một bảng cập nhật liên tục (dùng WebSocket hoặc polling mỗi 1 giây), mỗi dòng gồm: thời gian, mã giao dịch, thẻ, số tiền, quyết định (tô màu xanh/vàng/đỏ tương ứng CHO_QUA/XEM_XET/CHAN), điểm rủi ro.

### 3.5. Công nghệ cho phần UI này
- Đơn giản nhất: Spring Boot serve 1 trang HTML tĩnh + JavaScript thuần (fetch API), không cần React/Vue để tránh tốn thời gian dựng frontend phức tạp — mục tiêu là UI đủ dùng để thao tác và demo, không phải làm đẹp.
- Dùng WebSocket (Spring's `@MessageMapping`/STOMP) cho phần Live Feed để tự cập nhật không cần refresh; nếu muốn đơn giản hơn nữa, dùng polling `setInterval` gọi API mỗi giây cũng chấp nhận được.

## 4. Sản phẩm đầu ra cụ thể (Deliverables) — bản đầy đủ

1. **Simulator service** (`tx-simulator`) — vừa là backend sinh giao dịch, vừa serve Simulator UI (mục 3), gồm các REST endpoint:
   - `POST /simulator/manual-transaction` — body: `{cardId, amount, merchant, city, timestamp?}`, trả về ngay kết quả quyết định.
   - `POST /simulator/scenario/{name}` — `name` ∈ {`rapid-fire`, `impossible-travel`, `unusual-amount`}, chạy kịch bản dựng sẵn.
   - `POST /simulator/auto-mode` — body: `{enabled, ratePerSecond}`.
   - `GET /simulator/stats` — trả về tổng số giao dịch đã gửi, số CHAN/XEM_XET/CHO_QUA.
   - WebSocket endpoint `/ws/live-feed` phát mỗi giao dịch + quyết định ngay khi có.
2. **Feature Service** — Kafka Streams job tính 5 chỉ số (danh sách xem mục 5) theo cửa sổ trượt, ghi vào Redis.
3. **Scoring Service** — đọc chỉ số Redis, chạy rule engine (config dạng YAML, xem mục 6), gọi ONNX model khi cần, trả điểm + quyết định.
4. **Decision API** (`POST /check-transaction`) — nhận giao dịch, orchestrate gọi scoring service, trả JSON quyết định đầy đủ (xem mục 7 — API contract).
5. **Dashboard** — trang riêng (`http://localhost:8081`) hiển thị biểu đồ real-time: giao dịch/giây, tỷ lệ CHAN theo thời gian, top 10 giao dịch rủi ro cao nhất trong 1 giờ qua, biểu đồ latency (p50/p99).
6. **Model training script** (Python, thư mục `model-training/`) train model đơn giản trên dataset Kaggle "Credit Card Fraud Detection", xuất `model.onnx`.
7. **Rule config file** (`rules.yaml`) — xem ví dụ cụ thể ở mục 6, có thể sửa mà không cần build lại code.
8. **Docker Compose** dựng toàn bộ 6 service trên bằng 1 lệnh, kèm Kafka, Redis.
9. **Load Test Report** (`LOAD_TEST.md`) đo throughput/latency bằng Gatling (bắn traffic tự động, không qua UI, để đo hiệu năng thật).
10. **README.md** — hướng dẫn chạy, ảnh chụp màn hình Simulator UI và Dashboard, giải thích kiến trúc và trade-off thiết kế.

## 5. Chi tiết 5 chỉ số (feature) và công thức

| Tên chỉ số | Công thức / logic | Cửa sổ thời gian |
|---|---|---|
| `so_giao_dich_5_phut` | Đếm số giao dịch của `cardId` này trong 5 phút gần nhất | 5 phút trượt |
| `tong_tien_1_gio` | Tổng `amount` của `cardId` này trong 1 giờ gần nhất | 1 giờ trượt |
| `trung_binh_lich_su` | Trung bình `amount` của `cardId` này trong 30 ngày (tính offline, nạp sẵn lúc khởi động cho 20 thẻ giả lập) | 30 ngày (tĩnh) |
| `lech_so_voi_trung_binh` | `(amount - trung_binh_lich_su) / trung_binh_lich_su` — tỷ lệ % lệch so với trung bình | tại thời điểm giao dịch |
| `khoang_cach_bat_thuong` | So với giao dịch liền trước của cùng `cardId`: tính khoảng cách địa lý (Haversine formula) chia cho khoảng thời gian giữa 2 giao dịch → ra "tốc độ di chuyển ngụ ý" (km/h). Nếu > 900 km/h (nhanh hơn máy bay thương mại) → đánh dấu `true` | so với giao dịch liền trước |

## 6. Ví dụ cụ thể file `rules.yaml`

```yaml
rules:
  - name: "qua_nhieu_giao_dich"
    condition: "so_giao_dich_5_phut > 5"
    action: "CHAN"
  - name: "di_chuyen_bat_kha_thi"
    condition: "khoang_cach_bat_thuong == true"
    action: "CHAN"
  - name: "chi_tieu_qua_cao_tuyet_doi"
    condition: "amount > 50000000"
    action: "XEM_XET"

ml_thresholds:
  chan_neu_diem_tren: 0.8
  xem_xet_neu_diem_tren: 0.4
```

Scoring service đọc file này lúc khởi động (và có thể reload định kỳ), áp rule theo thứ tự trên xuống, nếu không rule nào khớp thì mới hỏi model ONNX.

## 7. API contract cụ thể

**`POST /check-transaction`**

Request:
```json
{
  "transactionId": "tx-000123",
  "cardId": "card-0007",
  "amount": 25000000,
  "merchant": "ATM",
  "location": { "lat": 21.0285, "lon": 105.8542 },
  "timestamp": "2026-08-19T10:15:32Z"
}
```

Response:
```json
{
  "transactionId": "tx-000123",
  "decision": "XEM_XET",
  "riskScore": 0.63,
  "triggeredRule": null,
  "features": {
    "so_giao_dich_5_phut": 2,
    "tong_tien_1_gio": 27000000,
    "lech_so_voi_trung_binh": 3.1,
    "khoang_cach_bat_thuong": false
  },
  "latencyMs": 18
}
```

Nếu 1 rule khớp trước, `triggeredRule` sẽ có tên rule đó và `riskScore` có thể để `null` (vì không cần hỏi model).

## 8. Lộ trình 4 tuần (đã cập nhật để có thời gian làm UI)

**Tuần 1:** Dựng Kafka, viết tx-simulator (backend trước — sinh giao dịch, đẩy Kafka, có sẵn 20 thẻ giả lập với lịch sử amount trung bình). Viết feature-service tính được ít nhất 2/5 chỉ số. Mục tiêu cuối tuần: có luồng giao dịch chảy vào Kafka và feature-service tính đúng số cơ bản.

**Tuần 2:** Hoàn thiện 5/5 chỉ số. Viết scoring-service đọc `rules.yaml`, viết model-training script (Python) và scoring-service tích hợp ONNX. Mục tiêu: `POST /check-transaction` (gọi trực tiếp qua Postman/curl) trả đúng quyết định cho vài case test tay.

**Tuần 3:** Xây Simulator UI (form thủ công, 3 nút kịch bản, live feed) — đây là phần việc mới thêm so với bản trước, ước tính 3-4 ngày công cho 1 trang UI đơn giản nhưng đủ chức năng. Kết nối UI với Decision API. Mục tiêu: bấm nút "Impossible travel" trên UI, thấy ngay giao dịch thứ 2 bị CHAN hiển thị đỏ trên live feed.

**Tuần 4:** Xây Dashboard (biểu đồ real-time). Chạy load test bằng Gatling (không qua UI, bắn thẳng vào Kafka/API để đo hiệu năng thật). Viết README kèm ảnh chụp UI. Mục tiêu cuối: demo hoàn chỉnh — mở Simulator UI, bấm các kịch bản, xem Dashboard cập nhật real-time, xem báo cáo hiệu năng.

## 9. Checklist "xong"

- [ ] `docker compose up` chạy toàn bộ 6 service, mở được Simulator UI tại `:8080` và Dashboard tại `:8081`.
- [ ] Trên Simulator UI, gửi giao dịch thủ công và thấy kết quả quyết định hiện ra ngay (không cần refresh trang).
- [ ] Bấm cả 3 nút kịch bản dựng sẵn và quan sát đúng hành vi kỳ vọng (rapid-fire bị chặn từ giao dịch thứ 6, impossible-travel bị chặn ngay giao dịch thứ 2, unusual-amount bị đẩy sang xem xét/chặn).
- [ ] Bật chế độ Auto Mode, Dashboard cập nhật số liệu real-time đúng.
- [ ] Sửa file `rules.yaml` (ví dụ đổi ngưỡng `so_giao_dich_5_phut` từ 5 xuống 3), reload, thấy hành vi hệ thống thay đổi mà không cần build lại code.
- [ ] Có `LOAD_TEST.md` ghi rõ throughput và latency p99 đo được.
- [ ] README có ảnh chụp màn hình Simulator UI và Dashboard, giải thích kiến trúc.

## 10. Nếu muốn giảm nhẹ độ khó

- Bỏ WebSocket, dùng polling đơn giản (`setInterval` gọi API mỗi 1-2 giây) cho Live Feed — vẫn đủ mượt để demo, code đơn giản hơn nhiều.
- Bỏ Kafka Streams, tính chỉ số bằng code Java thuần lưu trong `ConcurrentHashMap` (miễn chỉ chạy 1 instance) — vẫn giữ được toàn bộ trải nghiệm UI như mô tả.
- Bỏ hẳn việc train model ML, thay `ml_thresholds` bằng công thức tính điểm rủi ro là tổng có trọng số của các chỉ số (`riskScore = 0.4*lech_so_voi_trung_binh_norm + 0.6*so_giao_dich_5_phut_norm`, ví dụ) — vẫn đủ để phần rule + "điểm rủi ro" hoạt động đúng như thiết kế, chỉ đơn giản hóa phần AI thật sự.

# Realtime Fraud Detection (Java)

Hệ thống phát hiện gian lận giao dịch thẻ theo thời gian thực. Với mỗi giao dịch, hệ thống trả về **CHO_QUA / XEM_XET / CHAN** trong vài chục mili-giây.

Đề bài đầy đủ: [docs/realtime-fraud-detection.md](docs/realtime-fraud-detection.md)

> Dự án đang được xây dựng theo từng phase. README sẽ được hoàn thiện ở phase cuối.

## Công nghệ

Java 21 · Spring Boot 3.5 · Apache Kafka (KRaft) + Kafka Streams · Redis · ONNX Runtime · Python (scikit-learn) · Docker Compose · Gatling

## Tiến độ

- [x] Phase 0: Khởi tạo repo & hạ tầng (Kafka, Redis, Kafka UI)
- [x] Phase 1: `common` + `tx-simulator` backend
- [x] Phase 2: `feature-service` (Kafka Streams, 2/5 chỉ số)
- [x] Phase 3: Đủ 5/5 chỉ số
- [x] Phase 4: `scoring-service` (rules.yaml) + `decision-api`
- [x] Phase 5: Train model (Kaggle) + tích hợp ONNX
- [x] Phase 6: Simulator UI
- [ ] Phase 7: Dashboard
- [ ] Phase 8: Docker hoá toàn bộ
- [ ] Phase 9: Load test Gatling
- [ ] Phase 10: README hoàn chỉnh

## Chạy hạ tầng (hiện tại)

Yêu cầu: Docker Desktop, JDK 21+.

```bash
docker compose up -d        # Kafka :9092, Redis :6380, Kafka UI :8090
./mvnw verify               # build + test (Windows: mvnw.cmd verify)
```

| Thành phần | Địa chỉ |
|---|---|
| Kafka (từ máy host) | `localhost:9092` |
| Kafka (giữa các container) | `kafka:29092` |
| Redis (từ máy host) | `localhost:6380` (trong container vẫn là 6379) |
| Kafka UI | http://localhost:8090 |

## Simulator UI (:8080)

Trang web thao tác: gửi giao dịch thủ công, chạy 3 kịch bản gian lận, bật chế độ tự động, và xem Live Feed cập nhật real-time qua WebSocket.

Cần chạy **đủ 4 service** (mỗi service một terminal), rồi mở http://localhost:8080:

```powershell
.\mvnw.cmd install -DskipTests            # lần đầu, hoặc sau khi sửa module common
.\mvnw.cmd -pl feature-service spring-boot:run
.\mvnw.cmd -pl scoring-service spring-boot:run
.\mvnw.cmd -pl decision-api spring-boot:run
.\mvnw.cmd -pl tx-simulator spring-boot:run
```

Mọi giao dịch của simulator đều đi qua `POST /check-transaction` của decision-api. decision-api ghi giao dịch vào Kafka **sau khi** chấm điểm, nên simulator không cần nói chuyện trực tiếp với Kafka.

| Khu vực | Hoạt động |
|---|---|
| Chế độ tự động | 1–500 giao dịch/giây trên **50.000 thẻ nền** (`bg-00001`…`bg-50000`), trong đó ~2% cố tình bất thường (số tiền gấp 10–30 lần, hoặc thanh toán ở nước ngoài) |
| Gửi thủ công | 20 thẻ demo `card-0001`…`card-0020`, trả về quyết định + 5 chỉ số + latency |
| Kịch bản | Quẹt dồn dập (10 giao dịch/10 giây → từ giao dịch 6 bị CHAN), Impossible travel (Hà Nội → TP.HCM sau 2 phút → giao dịch 2 bị CHAN), Chi tiêu bất thường (gấp 20 lần trung bình → XEM_XET/CHAN) |
| Nhật ký thời gian thực | 100 giao dịch mới nhất, tô màu theo quyết định; có nút tạm dừng và lọc bỏ CHO_QUA |

**Vì sao Auto Mode không dùng 20 thẻ như đề bài?** Rule `so_giao_dich_5_phut > 5` chỉ cho mỗi thẻ khoảng 1 giao dịch/phút. Với 20 thẻ, chỉ cần khoảng 0,3 giao dịch/giây là thẻ nào cũng bị CHAN, và dashboard sẽ đỏ 100%. Với 50.000 thẻ, ở 500 giao dịch/giây mỗi thẻ chỉ có khoảng 3 giao dịch mỗi 5 phút, giống hành vi người dùng thật. 20 thẻ demo được giữ riêng cho thao tác tay và kịch bản, nên không bị traffic tự động làm nhiễu.

### API (không qua UI)

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/simulator/manual-transaction -ContentType "application/json" -Body '{"cardId":"card-0007","amount":25000000,"merchant":"ATM","city":"HA_NOI"}'

# chạy kịch bản; ?wait=true đợi chạy xong rồi trả về báo cáo đầy đủ
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/simulator/scenario/rapid-fire?wait=true"
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/simulator/scenario/impossible-travel?wait=true"
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/simulator/scenario/unusual-amount?wait=true"

Invoke-RestMethod -Method Post -Uri http://localhost:8080/simulator/auto-mode -ContentType "application/json" -Body '{"enabled":true,"ratePerSecond":50}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/simulator/auto-mode -ContentType "application/json" -Body '{"enabled":false}'

Invoke-RestMethod http://localhost:8080/simulator/stats
```

WebSocket (STOMP): `ws://localhost:8080/ws/live-feed`, gồm topic `/topic/live-feed` (lô giao dịch mỗi 250 ms) và `/topic/scenario` (tiến trình kịch bản).

## Chạy feature-service (:8084)

Kafka Streams đọc topic `transactions`, gom giao dịch của từng thẻ vào các ô 10 giây (giữ 1 giờ gần nhất) và ghi trạng thái mỗi thẻ vào Redis.

```bash
./mvnw -pl feature-service spring-boot:run

curl localhost:8084/features/card-0005                       # so_giao_dich_5_phut, tong_tien_1_gio
docker exec redis redis-cli HGETALL card:card-0005:state      # dữ liệu thô trong Redis
```

PowerShell: `Invoke-RestMethod http://localhost:8084/features/card-0005` (lệnh `docker exec ...` giữ nguyên).

### 5 chỉ số

Công thức nằm trong [FeatureCalculator](common/src/main/java/com/frauddetection/common/FeatureCalculator.java), dùng chung cho feature-service và scoring-service.

| Chỉ số | Cách tính |
|---|---|
| `so_giao_dich_5_phut` | số giao dịch trong 5 phút trước + giao dịch hiện tại |
| `tong_tien_1_gio` | tổng tiền trong 1 giờ trước + giao dịch hiện tại |
| `trung_binh_lich_su` | trung bình 30 ngày, được `HistorySeeder` nạp vào `card:{id}:avg` lúc khởi động |
| `lech_so_voi_trung_binh` | `(amount - avg) / avg`, ví dụ gấp 20 lần trung bình → `19.0` |
| `khoang_cach_bat_thuong` | Haversine(vị trí trước, vị trí hiện tại) / thời gian > 900 km/h, chỉ xét khi cách nhau ≥ 50 km |

Xem trước 5 chỉ số của một giao dịch giả định (không lưu gì cả):

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8084/features/preview -ContentType "application/json" -Body '{"cardId":"card-0007","amount":26000000,"city":"HO_CHI_MINH"}'
```

State cục bộ (RocksDB) nằm ở `~/.fraud-detection/kafka-streams`. Xoá thư mục này cũng không mất dữ liệu: khi khởi động lại, Kafka Streams tự dựng lại state từ changelog topic `feature-service-card-state-store-changelog`.

## Chạy scoring-service (:8083) + decision-api (:8082)

```
POST /check-transaction ─> decision-api ─> scoring-service ─> Redis (chỉ số)
                               │               └─> rules.yaml → nếu không rule nào khớp → model → ml_thresholds
                               └─> Kafka: "transactions" (để feature-service học) + "decisions" (cho dashboard)
```

decision-api ghi giao dịch vào Kafka **sau khi** chấm điểm, nên dù client nào gọi (UI, curl, Gatling) thì mọi giao dịch cũng được đếm cho các lần sau. Nếu scoring-service không trả lời trong 1 giây, quyết định mặc định là `XEM_XET` với `triggeredRule = "scoring_unavailable"`.

Cần chạy cùng feature-service (4 terminal: feature-service, scoring-service, decision-api, và một terminal để gọi API):

```powershell
.\mvnw.cmd -pl feature-service spring-boot:run
.\mvnw.cmd -pl scoring-service spring-boot:run
.\mvnw.cmd -pl decision-api spring-boot:run
```

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8082/check-transaction -ContentType "application/json" -Body '{"transactionId":"tx-000123","cardId":"card-0007","amount":25000000,"merchant":"ATM","location":{"lat":21.0285,"lon":105.8542}}'

Invoke-RestMethod http://localhost:8083/admin/rules                  # rules đang có hiệu lực
Invoke-RestMethod -Method Post -Uri http://localhost:8083/admin/reload-rules   # reload ngay, không cần đợi
```

### rules.yaml

[config/rules.yaml](config/rules.yaml) được đọc lại tự động trong vòng 5 giây sau khi lưu, không cần build hay restart. Rule được áp từ trên xuống, rule đầu tiên khớp sẽ quyết định. Nếu file bị sửa sai (lỗi YAML, sai tên biến), scoring-service ghi log lỗi và **giữ nguyên rules cũ**.

## Model ML (ONNX)

Nếu không rule nào khớp, scoring-service chấm điểm bằng model trong [model-training/model.onnx](model-training/model.onnx). Model là Gradient Boosting được train bằng Python rồi chạy trong Java qua ONNX Runtime, mỗi lần chấm dưới 1ms. Điểm lớn hơn `xem_xet_neu_diem_tren` thì ra XEM_XET, lớn hơn `chan_neu_diem_tren` thì ra CHAN.

Cách train lại và lý do phải sinh dữ liệu theo 5 chỉ số thay vì dùng thẳng Kaggle: xem [model-training/README.md](model-training/README.md). Kết quả đánh giá: [model-training/metrics.md](model-training/metrics.md).

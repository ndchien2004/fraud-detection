# Load test: `POST /check-transaction`

Đo hiệu năng thật của hệ thống bằng [Gatling](https://gatling.io). Traffic được bắn thẳng vào decision-api, không qua giao diện.

## Tóm tắt

| | Kết quả |
|---|---|
| **Tải bền vững** | **1.500 req/s**, 0 lỗi, p50 5 ms, **p99 22 ms** |
| Điểm gãy | 2.000 req/s: vẫn 0 lỗi nhưng p99 tăng vọt lên 520 ms (bắt đầu xếp hàng) |
| Quá tải | 3.000 req/s: 44% request lỗi kết nối, p99 16,5 s. Không container nào khởi động lại, tự hồi phục khi hết tải |
| Nút thắt | CPU của **decision-api** (~4,7 nhân ở 2.000 req/s), cùng với việc Gatling chạy chung máy |
| Kafka | Chưa phải nút thắt: đo riêng đạt 67.800 msg/s, trong khi 2.000 req/s chỉ cần ~4.000 msg/s |
| Streaming | Sau mọi mức tải, feature-service và dashboard đều không tồn đọng message (lag = 0) |

## Môi trường

| | |
|---|---|
| Máy | Laptop Intel Core i7-12700H (14 nhân / 20 luồng), RAM 16 GB, Windows 11 Pro |
| Docker | Docker Desktop 29.8 (WSL2), được cấp 20 CPU và 8 GB RAM |
| Hệ thống | Toàn bộ `docker compose up` (Kafka, Redis, 5 service Java), cấu hình mặc định của repo |
| Công cụ tải | Gatling 3.15.1, **chạy trên cùng máy**, nên tranh CPU với chính hệ thống được đo |
| Ngày đo | 25/09/2026 |

Vì mọi thứ chạy chung một laptop, các con số là **cận dưới**. Trên máy chủ thật, với công cụ tải đặt ở máy khác, hệ thống sẽ chịu được nhiều hơn.

## Kịch bản

[CheckTransactionSimulation.java](load-test/src/test/java/com/frauddetection/loadtest/CheckTransactionSimulation.java)

- **Mô hình mở (open model):** request mới đến đúng tốc độ mục tiêu dù các request trước đã được trả lời hay chưa, giống thanh toán thẻ ngoài đời. Mô hình đóng ("N người dùng, mỗi người chờ kết quả rồi mới gửi tiếp") sẽ tự chậm lại theo hệ thống và che mất điểm bão hoà.
- **Dữ liệu:** thẻ ngẫu nhiên trong 50.000 thẻ nền (`bg-*`, đều đã có trung bình lịch sử), số tiền 20.000–3.000.000 đ, 5 thành phố Việt Nam. Như vậy rule chỉ bắt đúng phần giao dịch bất thường, thay vì request nào cũng dính rule "quẹt dồn dập".
- **Mỗi mức tải:** 10 giây tăng dần từ 10% lên 100% tốc độ, rồi giữ 30 giây.
- **Latency đo phía client:** tính trọn một vòng HTTP, gồm decision-api, gọi scoring-service, đọc Redis, chạy rules và model ONNX, gửi Kafka (không đồng bộ).
- **Đạt yêu cầu khi:** HTTP 200 và có trường `decision` trong JSON.

## Kết quả

| mục tiêu req/s | đạt được req/s* | số request | lỗi | p50 ms | p95 ms | p99 ms | max ms | CPU máy (đỉnh) | decision-api | scoring | feature | kafka |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 89 | 3.550 | 0% | 4 | 9 | 61** | 239 | 38% | 71% | 31% | 36% | 203% |
| 250 | 222 | 8.875 | 0% | 4 | 7 | 15 | 37 | 40% | 62% | 34% | 22% | 252% |
| 500 | 433 | 17.750 | 0% | 4 | 7 | 12 | 26 | 46% | 92% | 42% | 43% | 198% |
| 750 | 649 | 26.625 | 0% | 4 | 6 | 9 | 25 | 53% | 138% | 59% | 59% | 210% |
| 1.000 | 866 | 35.500 | 0% | 5 | 7 | 11 | 30 | 47% | 189% | 70% | 57% | 195% |
| **1.500** | **1.299** | 53.250 | **0%** | **5** | **11** | **22** | 77 | 57% | 228% | 106% | 65% | 156% |
| 2.000 | 1.732 | 71.000 | 0% | 6 | 269 | 520 | 1.252 | 77% | 472% | 256% | 97% | 231% |
| 3.000 | 2.266 | 106.500 | 44,1% | 1.980 | 8.182 | 16.536 | 27.116 | 95% | 2850%*** | 810% | 390% | 562% |
| 4.000 | 2.029 | 142.000 | 76,1% | 8.196 | 23.102 | 33.530 | 50.732 | 93% | 436% | 188% | 96% | 284% |

\* Trung bình trên cả lượt chạy, **kể cả 10 giây tăng tải**. Với hình dạng tải này, 88,75% mục tiêu nghĩa là trong 30 giây giữ tải hệ thống đạt **đúng** tốc độ yêu cầu. Từ 2.000 req/s trở đi thì không còn đạt được.
\*\* Lượt đầu tiên sau khi container khởi động: JVM chưa "nóng" (xem phân tích bên dưới).
\*\*\* CPU container tính theo % của **một nhân**: 100% = 1 nhân bận hoàn toàn. Giá trị này là **đỉnh** lấy mẫu vài giây một lần. Con số 2850% là một mẫu lệch lúc Docker Desktop quá tải, nên chỉ xem như "rất cao".

## Phân tích

### 1. Từ 250 đến 1.500 req/s: tăng tải mà latency gần như không đổi
p50 giữ ở 4–5 ms, p99 ở 9–22 ms. Mỗi request mất vài mili-giây, nên thread pool của Tomcat (200 luồng) và pool kết nối tới scoring còn rất dư. CPU tăng gần như tỉ lệ thuận với tải.

### 2. Lượt đầu chậm hơn: JVM "khởi động nóng"
Mức 100 req/s có p99 = 61 ms, cao hơn cả mức 1.500. Đây là lượt chạy đầu tiên sau khi build lại container. Java lúc đầu chạy bytecode ở chế độ thông dịch, rồi trình biên dịch JIT dần dịch các đoạn code "nóng" sang mã máy tối ưu. Ở hệ thống thật, người ta **"làm nóng" (warm up)** service bằng traffic giả trước khi nhận traffic thật, hoặc dùng các kỹ thuật như CDS/CRaC.

### 3. Điểm gãy ở 2.000 req/s: hàng đợi
Lỗi vẫn là 0%, p50 chỉ 6 ms, nhưng p95 và p99 nhảy lên 269 ms và 520 ms. Đây là dấu hiệu kinh điển của **hệ thống đã chạm trần**:
- phần lớn request vẫn nhanh, nhưng một phần phải **chờ trong hàng đợi**;
- decision-api dùng khoảng 4,7 nhân, scoring khoảng 2,6 nhân, CPU của máy 77%, trong khi Gatling cũng đang dùng chung CPU.

Đây là lý do **phải nhìn p99 chứ không nhìn trung bình**: trung bình ở mức này vẫn "đẹp", nhưng cứ 100 khách thì có 1 người phải chờ quá nửa giây.

### 4. Quá tải ở 3.000 req/s: lỗi kết nối, không phải lỗi logic
Các lỗi ở mức 3.000 req/s đều là lỗi mạng:

| Lỗi | % | Nghĩa là |
|---|---|---|
| `Connection refused` | 53% | Hàng chờ kết nối của Tomcat đã đầy, kết nối mới bị từ chối |
| `Premature close` | 33% | Kết nối bị đóng giữa chừng khi server quá tải |
| `No buffer space available` | 14% | Windows **hết cổng kết nối**: khi phản hồi chậm, Gatling liên tục mở kết nối mới |

Điểm tốt: **không container nào khởi động lại hay hết bộ nhớ**. Khi hết tải, hệ thống tự trở về healthy, và feature-service cùng dashboard đọc hết phần message tồn đọng (lag = 0).

### 5. Kafka không phải nút thắt
Đo riêng Kafka bằng `kafka-producer-perf-test` (500.000 message × 300 byte, `acks=all`): **67.800 msg/s (19,4 MB/s)**. Mỗi giao dịch ghi 2 message (`transactions` + `decisions`), nên 2.000 req/s chỉ cần khoảng 4.000 msg/s, tức chưa tới 6% sức chứa.

### 6. Bài học từ Phase 6: thiết lập mặc định của thư viện
Trước khi load test, trong lúc thử Auto Mode ở Phase 6, hệ thống chỉ đạt khoảng 230 tx/s. Nguyên nhân là ONNX Runtime mặc định chia mỗi lần chạy model ra 20 luồng **quay vòng chờ việc (spin)**, đốt hết CPU. Chuyển sang 1 luồng, không quay vòng, thì đạt được hơn 500 tx/s ([OnnxRiskModel.java](scoring-service/src/main/java/com/frauddetection/scoring/model/OnnxRiskModel.java)). Không có bảng số liệu ở trên nếu không sửa điểm này.

## Hướng cải thiện (chưa làm)

| Ý tưởng | Lợi ích dự kiến |
|---|---|
| Đặt công cụ tải trên máy khác | Đo đúng sức chịu của hệ thống, không bị Gatling giành CPU |
| **Chạy nhiều instance** decision-api và scoring-service sau một load balancer | Hai service này không giữ state, nên thêm máy là tăng throughput gần như tuyến tính |
| Gộp scoring vào decision-api (gọi hàm trực tiếp thay vì qua HTTP) | Bớt một vòng HTTP + JSON cho mỗi request, giảm CPU và latency |
| Trả lỗi 503 nhanh khi quá tải (giới hạn tốc độ, backpressure) | Giữ p99 thấp cho phần traffic được nhận, thay vì để mọi request cùng chậm |
| Gộp 2 lần đọc Redis (state + trung bình) thành 1 lần | Bớt một vòng mạng mỗi request |
| Làm nóng JVM trước khi nhận traffic | Bỏ đỉnh latency ở những giây đầu |

## Chạy lại

Yêu cầu: hệ thống đang chạy (`docker compose up -d --wait`), JDK 21+.

```powershell
# một mức tải, có báo cáo HTML đầy đủ trong load-test\target\gatling\
.\mvnw.cmd -Pload-test -pl load-test gatling:test "-Drate=500" "-DholdSeconds=30"

# nhiều mức liên tiếp, in ra bảng như trên (kèm CPU từng container)
.\load-test\run-steps.ps1 -Rates 100,250,500,750,1000,1500,2000

# riêng Kafka
docker exec kafka /opt/kafka/bin/kafka-producer-perf-test.sh --topic perf-test --num-records 500000 --record-size 300 --throughput -1 --producer-props bootstrap.servers=localhost:9092 acks=all linger.ms=5
```

Module `load-test` nằm trong Maven profile `load-test`, nên không ảnh hưởng tới build thường hay tới Docker image. Module này ghi đè Netty lên 4.2 vì Gatling 3.15 cần bản đó, còn các service vẫn dùng bản của Spring Boot.

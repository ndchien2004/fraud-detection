# Load test: `POST /check-transaction`

Đo hiệu năng thật của hệ thống bằng [Gatling](https://gatling.io). Traffic được bắn thẳng vào decision-api, không qua giao diện.

## Tóm tắt

| | Kết quả |
|---|---|
| **Không lỗi** | từ 100 tới **2.500 req/s**: 0 request lỗi, luôn đạt đủ tốc độ yêu cầu |
| **Latency tốt** | tới **1.000 req/s**: p50 4–5 ms, **p99 ≤ 15 ms** |
| 1.500–2.000 req/s | p99 50–81 ms: bắt đầu có request phải chờ |
| Điểm gãy | 2.500 req/s: vẫn 0 lỗi nhưng p99 387 ms, CPU máy 87% |
| Quá tải | 3.000 req/s: 55% request lỗi kết nối. Không container nào khởi động lại, tự hồi phục khi hết tải |
| Nút thắt | CPU của **decision-api** (~4,5 nhân), cộng việc Gatling chạy chung máy |
| Kafka | Chưa phải nút thắt: đo riêng đạt 67.800 msg/s, trong khi 2.500 req/s chỉ cần ~5.000 msg/s |
| Streaming | Sau mọi mức tải, feature-service và dashboard không tồn đọng message (lag = 0) |

## Môi trường

| | |
|---|---|
| Máy | Laptop Intel Core i7-12700H (14 nhân / 20 luồng), RAM 16 GB, Windows 11 Pro |
| Docker | Docker Desktop 29.8 (WSL2), được cấp 20 CPU và 8 GB RAM |
| Hệ thống | Toàn bộ `docker compose up` (Kafka, Redis, 5 service Java), cấu hình mặc định của repo |
| Công cụ tải | Gatling 3.15.1, **chạy trên cùng máy**, nên tranh CPU với chính hệ thống được đo |
| Ngày đo | 25/09/2026, hệ thống vừa `docker compose down -v` rồi dựng lại (dữ liệu sạch) |

Vì mọi thứ chạy chung một laptop, các con số là **cận dưới**. Trên máy chủ thật, với công cụ tải đặt ở máy khác, hệ thống sẽ chịu được nhiều hơn.

## Kịch bản

[CheckTransactionSimulation.java](load-test/src/test/java/com/frauddetection/loadtest/CheckTransactionSimulation.java)

- **Mô hình mở (open model):** request mới đến đúng tốc độ mục tiêu dù các request trước đã được trả lời hay chưa, giống thanh toán thẻ ngoài đời. Mô hình đóng ("N người dùng, mỗi người chờ kết quả rồi mới gửi tiếp") sẽ tự chậm lại theo hệ thống và che mất điểm bão hoà.
- **Dữ liệu:** giao dịch bình thường của thẻ ngẫu nhiên trong 50.000 thẻ nền (`bg-*`, đều có trung bình lịch sử): ở **thành phố nhà** của thẻ, số tiền quanh mức chi tiêu thường ngày, giống Auto Mode của simulator. Vì vậy gần như mọi request đi **trọn đường**: đọc Redis, tính 5 chỉ số, rules, rồi model ONNX.
  Lưu ý: với 50.000 thẻ, từ khoảng **800 req/s** trở lên, mỗi thẻ trung bình có hơn 5 giao dịch trong 5 phút, nên rule `qua_nhieu_giao_dich` bắt đầu chặn một phần request **trước khi** tới model (đường chạy nhẹ hơn). Ngân hàng thật có hàng triệu thẻ nên không gặp hiện tượng này. Muốn đo mức cao "sạch" hơn thì phải tăng số thẻ nền.
- **Mỗi mức tải:** 10 giây tăng dần từ 10% lên 100% tốc độ, rồi giữ 30 giây.
- **Latency đo phía client:** tính trọn một vòng HTTP, gồm decision-api, gọi scoring-service, đọc Redis, chạy rules và model ONNX, gửi Kafka (không đồng bộ).
- **Đạt yêu cầu khi:** HTTP 200 và có trường `decision` trong JSON.

## Kết quả

| mục tiêu req/s | đạt được req/s* | số request | lỗi | p50 ms | p95 ms | p99 ms | max ms | CPU máy (đỉnh) | decision-api | scoring | feature | kafka |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 89 | 3.550 | 0% | 5 | 12 | 21** | 501 | 53% | 201% | 75% | 61% | 222% |
| 250 | 222 | 8.875 | 0% | 4 | 8 | 15 | 39 | 42% | 178% | 81% | 38% | 183% |
| 500 | 433 | 17.750 | 0% | 4 | 6 | 9 | 32 | 46% | 106% | 48% | 24% | 130% |
| 750 | 666 | 26.625 | 0% | 4 | 6 | 8 | 24 | 40% | 118% | 53% | 36% | 211% |
| **1.000** | **866** | 35.500 | **0%** | **5** | **8** | **14** | 56 | 54% | 187% | 80% | 62% | 123% |
| 1.500 | 1.299 | 53.250 | 0% | 6 | 16 | 81 | 192 | 63% | 407% | 186% | 109% | 175% |
| 2.000 | 1.732 | 71.000 | 0% | 6 | 22 | 50 | 298 | 78% | 421% | 182% | 89% | 208% |
| 2.500 | 2.165 | 88.750 | 0% | 8 | 179 | 387 | 702 | 87% | 457% | 203% | 224% | 251% |
| 3.000 | 1.439 | 106.500 | 55,0% | 3.918 | 14.909 | 24.496 | 38.984 | 92% | 542% | 163% | 90% | 206% |

\* Trung bình trên cả lượt chạy, **kể cả 10 giây tăng tải**. Với hình dạng tải này, 86–89% mục tiêu nghĩa là trong 30 giây giữ tải hệ thống đạt **đúng** tốc độ yêu cầu. Ở 3.000 req/s thì không còn đạt được.
\*\* Lượt đầu tiên sau khi container khởi động: JVM chưa "nóng" (max 501 ms), xem phân tích bên dưới.
CPU container tính theo % của **một nhân** (100% = 1 nhân bận hoàn toàn), là giá trị **đỉnh** lấy mẫu vài giây một lần.

Mỗi mức chỉ đo một lần 30 giây trên máy dùng chung, nên p99 có dao động. Ví dụ mức 1.500 (81 ms) lại cao hơn mức 2.000 (50 ms), do một đợt dọn rác (GC) hoặc JIT rơi đúng lúc đo. Muốn số liệu chắc hơn, hãy chạy mỗi mức nhiều lần hoặc giữ tải lâu hơn (`-HoldSeconds 120`).

## Phân tích

### 1. Từ 250 đến 1.000 req/s: tăng tải mà latency gần như không đổi
p50 giữ ở 4–5 ms, p99 ở 8–15 ms. Mỗi request mất vài mili-giây, nên thread pool của Tomcat (200 luồng) và pool kết nối tới scoring còn rất dư. CPU tăng gần như tỉ lệ thuận với tải.

### 2. Lượt đầu chậm hơn: JVM "khởi động nóng"
Mức 100 req/s có max = 501 ms và p99 = 21 ms, cao hơn các mức 250–1.000. Đây là lượt chạy đầu tiên sau khi dựng lại container. Java lúc đầu chạy bytecode ở chế độ thông dịch, rồi trình biên dịch JIT dần dịch các đoạn code "nóng" sang mã máy tối ưu. Ở hệ thống thật, người ta **"làm nóng" (warm up)** service bằng traffic giả trước khi nhận traffic thật, hoặc dùng các kỹ thuật như CDS/CRaC.

### 3. Điểm gãy ở 2.500 req/s: hàng đợi
Lỗi vẫn là 0% và hệ thống vẫn đạt đủ 2.500 req/s, p50 chỉ 8 ms, nhưng p95 và p99 nhảy lên 179 ms và 387 ms. Đây là dấu hiệu kinh điển của **hệ thống đã chạm trần**:
- phần lớn request vẫn nhanh, nhưng một phần phải **chờ trong hàng đợi**;
- decision-api dùng khoảng 4,5 nhân, scoring khoảng 2 nhân, feature-service khoảng 2,2 nhân, CPU của máy 87%, trong khi Gatling cũng đang dùng chung CPU.

Đây là lý do **phải nhìn p99 chứ không nhìn trung bình**: trung bình ở mức này vẫn "đẹp", nhưng cứ 100 khách thì có 1 người phải chờ gần 0,4 giây.

### 4. Quá tải ở 3.000 req/s: lỗi kết nối, không phải lỗi logic
Các lỗi ở mức 3.000 req/s đều là lỗi mạng:

| Lỗi | % | Nghĩa là |
|---|---|---|
| `No buffer space available` | 60% | Windows **hết cổng kết nối**: khi phản hồi chậm, Gatling liên tục mở kết nối mới |
| `Connection refused` | 24% | Hàng chờ kết nối của Tomcat đã đầy, kết nối mới bị từ chối |
| `Premature close` | 16% | Kết nối bị đóng giữa chừng khi server quá tải |
| `connection timed out after 10000 ms` | 0,2% | Không kết nối được trong 10 giây |

Điểm tốt: **không container nào khởi động lại hay hết bộ nhớ**. Khi hết tải, hệ thống tự trở về healthy, và feature-service cùng dashboard đọc hết phần message tồn đọng (lag = 0).

Một tác dụng phụ trên máy Windows: sau đợt quá tải, bộ chuyển tiếp cổng của Docker Desktop (`wslrelay`) bị **kẹt ở nhánh IPv6**. Gọi `http://127.0.0.1:8080` vẫn chạy, còn `http://[::1]:8080` (và `curl localhost`, vì thử IPv6 trước) thì treo. Trình duyệt tự chuyển sang IPv4 nên ít bị ảnh hưởng. Khởi động lại Docker Desktop là hết. Hiện tượng này thuộc về môi trường đo (Docker Desktop trên laptop), không phải hệ thống.

### 5. Kafka không phải nút thắt
Đo riêng Kafka bằng `kafka-producer-perf-test` (500.000 message × 300 byte, `acks=all`): **67.800 msg/s (19,4 MB/s)**. Mỗi giao dịch ghi 2 message (`transactions` + `decisions`), nên 2.500 req/s chỉ cần khoảng 5.000 msg/s, tức chưa tới 8% sức chứa.

### 6. Bài học: dữ liệu test sai cho kết quả sai, và còn làm hỏng hệ thống
Lần đo đầu tiên dùng **thành phố ngẫu nhiên** cho mỗi giao dịch. Hệ quả có hai mặt:
- **Số liệu sai lệch:** thẻ nền trông như vừa "bay" giữa các thành phố, nên rất nhiều request bị rule `di_chuyen_bat_kha_thi` chặn sớm và **bỏ qua bước chạy model**. Hệ thống được đo ở một đường chạy nhẹ hơn thực tế.
- **Làm hỏng dữ liệu sau đó:** load test để lại "vị trí cuối" sai cho hàng chục nghìn thẻ. Sau bài đo, Auto Mode của simulator bị chặn tới 17% giao dịch vì di chuyển bất khả thi, trong khi con số đúng là khoảng 1–2%.

Lỗi này chỉ lộ ra khi chụp ảnh màn hình cho README. Đã sửa: load test giờ dùng thành phố nhà và số tiền quen thuộc của từng thẻ, rồi xoá sạch dữ liệu và **đo lại toàn bộ** (bảng trên). Bài học: **dữ liệu load test phải giống traffic thật**, và phải xoá hoặc cô lập dữ liệu mà bài test để lại.

### 7. Bài học từ Phase 6: thiết lập mặc định của thư viện
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
.\load-test\run-steps.ps1 -Rates 100,250,500,750,1000,1500,2000,2500

# riêng Kafka
docker exec kafka /opt/kafka/bin/kafka-producer-perf-test.sh --topic perf-test --num-records 500000 --record-size 300 --throughput -1 --producer-props bootstrap.servers=localhost:9092 acks=all linger.ms=5
```

Module `load-test` nằm trong Maven profile `load-test`, nên không ảnh hưởng tới build thường hay tới Docker image. Module này ghi đè Netty lên 4.2 vì Gatling 3.15 cần bản đó, còn các service vẫn dùng bản của Spring Boot.

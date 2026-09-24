# Realtime Fraud Detection (Java)

Hệ thống phát hiện gian lận giao dịch thẻ theo thời gian thực. Với mỗi giao dịch, hệ thống trả về **CHO_QUA / XEM_XET / CHAN** trong vài chục mili-giây.

Đề bài đầy đủ: [docs/realtime-fraud-detection.md](docs/realtime-fraud-detection.md)

> Dự án đang được xây dựng theo từng phase. README sẽ được hoàn thiện ở phase cuối.

## Công nghệ

Java 21 · Spring Boot 3.5 · Apache Kafka (KRaft) + Kafka Streams · Redis · ONNX Runtime · Python (scikit-learn) · Docker Compose · Gatling

## Tiến độ

- [x] Phase 0: Khởi tạo repo & hạ tầng (Kafka, Redis, Kafka UI)
- [ ] Phase 1: `common` + `tx-simulator` backend
- [ ] Phase 2: `feature-service` (Kafka Streams, 2/5 chỉ số)
- [ ] Phase 3: Đủ 5/5 chỉ số
- [ ] Phase 4: `scoring-service` (rules.yaml) + `decision-api`
- [ ] Phase 5: Train model (Kaggle) + tích hợp ONNX
- [ ] Phase 6: Simulator UI
- [ ] Phase 7: Dashboard
- [ ] Phase 8: Docker hoá toàn bộ
- [ ] Phase 9: Load test Gatling
- [ ] Phase 10: README hoàn chỉnh

## Chạy hạ tầng (hiện tại)

Yêu cầu: Docker Desktop, JDK 21+.

```bash
docker compose up -d        # Kafka :9092, Redis :6379, Kafka UI :8090
./mvnw verify               # build + test (Windows: mvnw.cmd verify)
```

| Thành phần | Địa chỉ |
|---|---|
| Kafka (từ máy host) | `localhost:9092` |
| Kafka (giữa các container) | `kafka:29092` |
| Redis | `localhost:6379` |
| Kafka UI | http://localhost:8090 |

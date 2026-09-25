# model-training

Train model phát hiện gian lận rồi xuất ra `model.onnx`. scoring-service chạy trực tiếp file này bằng ONNX Runtime trong Java, không cần Python lúc chạy.

## Các file

| File | Vai trò | Commit? |
|---|---|---|
| `explore_kaggle.py` | Bước 1: khám phá dataset Kaggle, train model baseline, xuất thống kê | ✅ |
| `kaggle_stats.json` | Thống kê rút ra từ Kaggle, là đầu vào của `train.py` | ✅ |
| `train.py` | Bước 2: sinh dữ liệu theo 5 chỉ số của hệ thống, train, xuất ONNX | ✅ |
| `model.onnx` | Model đã train (~100 KB), scoring-service đọc file này | ✅ |
| `metrics.md` | Báo cáo đánh giá model | ✅ |
| `data/creditcard.csv` | Dataset Kaggle (~150 MB) | ❌ (.gitignore) |

Vì `kaggle_stats.json` được commit, bạn có thể chạy lại `train.py` mà **không cần** tải dataset. Dataset chỉ cần cho `explore_kaggle.py`.

## Chạy (PowerShell)

```powershell
cd model-training
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt

# Bước 1 (cần data/creditcard.csv từ https://www.kaggle.com/datasets/mlg-ulb/creditcardfraud)
.\.venv\Scripts\python explore_kaggle.py

# Bước 2 (~1 phút): tạo model.onnx + metrics.md
.\.venv\Scripts\python train.py
```

Sau khi train lại, **khởi động lại scoring-service** để nạp model mới. Log sẽ có dòng `Using ONNX model ...`, và `GET http://localhost:8083/admin/rules` sẽ trả về `"model": "onnx:model.onnx"`. Nếu không tìm thấy `model.onnx`, scoring-service tự dùng công thức điểm có trọng số (`WeightedScoreModel`).

## Vì sao không dùng thẳng model train trên Kaggle?

Dataset Kaggle chỉ có `Time`, `Amount`, `V1..V28`. Các cột `V` đã được ẩn danh bằng PCA, không ai biết ý nghĩa của chúng. Hệ thống của mình lúc chấm điểm chỉ có **5 chỉ số** của đề bài, nên model phải được train trên đúng 5 chỉ số đó. Cách làm:

1. **Lấy từ Kaggle những gì dữ liệu thật cho biết:**
   - 40% giao dịch gian lận là số tiền rất nhỏ (≤ 2 EUR), kiểu "quẹt thử" thẻ đánh cắp.
   - Phân phối số tiền của các giao dịch gian lận còn lại, tính theo bội số của mức chi tiêu thông thường.
   - 17% giao dịch bình thường cũng là số tiền nhỏ.
2. **Giả định phần còn lại**, vì Kaggle không có dữ liệu về tần suất hay vị trí. Các giả định được liệt kê trong `ASSUMPTIONS` của `train.py` và trong `metrics.md`.

Hệ quả, cần nói thẳng: model phần lớn **học lại chính các giả định đó**. Đây là thoả hiệp cho mục đích học tập. Ở ngân hàng thật, dữ liệu train là lịch sử giao dịch đã được gắn nhãn gian lận/không gian lận, có sẵn đủ 5 chỉ số.

## Kết quả

Xem [metrics.md](metrics.md). Tóm tắt: PR-AUC khoảng 0.77. Giao dịch bình thường và ly cà phê → CHO_QUA. Gấp 5–20 lần trung bình → CHAN. Nhiều giao dịch nhỏ dồn dập (quẹt thử) → CHAN.

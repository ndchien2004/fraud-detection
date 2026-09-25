"""
Step 2 - train the model that the scoring service actually uses, and export it to ONNX.

Why not the Kaggle model directly? Kaggle's features are V1..V28 (anonymised PCA components):
our system never has those. It has the 5 features of the spec. So we generate a labelled dataset
of those 5 features, borrowing from Kaggle what it can tell us (see kaggle_stats.json):
  - which share of frauds are tiny "card testing" amounts (~40%)
  - how big the other fraud amounts are, as a multiple of normal spending
  - which share of normal purchases are tiny too (~17%)
Everything else is an explicit assumption, listed in ASSUMPTIONS below and in metrics.md.

Run:    .venv/Scripts/python train.py        (needs kaggle_stats.json from explore_kaggle.py)
Output: model.onnx  - input "features" float[N, 5], output "probabilities" float[N, 2]
        metrics.md  - evaluation report
"""
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.metrics import average_precision_score, confusion_matrix, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import train_test_split

HERE = Path(__file__).parent
SEED = 42

# Input order of the ONNX model. The Java OnnxRiskModel must send the features in this order.
FEATURES = ["amount", "so_giao_dich_5_phut", "tong_tien_1_gio", "lech_so_voi_trung_binh", "khoang_cach_bat_thuong"]

N_SAMPLES = 400_000
# Kaggle has 0.17% fraud. We keep 2% in training (i.e. fewer normal rows) so the model sees
# ~8,000 frauds. Side effect: scores are on this enriched scale, which is what the 0.4 / 0.8
# thresholds of rules.yaml are tuned for.
TRAIN_FRAUD_RATE = 0.02

ASSUMPTIONS = {
    "normal spending around the card average (same as tx-simulator)": "amount = avg / 1.083 * exp(N(0, 0.4))",
    "normal transactions in the previous 5 minutes": "Poisson(0.3)",
    "normal transactions earlier in the hour": "Poisson(0.5)",
    "normal impossible travel (GPS/VPN noise)": "0.1%",
    "normal small purchases (coffee, parking): amount": "log-uniform 10,000 - 200,000 VND, whatever the card",
    "card-testing fraud: amount (Kaggle: <= 2 EUR)": "log-uniform 1,000 - 50,000 VND",
    "card-testing fraud: earlier attempts in 5 minutes": "Poisson(2.5)",
    "spend fraud: earlier attempts in 5 minutes": "Poisson(1.0)",
    "fraud impossible travel": "card testing 10%, spend 15%",
    "card averages": "log-uniform between 100,000 and 5,000,000 VND",
}


def generate(stats, rng):
    n_fraud = int(N_SAMPLES * TRAIN_FRAUD_RATE)
    n_normal = N_SAMPLES - n_fraud
    normal = generate_normal(n_normal, stats, rng)
    fraud = generate_fraud(n_fraud, stats, rng)
    X = np.vstack([normal, fraud]).astype(np.float32)
    y = np.concatenate([np.zeros(n_normal, dtype=int), np.ones(n_fraud, dtype=int)])
    return X, y


def card_averages(n, rng):
    return np.exp(rng.uniform(np.log(100_000), np.log(5_000_000), n))


def everyday_amounts(avg, rng):
    return avg / 1.083 * np.exp(rng.normal(0, 0.4, len(avg)))


def log_uniform(low, high, size, rng):
    return np.exp(rng.uniform(np.log(low), np.log(high), size))


def generate_normal(n, stats, rng):
    avg = card_averages(n, rng)
    amount = everyday_amounts(avg, rng)
    # small purchases are small in absolute terms: a coffee costs the same for every card holder
    small = rng.random(n) < stats["tiny_amount_share_normal"]
    amount[small] = log_uniform(10_000, 200_000, small.sum(), rng)

    earlier_5min = rng.poisson(0.3, n)
    earlier_hour = earlier_5min + rng.poisson(0.5, n)
    hour_total = amount + sum_of_amounts(earlier_hour, lambda k: everyday_amounts(np.repeat(avg, k), rng))
    travel = rng.random(n) < 0.001
    return features(amount, 1 + earlier_5min, hour_total, avg, travel)


def generate_fraud(n, stats, rng):
    avg = card_averages(n, rng)
    testing = rng.random(n) < stats["tiny_amount_share_fraud"]
    ratios = np.array(stats["fraud_spend_ratio_quantiles"])

    def spend_ratio(size):
        # sample from Kaggle's empirical distribution (inverse transform on its quantiles)
        return np.interp(rng.random(size), np.linspace(0, 1, len(ratios)), ratios)

    def fraud_amounts(is_testing, card_avg):
        # card testing: tiny absolute amounts; spend fraud: Kaggle's multiples of normal spending
        return np.where(is_testing, log_uniform(1_000, 50_000, len(card_avg), rng),
                        card_avg * spend_ratio(len(card_avg)))

    amount = fraud_amounts(testing, avg)
    earlier_5min = np.where(testing, rng.poisson(2.5, n), rng.poisson(1.0, n))
    earlier_hour = earlier_5min + rng.poisson(0.5, n)
    hour_total = amount + sum_of_amounts(
        earlier_hour, lambda k: fraud_amounts(np.repeat(testing, k), np.repeat(avg, k)))
    travel = rng.random(n) < np.where(testing, 0.10, 0.15)
    return features(amount, 1 + earlier_5min, hour_total, avg, travel)


def sum_of_amounts(counts, draw):
    """Total of `counts[i]` earlier transactions for each row i."""
    amounts = draw(counts)
    row = np.repeat(np.arange(len(counts)), counts)
    return np.bincount(row, weights=amounts, minlength=len(counts))


def features(amount, count_5min, hour_total, avg, travel):
    amount = np.maximum(np.round(amount / 1000) * 1000, 1000)
    deviation = (amount - avg) / avg
    return np.column_stack([amount, count_5min, hour_total, deviation, travel.astype(float)])


def evaluate(y, proba, threshold):
    pred = (proba > threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y, pred).ravel()
    return {
        "threshold": threshold,
        "precision": precision_score(y, pred, zero_division=0),
        "recall": recall_score(y, pred),
        "tp": int(tp), "fn": int(fn), "fp": int(fp), "tn": int(tn),
    }


def scenario(amount, count, hour_total, avg=1_000_000, travel=0):
    return [amount, count, hour_total, (amount - avg) / avg, travel]


SCENARIOS = {
    "normal purchase (1x average)": scenario(1_000_000, 1, 1_000_000),
    "coffee (5% of average)": scenario(50_000, 1, 50_000),
    "20k coffee on a 3.2M-average card": scenario(20_000, 1, 20_000, avg=3_200_000),
    "3x average": scenario(3_000_000, 1, 3_000_000),
    "5x average": scenario(5_000_000, 1, 5_000_000),
    "20x average (unusual-amount scenario)": scenario(20_000_000, 1, 20_000_000),
    "card testing: 3 tiny payments in 5 min": scenario(15_000, 3, 45_000),
    "4 normal payments in 5 min": scenario(1_000_000, 4, 4_000_000),
}


def main():
    stats = json.loads((HERE / "kaggle_stats.json").read_text())
    rng = np.random.default_rng(SEED)

    X, y = generate(stats, rng)
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.25, stratify=y, random_state=SEED)
    print(f"Generated {len(X):,} rows ({y.sum():,} frauds = {y.mean():.1%}), features: {FEATURES}")

    # 200 shallow trees, each fitted on a random half of the rows (subsample): fast and robust.
    # min_samples_leaf=50 stops leaves from memorising a handful of frauds (see explore_kaggle.py).
    model = GradientBoostingClassifier(n_estimators=200, max_depth=3, learning_rate=0.1, subsample=0.5,
                                       min_samples_leaf=50, random_state=SEED)
    model.fit(X_train, y_train)
    proba = model.predict_proba(X_test)[:, 1]
    pr_auc = average_precision_score(y_test, proba)
    roc_auc = roc_auc_score(y_test, proba)
    print(f"Test PR-AUC {pr_auc:.4f} | ROC-AUC {roc_auc:.4f}")
    at = [evaluate(y_test, proba, t) for t in (0.4, 0.8)]
    for e in at:
        print(f"  score > {e['threshold']}: precision {e['precision']:.3f}, recall {e['recall']:.3f}, "
              f"TP={e['tp']} FN={e['fn']} FP={e['fp']} TN={e['tn']}")

    # --- export to ONNX: zipmap=False gives a plain float[N, 2] probability tensor, easy to read from Java
    onnx_model = convert_sklearn(model, initial_types=[("features", FloatTensorType([None, len(FEATURES)]))],
                                 options={id(model): {"zipmap": False}}, target_opset=15)
    onnx_path = HERE / "model.onnx"
    onnx_path.write_bytes(onnx_model.SerializeToString())

    # --- check the ONNX file gives the same answers as scikit-learn
    session = ort.InferenceSession(str(onnx_path))
    outputs = [o.name for o in session.get_outputs()]
    onnx_proba = session.run(["probabilities"], {"features": X_test[:5000]})[0][:, 1]
    max_diff = float(np.abs(onnx_proba - proba[:5000]).max())
    print(f"ONNX outputs {outputs}, max difference vs scikit-learn: {max_diff:.2e}")
    assert max_diff < 1e-4, "ONNX export does not match the scikit-learn model"

    print("\nScenario scores (card average 1,000,000 VND):")
    scenario_scores = {}
    for name, row in SCENARIOS.items():
        score = float(session.run(["probabilities"], {"features": np.array([row], dtype=np.float32)})[0][0, 1])
        scenario_scores[name] = score
        decision = "CHAN" if score > 0.8 else "XEM_XET" if score > 0.4 else "CHO_QUA"
        print(f"  {name:42s} {score:.3f}  -> {decision}")

    write_report(stats, len(X), int(y.sum()), pr_auc, roc_auc, at, max_diff, scenario_scores, onnx_path)


def write_report(stats, n, n_fraud, pr_auc, roc_auc, at, max_diff, scenario_scores, onnx_path):
    lines = [
        "# Model metrics",
        "",
        "Generated by `train.py`. Model: scikit-learn `GradientBoostingClassifier`, exported to `model.onnx`.",
        "",
        "## Input",
        "",
        "`features`: float[N, 5] in this order: " + ", ".join(f"`{f}`" for f in FEATURES) + ".",
        "Output `probabilities`: float[N, 2], column 1 = probability of fraud.",
        "",
        "## Training data",
        "",
        f"{n:,} synthetic rows, {n_fraud:,} frauds ({n_fraud / n:.1%}; Kaggle's real rate is {stats['fraud_rate']:.2%}).",
        "",
        "From Kaggle (`kaggle_stats.json`):",
        "",
        f"- {stats['tiny_amount_share_fraud']:.1%} of frauds are tiny card-testing amounts",
        f"- {stats['tiny_amount_share_normal']:.1%} of normal purchases are small",
        "- other fraud amounts follow Kaggle's distribution of fraud amount / average normal amount",
        "",
        "Assumptions (not in Kaggle, which has no velocity or location data):",
        "",
        *[f"- {k}: {v}" for k, v in ASSUMPTIONS.items()],
        "",
        "## Test results (25% hold-out)",
        "",
        f"- PR-AUC: **{pr_auc:.4f}**, ROC-AUC: {roc_auc:.4f}",
        f"- Kaggle baseline on its own 30 features, for reference: PR-AUC {stats['baseline_pr_auc_gradient_boosting']:.4f}",
        "",
        "| score > | precision | recall | caught (TP) | missed (FN) | false alarms (FP) |",
        "|---|---|---|---|---|---|",
        *[f"| {e['threshold']} | {e['precision']:.3f} | {e['recall']:.3f} | {e['tp']} | {e['fn']} | {e['fp']} |" for e in at],
        "",
        f"ONNX vs scikit-learn max difference: {max_diff:.2e}",
        "",
        "## Scenario scores (card average 1,000,000 VND)",
        "",
        "| scenario | score | decision |",
        "|---|---|---|",
        *[f"| {k} | {v:.3f} | {'CHAN' if v > 0.8 else 'XEM_XET' if v > 0.4 else 'CHO_QUA'} |" for k, v in scenario_scores.items()],
        "",
        "Scores reflect the assumptions above: the model mostly re-learns them. This is a teaching",
        "compromise, since the public Kaggle data cannot be mapped to real-time card features.",
        "",
    ]
    (HERE / "metrics.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"\nWrote {onnx_path.name} ({onnx_path.stat().st_size / 1024:.0f} KB) and metrics.md")


if __name__ == "__main__":
    main()

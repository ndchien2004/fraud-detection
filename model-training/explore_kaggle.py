"""
Step 1 - explore the Kaggle "Credit Card Fraud Detection" dataset and train baseline models.

Goal: learn the workflow on real data (imbalanced classes, stratified split, the right metrics),
and extract a few statistics that train.py reuses to generate data for OUR 5 features.

Run:  .venv/Scripts/python explore_kaggle.py
Input:  data/creditcard.csv   (download from https://www.kaggle.com/datasets/mlg-ulb/creditcardfraud)
Output: kaggle_stats.json     (statistics used by train.py)
"""
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, confusion_matrix, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

HERE = Path(__file__).parent
DATA = HERE / "data" / "creditcard.csv"
SEED = 42


def section(title):
    print(f"\n{'=' * 70}\n{title}\n{'=' * 70}")


def evaluate(name, y_true, proba, threshold=0.5):
    pred = (proba >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_true, pred).ravel()
    print(f"\n--- {name} (threshold {threshold}) ---")
    print(f"PR-AUC    : {average_precision_score(y_true, proba):.4f}   <- the metric that matters for rare fraud")
    print(f"ROC-AUC   : {roc_auc_score(y_true, proba):.4f}   <- looks great on imbalanced data even for weak models")
    print(f"Precision : {precision_score(y_true, pred, zero_division=0):.4f}   (of the transactions we flag, how many are fraud)")
    print(f"Recall    : {recall_score(y_true, pred):.4f}   (of all frauds, how many we catch)")
    print(f"Confusion matrix: caught fraud (TP)={tp}, missed fraud (FN)={fn}, "
          f"false alarms (FP)={fp}, correct normal (TN)={tn}")


def main():
    if not DATA.exists():
        raise SystemExit(f"Missing {DATA} - download creditcard.csv from Kaggle first (see README.md)")

    section("1. Load the data")
    df = pd.read_csv(DATA)
    print(f"{len(df):,} transactions, {df.shape[1]} columns")
    print("Columns: Time, V1..V28 (anonymised with PCA - their meaning is secret), Amount (EUR), Class (1 = fraud)")

    section("2. Class balance - the core difficulty")
    fraud = df[df.Class == 1]
    normal = df[df.Class == 0]
    fraud_rate = len(fraud) / len(df)
    print(f"Fraud : {len(fraud):,} ({fraud_rate:.3%})")
    print(f"Normal: {len(normal):,}")
    print(f"A 'model' that always answers 'not fraud' has accuracy {1 - fraud_rate:.2%} and catches ZERO fraud.")
    print("=> accuracy is useless here; we look at precision, recall and PR-AUC instead.")

    section("3. Amount: fraud vs normal")
    quantiles = [0.1, 0.25, 0.5, 0.75, 0.9, 0.99]
    table = pd.DataFrame({
        "normal": normal.Amount.quantile(quantiles),
        "fraud": fraud.Amount.quantile(quantiles),
    })
    table.index = [f"p{int(q * 100)}" for q in quantiles]
    print(table.round(2).to_string())
    tiny_threshold = 2.0
    tiny_fraud_share = (fraud.Amount <= tiny_threshold).mean()
    tiny_normal_share = (normal.Amount <= tiny_threshold).mean()
    print(f"\nAmounts <= {tiny_threshold} EUR: {tiny_fraud_share:.1%} of frauds vs {tiny_normal_share:.1%} of normal.")
    print("Fraudsters often 'test' a stolen card with a tiny amount before spending big.")

    section("4. Baseline models on the 30 Kaggle features")
    X = df.drop(columns=["Class"])
    y = df.Class
    # stratify=y keeps the same 0.17% fraud rate in train and test
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.25, stratify=y, random_state=SEED)
    print(f"Train: {len(X_train):,} rows ({y_train.sum()} frauds) | Test: {len(X_test):,} rows ({y_test.sum()} frauds)")

    # class_weight="balanced": a missed fraud costs ~580x more than a false alarm during training
    logreg = make_pipeline(StandardScaler(), LogisticRegression(class_weight="balanced", max_iter=1000))
    logreg.fit(X_train, y_train)
    evaluate("Logistic Regression", y_test, logreg.predict_proba(X_test)[:, 1])

    # early_stopping=False: its 10% validation split holds only ~37 frauds, so the validation loss is
    #   too noisy and training stops after ~11 trees (PR-AUC 0.54).
    # min_samples_leaf=50 + l2_regularization: without them, leaves memorise a handful of frauds
    #   (overfitting, PR-AUC 0.38). With them: PR-AUC ~0.86.
    gbm = HistGradientBoostingClassifier(max_iter=300, learning_rate=0.05, early_stopping=False,
                                         min_samples_leaf=50, l2_regularization=1.0, random_state=SEED)
    gbm.fit(X_train, y_train)
    gbm_proba = gbm.predict_proba(X_test)[:, 1]
    evaluate("Gradient Boosting", y_test, gbm_proba)
    evaluate("Gradient Boosting", y_test, gbm_proba, threshold=0.2)
    print("\nLowering the threshold catches more fraud (recall up) at the cost of more false alarms.")
    print("Our system turns that trade-off into two thresholds: XEM_XET (review) and CHAN (block).")

    section("5. Statistics kept for train.py")
    normal_mean = float(normal.Amount.mean())
    spend_fraud = fraud.Amount[fraud.Amount > tiny_threshold]
    stats = {
        "source": "Kaggle mlg-ulb/creditcardfraud",
        "fraud_rate": fraud_rate,
        "tiny_amount_threshold_eur": tiny_threshold,
        "tiny_amount_share_fraud": float(tiny_fraud_share),
        "tiny_amount_share_normal": float(tiny_normal_share),
        # non-tiny fraud amounts as a multiple of the average normal amount ("x times the usual spend")
        "fraud_spend_ratio_quantiles": [
            float(spend_fraud.quantile(q) / normal_mean) for q in np.linspace(0.0, 1.0, 101)
        ],
        "baseline_pr_auc_logistic_regression": float(average_precision_score(y_test, logreg.predict_proba(X_test)[:, 1])),
        "baseline_pr_auc_gradient_boosting": float(average_precision_score(y_test, gbm_proba)),
    }
    out = HERE / "kaggle_stats.json"
    out.write_text(json.dumps(stats, indent=2))
    print(f"Wrote {out.name}: fraud_rate={fraud_rate:.5f}, tiny_amount_share_fraud={tiny_fraud_share:.3f}")


if __name__ == "__main__":
    main()

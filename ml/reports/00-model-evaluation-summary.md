# Model evaluation summary

Every model the application serves, with the governance fields recorded in its model card (src/main/resources/models/*.card.json). Detailed evidence is in the numbered reports in this folder.

| Model | Version | Task | Algorithm | Trained | Dataset version | Features | Headline test metrics | Artifact SHA-256 |
|---|---|---|---|---|---|---|---|---|
| bearing-anomaly-detector | 1.0.0 | Anomaly detection: does this vibration window look unlike a healthy bearing? | Smile IsolationForest, 300 trees, threshold = 99.5th percentile of held-out healthy scores at 0-2 hp | 2026-11-12 | cwru/97_DE.csv@sha256:9407753c18eef371,cwru/98_DE.csv@sha… | 12 | pr_auc 1, roc_auc 1, f1 0.9951, recall 1 | 355bad0b96856550… |
| bearing-fault-classifier | 1.0.0 | Classification: normal, inner race, ball or outer race fault | Smile RandomForest, 100 trees | 2026-11-12 | cwru/97_DE.csv@sha256:9407753c18eef371,cwru/98_DE.csv@sha… | 12 | accuracy 1, macro_f1 1 | 44f8d5f2451abd48… |
| process-failure-classifier | 1.0.0 | Binary classification: will this machining process fail? | Smile GradientTreeBoost, 300 trees, depth ≤ 6, shrinkage 0.05 | 2026-11-12 | ai4i/ai4i2020.csv@sha256:dc6630cd9b1f0f85 | 10 | pr_auc 0.9017, roc_auc 0.9822, f1 0.8661, recall 0.8088 | 4a41506a741271e9… |
| remaining-useful-life-regressor | 1.0.0 | Regression: remaining useful life in cycles (capped at 125) | Smile RandomForest regression, 120 trees, mtry=14 | 2026-11-12 | cmapss/train_FD001.txt@sha256:963b5e22825b34d8,cmapss/tes… | 43 | rmse 18.6828, mae 13.7114, r2 0.7979 | 9435a3eb141e6a32… |

## Selection rules

- Process failure (AI4I): highest validation PR-AUC, because failures are 3.4% of rows.
- Remaining useful life (C-MAPSS): lowest validation RMSE.
- Bearing anomaly detector (CWRU): threshold fixed at the 99th percentile of healthy calibration scores.
- Test sets are scored once after selection and never used to choose a model or threshold.


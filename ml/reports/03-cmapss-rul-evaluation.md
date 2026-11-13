# NASA C-MAPSS FD001: data analysis and RUL evaluation

Source: NASA Prognostics Center of Excellence (Saxena et al., PHM 2008). Generated 2026-11-12T09:39:50.520256Z.

## Data quality

| Check | Result |
|---|---|
| Training engines | 100 |
| Training cycles (rows) | 20631 |
| Missing values | 0 |
| Engine life (cycles): min / median / max | 128 / 199 / 362 |

## Sensor variability

| Sensor | Mean | SD | Decision |
|---|---|---|---|
| s1 | 518.7 | 0 | dropped (near-constant) |
| s2 | 642.7 | 0.5001 | used |
| s3 | 1590.5 | 6.1311 | used |
| s4 | 1408.9 | 9.0006 | used |
| s5 | 14.6200 | 0 | dropped (near-constant) |
| s6 | 21.6098 | 0.0014 | dropped (near-constant) |
| s7 | 553.4 | 0.8851 | used |
| s8 | 2388.1 | 0.0710 | used |
| s9 | 9065.2 | 22.0829 | used |
| s10 | 1.3000 | 0 | dropped (near-constant) |
| s11 | 47.5412 | 0.2671 | used |
| s12 | 521.4 | 0.7376 | used |
| s13 | 2388.1 | 0.0719 | used |
| s14 | 8143.8 | 19.0762 | used |
| s15 | 8.4421 | 0.0375 | used |
| s16 | 0.0300 | 0 | dropped (near-constant) |
| s17 | 393.2 | 1.5488 | used |
| s18 | 2388 | 0 | dropped (near-constant) |
| s19 | 100 | 0 | dropped (near-constant) |
| s20 | 38.8163 | 0.1807 | used |
| s21 | 23.2897 | 0.1083 | used |

Seven sensors are flat in FD001 (one operating condition) and carry no degradation signal. Each remaining sensor contributes its current value, a 10-cycle mean (noise reduction) and a 10-cycle slope (degradation rate), plus the cycle count: 43 features.

## Regression evaluation

Split by engine: 80 engines for training (16453 cycles), 20 engines for validation (4178 cycles). Selection uses validation RMSE. The test set is NASA's held-out FD001 test (100 engines, scored at the last observed cycle against the published true RUL, uncapped).

| Model | Val MAE | Val RMSE | Val R² | Test MAE | Test RMSE | Test R² | Test NASA score (lower is better) |
|---|---|---|---|---|---|---|---|
| Linear regression (baseline) | 14.6081 | 19.2776 | 0.7856 | 17.0172 | 22.0379 | 0.7188 | 1202.7 |
| **Random forest** (selected) | 11.4249 | 15.9462 | 0.8533 | 13.7114 | 18.6828 | 0.7979 | 704.0 |
| Gradient boosting | 11.3526 | 16.3840 | 0.8451 | 13.6906 | 18.4728 | 0.8024 | 760.9 |

The NASA scoring function penalises late predictions (predicting more life than remains) more than early ones, which matches the maintenance cost of a missed failure. For reference, published deep-learning results on FD001 report test RMSE of roughly 12 to 16 cycles.


# AI4I 2020: model comparison and selection

Split: stratified 60/20/20 (6000 train, 2000 validation, 2000 test). Each model's decision threshold maximises F1 on validation. Selection uses validation PR-AUC only; test metrics are reported once for the record.

| Model | Val PR-AUC | Val ROC-AUC | Val F1 | Test precision | Test recall | Test F1 | Test ROC-AUC | Test PR-AUC | Threshold |
|---|---|---|---|---|---|---|---|---|---|
| Logistic regression (baseline) | 0.4016 | 0.9036 | 0.4228 | 0.4909 | 0.3971 | 0.4390 | 0.9451 | 0.4669 | 0.2869 |
| Decision tree | 0.7033 | 0.8434 | 0.7520 | 0.9649 | 0.8088 | 0.8800 | 0.9065 | 0.8334 | 0.3333 |
| Random forest | 0.7578 | 0.9608 | 0.7231 | 0.8814 | 0.7647 | 0.8189 | 0.9861 | 0.8817 | 0.0681 |
| **Gradient boosting** (selected) | 0.8479 | 0.9744 | 0.8167 | 0.9322 | 0.8088 | 0.8661 | 0.9822 | 0.9017 | 0.5844 |

## Confusion matrices (test split)

| Model | TP | FP | TN | FN | False alarm rate |
|---|---|---|---|---|---|
| Logistic regression (baseline) | 27 | 28 | 1904 | 41 | 0.0145 |
| Decision tree | 55 | 2 | 1930 | 13 | 0.0010 |
| Random forest | 52 | 7 | 1925 | 16 | 0.0036 |
| Gradient boosting | 55 | 4 | 1928 | 13 | 0.0021 |

## Feature importance: Random forest

| Feature | Share of impurity reduction |
|---|---|
| strain_min_nm | 0.1899 |
| rpm | 0.1704 |
| power_w | 0.1391 |
| torque_nm | 0.1303 |
| tool_wear_min | 0.0947 |
| air_temp_k | 0.0856 |
| process_temp_k | 0.0638 |
| temp_diff_k | 0.0564 |
| type_l | 0.0508 |
| type_m | 0.0189 |

## Feature importance: Gradient boosting

| Feature | Share of impurity reduction |
|---|---|
| strain_min_nm | 0.3189 |
| rpm | 0.2675 |
| power_w | 0.1839 |
| temp_diff_k | 0.0836 |
| torque_nm | 0.0702 |
| tool_wear_min | 0.0495 |
| process_temp_k | 0.0089 |
| air_temp_k | 0.0066 |
| type_m | 0.0056 |
| type_l | 0.0053 |


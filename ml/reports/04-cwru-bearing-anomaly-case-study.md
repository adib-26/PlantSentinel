# CWRU bearings: anomaly detection case study and fault classification

Source: Case Western Reserve University Bearing Data Center. Drive-end accelerometer, 12 kHz, 0.007" seeded faults, motor loads 0-3 hp (16 recordings). Windows of 1024 samples (85.3333 ms). Generated 2026-11-12T09:40:05.756281Z.

## Data quality

| Class | 0 hp | 1 hp | 2 hp | 3 hp |
|---|---|---|---|---|
| normal | 238 | 472 | 472 | 474 |
| inner_race | 118 | 119 | 119 | 120 |
| ball | 119 | 118 | 118 | 118 |
| outer_race | 119 | 119 | 118 | 119 |

Every recording parsed completely; no missing samples. Healthy recordings are about four times longer than fault recordings.

## Anomaly detection (unsupervised, healthy data only)

Isolation Forest (300 trees) fitted on the first 70% of each healthy recording at 0, 1 and 2 hp (826 windows). Alarm threshold = 99.5th percentile of the held-out 30% of those recordings (356 windows). Test: healthy 3 hp (474 windows, a load never seen) plus all 1424 fault windows.

| Metric | Test |
|---|---|
| precision | 0.9903 |
| recall | 1 |
| f1 | 0.9951 |
| roc_auc | 1 |
| pr_auc | 1 |
| accuracy | 0.9926 |
| false_alarm_rate | 0.0295 |
| tp | 1424 |
| fp | 14 |
| tn | 460 |
| fn | 0 |
| alarm_false_alarm_rate | 0 |
| alarm_fault_recordings_detected | 1 |

Finding: the false alarm rate on the unseen 3 hp load is 2.9536% against a 0.5% calibration target. A change in operating condition alone shifts the healthy signature, so in production the threshold should be calibrated per machine and load, or alarms should require persistence (rule B below).

## Detection delay (3 hp)

Alarm rule A: any single window above threshold. Rule B: 3 consecutive windows above threshold, which suppresses isolated false alarms at the cost of two extra windows.

| Fault | Delay, rule A | Delay, rule B | False alarms before onset, A | False alarms before onset, B |
|---|---|---|---|---|
| inner_race | 1 windows (85.3333 ms) | 3 windows (256 ms) | 14 | 0 |
| ball | 1 windows (85.3333 ms) | 3 windows (256 ms) | 14 | 0 |
| outer_race | 1 windows (85.3333 ms) | 3 windows (256 ms) | 14 | 0 |

## Fault classification (supervised random forest, unseen load)

Trained on 0-1 hp (1422 windows), validated on 2 hp (827), tested on 3 hp (831). Validation accuracy 1, macro F1 1.

| Confusion matrix (test) | predicted normal | predicted inner_race | predicted ball | predicted outer_race |
|---|---|---|---|---|
| actual normal | 474 | 0 | 0 | 0 |
| actual inner_race | 0 | 120 | 0 | 0 |
| actual ball | 0 | 0 | 118 | 0 |
| actual outer_race | 0 | 0 | 0 | 119 |

Test accuracy 1, macro F1 1. Caveat: CWRU uses one test rig and seeded EDM faults, so near-perfect scores here do not transfer directly to naturally developing faults on plant bearings.


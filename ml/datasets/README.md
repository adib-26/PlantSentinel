# Datasets used by the BAS Intelligence ML lab

Run `ml/datasets/fetch_datasets.sh` to download them into `ml/data/raw/` (git-ignored). **Raw data is not
committed.** Each file's SHA-256 checksum is stored in `ml/data/raw/SHA256SUMS` and copied into every model card
as the training dataset version.

| Dataset | Used for | Source | Licence / terms | Redistributed here? |
|---|---|---|---|---|
| AI4I 2020 Predictive Maintenance | Failure classification (steps 1–5, 8–10) | [UCI ML Repository #601](https://archive.ics.uci.edu/dataset/601/ai4i+2020+predictive+maintenance+dataset) | CC BY 4.0. Cite: *AI4I 2020 Predictive Maintenance Dataset* (2020), UCI Machine Learning Repository, https://doi.org/10.24432/C5HS5C | No (download script). Trained model artifacts are shared with attribution. |
| NASA C-MAPSS (FD001) | Remaining useful life regression (step 7) | [NASA PCoE data repository](https://www.nasa.gov/intelligent-systems-division/discovery-and-systems-health/pcoe/pcoe-data-set-repository/) | US government open data. Cite: A. Saxena, K. Goebel, D. Simon, N. Eklund, "Damage Propagation Modeling for Aircraft Engine Run-to-Failure Simulation", PHM 2008 | No |
| CWRU Bearing Data Center | Anomaly detection case study and bearing fault classification (step 6) | [Case Western Reserve University Bearing Data Center](https://engineering.case.edu/bearingdatacenter) | Publicly available for research; no explicit redistribution licence, so only download links are provided. Acknowledge CWRU in publications. | No |
| MVTec AD | Evaluating the vision model on real defects | [MVTec AD](https://www.mvtec.com/company/research/datasets/mvtec-ad) | CC BY-NC-SA 4.0, **non-commercial only**; download requires accepting terms | No. Download manually to `ml/data/raw/mvtec/<category>/` |

Check each provider's current terms before publishing data, models or results.

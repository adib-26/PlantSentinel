#!/usr/bin/env bash
# Runs the offline ML lab: EDA, training, evaluation, model selection and export.
# Heap is capped at 1.5 GB so it fits next to an IDE and browser on an 8 GB laptop. Stop the app first.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ -f ml/data/raw/SHA256SUMS ]] || ml/datasets/fetch_datasets.sh
./mvnw -q -B compile dependency:build-classpath -Dmdep.outputFile=target/ml-classpath.txt
java -Xmx1536m -cp "target/classes:$(cat target/ml-classpath.txt)" com.basintelligence.mllab.MlLab "$ROOT"

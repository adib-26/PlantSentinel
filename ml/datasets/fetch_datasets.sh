#!/usr/bin/env bash
# Downloads the public datasets used by the BAS Intelligence ML lab into ml/data/raw (git-ignored).
# Datasets are NOT committed to the repository; see ml/datasets/README.md for licences and attribution.
# Each file's SHA-256 is written to ml/data/raw/SHA256SUMS. That checksum is the "training dataset version"
# recorded in every model card.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RAW="$ROOT/ml/data/raw"
mkdir -p "$RAW/ai4i" "$RAW/cmapss" "$RAW/cwru"

fetch() { # url dest
  if [[ -s "$2" ]]; then echo "exists  $2"; return; fi
  echo "fetch   $1"
  curl -fSL --retry 3 --max-time 600 -o "$2.part" "$1" && mv "$2.part" "$2"
}

# 1. AI4I 2020 Predictive Maintenance (UCI, CC BY 4.0), 0.5 MB
fetch "https://archive.ics.uci.edu/static/public/601/ai4i+2020+predictive+maintenance+dataset.zip" "$RAW/ai4i/ai4i2020.zip"
unzip -oq "$RAW/ai4i/ai4i2020.zip" -d "$RAW/ai4i"

# 2. NASA C-MAPSS turbofan degradation (NASA Prognostics Center of Excellence), 12 MB
fetch "https://phm-datasets.s3.amazonaws.com/NASA/6.+Turbofan+Engine+Degradation+Simulation+Data+Set.zip" "$RAW/cmapss/cmapss.zip"
if [[ ! -f "$RAW/cmapss/train_FD001.txt" ]]; then
  TMP="$(mktemp -d)"; unzip -oq "$RAW/cmapss/cmapss.zip" -d "$TMP"
  INNER="$(find "$TMP" -iname 'CMAPSSData.zip' | head -1)"
  if [[ -n "$INNER" ]]; then unzip -oq "$INNER" -d "$TMP/inner"; SRC="$TMP/inner"; else SRC="$TMP"; fi
  find "$SRC" -name '*FD00?.txt' -exec cp {} "$RAW/cmapss/" \;
  rm -rf "$TMP"
fi

# 3. CWRU Bearing Data Center, 12 kHz drive-end, 0.007" faults. Loads 0-3 hp:
#    normal 97-100, inner race 105-108, ball 118-121, outer race (6 o'clock) 130-133. About 45 MB.
for n in 97 98 99 100 105 106 107 108 118 119 120 121 130 131 132 133; do
  fetch "https://engineering.case.edu/sites/default/files/$n.mat" "$RAW/cwru/$n.mat"
done
"$ROOT/ml/.venv/bin/python" "$ROOT/ml/datasets/cwru_to_csv.py" "$RAW/cwru"

# 4. MVTec AD (CC BY-NC-SA 4.0) requires accepting the licence via a form, so it is not downloaded here.
#    Download one category (e.g. metal_nut) from https://www.mvtec.com/company/research/datasets/mvtec-ad
#    and extract it to ml/data/raw/mvtec/<category>/ (test/good, test/<defect>, ...).

( cd "$RAW" && find . -type f \( -name '*.csv' -o -name '*.txt' \) ! -name SHA256SUMS | sort | xargs shasum -a 256 > SHA256SUMS )
echo "Done. Dataset checksums: $RAW/SHA256SUMS"

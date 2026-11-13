"""Converts CWRU .mat files to plain CSV signals so the Java ML lab can read them without a MATLAB parser.

Writes <dir>/<file>_DE.csv with one drive-end accelerometer sample (in g) per line, sampled at 12 kHz.
"""
import pathlib
import sys

import numpy as np
from scipy.io import loadmat

LABELS = {**{str(n): "normal" for n in range(97, 101)}, **{str(n): "inner_race" for n in range(105, 109)},
          **{str(n): "ball" for n in range(118, 122)}, **{str(n): "outer_race" for n in range(130, 134)}}

def main(directory: str) -> None:
    d = pathlib.Path(directory)
    for mat in sorted(d.glob("*.mat")):
        out = d / f"{mat.stem}_DE.csv"
        if out.exists():
            continue
        data = loadmat(mat)
        key = next(k for k in data if k.endswith("DE_time"))
        signal = np.asarray(data[key]).ravel()
        np.savetxt(out, signal, fmt="%.6f")
        print(f"{mat.name}: {LABELS.get(mat.stem, '?')}, {signal.size} samples -> {out.name}")

if __name__ == "__main__":
    main(sys.argv[1])

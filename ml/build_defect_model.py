"""
Builds the baseline surface-defect ONNX model used by PlantSentinel.

Contract (any replacement model MUST keep it so the Java side stays unchanged):
    input  "image"              float32 [1, 1, 128, 128]  grayscale, values in [0, 1]
    output "defect_probability" float32 [1, 1]            probability the part is defective

The baseline is a small fixed-weight convolutional network:
    3x3 box blur -> 3x3 Laplacian -> |x| -> 3x3 dilation (MaxPool)
      -> [GlobalMaxPool, GlobalAveragePool] -> Gemm -> Sigmoid

It responds to sharp local discontinuities (cracks, scratches, pits). The blur suppresses
sensor noise and the fine texture of a normal machined surface, and the mean-edge term
discounts images that are rough everywhere. It needs no training data or GPU, so the whole
pipeline runs at RM0 on a laptop CPU.

To use a trained CNN instead (e.g. fine-tuned on MVTec AD), export it from PyTorch with the
same input/output names and shapes and point `bas.vision.model-path` at the file.

Usage:
    python3 -m venv ml/.venv && ml/.venv/bin/pip install onnx numpy
    ml/.venv/bin/python ml/build_defect_model.py src/main/resources/models/defect-detector.onnx

Weights can be overridden for calibration, e.g. W_MAX=1 W_MEAN=0 BIAS=0 outputs sigmoid(max_edge).
"""
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

SIZE = 128
# Decision weights: logit = W_MAX * max_edge + W_MEAN * mean_edge + BIAS.
# Calibrated on 40 synthetic parts per class from SampleImageGenerator.java:
#   GOOD max_edge 0.014-0.019, CRACK 0.108-0.218, SCRATCH 0.061-0.187, PITTING 0.126-0.470,
#   mean_edge about 0.003 (good) to 0.005 (defective). Boundary sits near max_edge = 0.045.
W_MAX = float(os.environ.get("W_MAX", 150.0))
W_MEAN = float(os.environ.get("W_MEAN", -200.0))
BIAS = float(os.environ.get("BIAS", -6.0))


def build(path: str) -> None:
    blur = np.full((1, 1, 3, 3), 1.0 / 9.0, dtype=np.float32)
    laplacian = np.array([[[[0, 1, 0], [1, -4, 1], [0, 1, 0]]]], dtype=np.float32)
    gemm_w = np.array([[W_MAX], [W_MEAN]], dtype=np.float32)  # [2, 1]
    gemm_b = np.array([BIAS], dtype=np.float32)

    inits = [
        numpy_helper.from_array(blur, "blur_w"),
        numpy_helper.from_array(laplacian, "lap_w"),
        numpy_helper.from_array(gemm_w, "fc_w"),
        numpy_helper.from_array(gemm_b, "fc_b"),
    ]
    nodes = [
        # Valid (unpadded) convolutions: zero padding would create a strong false edge along the border.
        helper.make_node("Conv", ["image", "blur_w"], ["blurred"]),
        helper.make_node("Conv", ["blurred", "lap_w"], ["edges"]),
        helper.make_node("Abs", ["edges"], ["edges_abs"]),
        helper.make_node("MaxPool", ["edges_abs"], ["dilated"], kernel_shape=[3, 3], pads=[1, 1, 1, 1]),
        helper.make_node("GlobalMaxPool", ["dilated"], ["gmax"]),
        helper.make_node("GlobalAveragePool", ["edges_abs"], ["gavg"]),
        helper.make_node("Concat", ["gmax", "gavg"], ["feat4d"], axis=1),
        helper.make_node("Flatten", ["feat4d"], ["features"], axis=1),
        helper.make_node("Gemm", ["features", "fc_w", "fc_b"], ["logit"]),
        helper.make_node("Sigmoid", ["logit"], ["defect_probability"]),
    ]
    graph = helper.make_graph(
        nodes,
        "bas_defect_baseline",
        [helper.make_tensor_value_info("image", TensorProto.FLOAT, [1, 1, SIZE, SIZE])],
        [helper.make_tensor_value_info("defect_probability", TensorProto.FLOAT, [1, 1])],
        initializer=inits,
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)],
                              producer_name="bas-intelligence")
    model.ir_version = 8
    model.doc_string = "PlantSentinel baseline surface-defect detector (fixed-weight CNN) v1"
    onnx.checker.check_model(model)
    onnx.save(model, path)
    print(f"Wrote {path} (W_MAX={W_MAX}, W_MEAN={W_MEAN}, BIAS={BIAS})")


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "defect-detector.onnx")

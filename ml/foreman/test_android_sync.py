"""The files android/ml ships and tests against are byte-identical to the ones ml/foreman produced."""
import filecmp
import os

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.join(HERE, "..", "..", "android", "ml", "src")
PAIRS = [
    ("classifier/model/pickup_model.json", "main/assets/foreman/pickup_model.json"),
    ("classifier/model/pickup_model.tflite", "main/assets/foreman/pickup_model.tflite"),
    ("classifier/model/pickup_vectors.json", "test/resources/foreman/pickup_vectors.json"),
    ("classifier/model/pickup_logistic.json", "test/resources/foreman/pickup_logistic.json"),
    ("classifier/model/pickup_gbdt.json", "test/resources/foreman/pickup_gbdt.json"),
    ("classifier/model/pickup_cnn.json", "test/resources/foreman/pickup_cnn.json"),
    ("planner/model/planner_params.json", "main/assets/foreman/planner_params.json"),
    ("planner/model/planner_vectors.json", "test/resources/foreman/planner_vectors.json"),
]


@pytest.mark.parametrize("src,dst", PAIRS)
def test_android_copy_is_identical(src, dst):
    a, b = os.path.join(HERE, src), os.path.join(ANDROID, dst)
    if not os.path.exists(a) and src.endswith(".tflite"):
        assert not os.path.exists(b), "a stale .tflite is still shipped"
        return
    assert filecmp.cmp(a, b, shallow=False), f"{dst} differs from {src}: rerun the exporter"

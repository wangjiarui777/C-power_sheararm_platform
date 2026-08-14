import hashlib
import importlib
import importlib.util
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import HTTPException
import numpy as np
import torch


INFERENCE_DIR = Path(__file__).resolve().parents[1]
if str(INFERENCE_DIR) not in sys.path:
    sys.path.insert(0, str(INFERENCE_DIR))

os.environ.setdefault("INFERENCE_INTERNAL_TOKEN", "test-internal-token-at-least-32-bytes")
if importlib.util.find_spec("prometheus_client") is None:
    prometheus = types.ModuleType("prometheus_client")

    class _Metric:
        def __init__(self, *args, **kwargs):
            pass

        def labels(self, *args, **kwargs):
            return self

        def inc(self, *args, **kwargs):
            return None

        def observe(self, *args, **kwargs):
            return None

    prometheus.CONTENT_TYPE_LATEST = "text/plain"
    prometheus.Counter = _Metric
    prometheus.Histogram = _Metric
    prometheus.generate_latest = lambda: b""
    sys.modules["prometheus_client"] = prometheus
service = importlib.import_module("inference_service")


class InferenceServiceContractTest(unittest.TestCase):
    def test_only_production_internal_routes_are_exposed(self):
        routes = {
            (method, route.path)
            for route in service.app.routes
            for method in getattr(route, "methods", set())
        }
        self.assertEqual(routes, {
            ("POST", "/internal/infer"),
            ("GET", "/internal/health/live"),
            ("GET", "/internal/health/ready"),
            ("GET", "/internal/metrics"),
        })

    def test_internal_token_is_required(self):
        with patch.object(service, "INTERNAL_TOKEN", "a" * 32):
            with self.assertRaises(HTTPException) as error:
                service.require_internal_token("wrong")
            self.assertEqual(error.exception.status_code, 401)
            service.require_internal_token("a" * 32)

    def test_model_type_is_strict(self):
        self.assertEqual(service._normalize_model_type("GEAR"), "gear")
        self.assertEqual(service._normalize_model_type("bearing"), "bearing")
        with self.assertRaises(HTTPException) as error:
            service._normalize_model_type("unknown")
        self.assertEqual(error.exception.status_code, 400)

    def test_model_artifact_hash_is_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.pth"
            content = b"verified-model"
            artifact.write_bytes(content)
            expected = hashlib.sha256(content).hexdigest()
            with patch.object(service, "ALLOW_UNVERIFIED_MODELS", False):
                service._verify_model_artifact(artifact, expected, "gear")
                with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
                    service._verify_model_artifact(artifact, "0" * 64, "gear")
                with self.assertRaisesRegex(RuntimeError, "must be configured"):
                    service._verify_model_artifact(artifact, "", "gear")

    def test_numpy_metadata_checkpoint_loads_with_weights_only(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "checkpoint.pth"
            torch.save({"state": {"weight": torch.ones(2)}, "dtype": np.dtype("float32")}, artifact)
            service._register_torch_safe_globals()
            checkpoint = service.v6.safe_torch_load(artifact, map_location="cpu")
            self.assertEqual(tuple(checkpoint["state"]["weight"].shape), (2,))
            self.assertEqual(str(checkpoint["dtype"]), "float32")

    def test_health_reports_partial_model_availability(self):
        with patch.object(service, "gear_model", object()), \
             patch.object(service, "bearing_model", None), \
             patch.object(service, "model_load_errors", {"bearing": "hash mismatch"}):
            payload = service._build_health_payload()
        self.assertEqual(payload["status"], "degraded")
        self.assertTrue(payload["gear_model_loaded"])
        self.assertFalse(payload["bearing_model_loaded"])
        self.assertEqual(payload["model_errors"]["bearing"], "hash mismatch")

    def test_infer_rejects_missing_file_before_model_execution(self):
        with self.assertRaises(HTTPException) as error:
            service.infer({"modelType": "gear", "filePath": "missing.npy"})
        self.assertEqual(error.exception.status_code, 400)

    def test_infer_rejects_absolute_path_outside_allowlist(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "sample.npy"
            source.write_bytes(b"\x93NUMPY")
            with patch.object(service, "ALLOWED_INPUT_ROOTS", (INFERENCE_DIR / "trusted",)):
                with self.assertRaises(HTTPException) as error:
                    service.infer({"modelType": "gear", "filePath": str(source)})
        self.assertEqual(error.exception.status_code, 403)

    def test_model_artifact_path_cannot_escape_model_root(self):
        with tempfile.TemporaryDirectory() as root_dir, tempfile.TemporaryDirectory() as outside_dir:
            outside = Path(outside_dir) / "model.pth"
            outside.write_bytes(b"model")
            with patch.object(service, "MODEL_ROOT", Path(root_dir).resolve()):
                with self.assertRaisesRegex(ValueError, "outside INFERENCE_MODEL_ROOT"):
                    service._resolve_model_artifact(str(outside))

    def test_result_cache_is_isolated_by_model_version(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "sample.npy"
            source.write_bytes(b"sample")
            calls = []

            def compute(version):
                calls.append(version)
                return {"version": version}

            service._response_cache.clear()
            first = service._get_cached_or_compute(source, "gear", "1.0.0", compute, "1.0.0")
            cached = service._get_cached_or_compute(source, "gear", "1.0.0", compute, "unexpected")
            second = service._get_cached_or_compute(source, "gear", "2.0.0", compute, "2.0.0")
            self.assertEqual(first, cached)
            self.assertEqual(second["version"], "2.0.0")
            self.assertEqual(calls, ["1.0.0", "2.0.0"])

    def test_model_bundle_cache_uses_bounded_lru(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            artifacts = []
            for index in range(3):
                artifact = root / f"model-{index}.pth"
                artifact.write_bytes(f"model-{index}".encode())
                artifacts.append(artifact)

            service._model_bundle_cache.clear()
            loader_result = (object(), {}, [], {})
            with patch.object(service, "MODEL_ROOT", root), \
                 patch.object(service, "MODEL_CACHE_SIZE", 2), \
                 patch.object(service, "load_gear_model", return_value=loader_result):
                for index, artifact in enumerate(artifacts):
                    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
                    service._load_model_bundle("gear", f"1.0.{index}", artifact.name, digest)

            self.assertEqual(len(service._model_bundle_cache), 2)
            self.assertFalse(any(":1.0.0:" in key for key in service._model_bundle_cache))
            self.assertTrue(any(":1.0.2:" in key for key in service._model_bundle_cache))


if __name__ == "__main__":
    unittest.main()

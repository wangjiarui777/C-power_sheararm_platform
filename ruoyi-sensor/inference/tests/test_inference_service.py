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


if __name__ == "__main__":
    unittest.main()

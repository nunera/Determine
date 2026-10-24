import os
import time
import unittest
from unittest import mock

import support

import backends


class InferenceBackendTest(unittest.TestCase):
    def setUp(self):
        self.server = support.FakeInferenceServer().__enter__()
        self.addCleanup(self.server.__exit__, None, None, None)
        self.backend = backends.InferenceBackend(self.server.url + "/", "k3y")

    def generate(self, timeout_s=2.0):
        return self.backend.generate("req_12345678", "/9j/AAAA", "describe", timeout_s)

    def assertBackendError(self, code, retryable, timeout_s=2.0):
        with self.assertRaises(backends.BackendError) as ctx:
            self.generate(timeout_s)
        self.assertEqual((ctx.exception.code, ctx.exception.retryable), (code, retryable))
        self.assertEqual(ctx.exception.status, backends.BackendError.STATUS[code])
        return ctx.exception

    def test_success(self):
        gen = self.generate()
        self.assertEqual(gen.text, self.server.text)
        self.assertEqual((gen.backend, gen.silicon), ("inf2", "Inferentia2"))
        self.assertEqual(gen.model, "HuggingFaceTB/SmolVLM-256M-Instruct")
        self.assertEqual(self.server.requests[0]["path"], "/v1/generate")
        self.assertEqual(self.server.requests[0]["headers"]["X-Determine-Key"], "k3y")

    def test_not_ready_503(self):
        self.server.status = 503
        err = self.assertBackendError("unavailable", True)
        self.assertEqual(err.status, 503)

    def test_timeout_504(self):
        self.server.delay_s = 1.0
        started = time.monotonic()
        err = self.assertBackendError("timeout", True, timeout_s=0.2)
        self.assertEqual(err.status, 504)
        self.assertLess(time.monotonic() - started, 0.8)

    def test_bad_key_is_unavailable_not_retryable(self):
        self.server.status = 401
        self.server.payload = {"error": "unauthorized", "retryable": False}
        self.assertBackendError("unavailable", False)

    def test_bad_request_is_inference_failed(self):
        self.server.status = 400
        self.server.payload = {"error": "bad_image", "retryable": False}
        self.assertBackendError("inference_failed", False)

    def test_server_500_uses_retryable_hint(self):
        self.server.status = 500
        self.server.payload = {"error": "oom", "retryable": True}
        self.assertBackendError("inference_failed", True)

    def test_malformed_responses(self):
        self.server.payload = {"request_id": "req_12345678", "model": "m", "device": "inf2"}  # no text
        self.assertBackendError("inference_failed", True)
        self.server.payload = {"request_id": "other_id", "text": "x", "model": "m", "device": "inf2"}
        self.assertBackendError("inference_failed", True)
        self.server.payload = {"request_id": "req_12345678", "text": "x", "model": "m", "device": "gpu"}
        self.assertBackendError("inference_failed", False)
        self.server.payload = ["not", "an", "object"]
        self.assertBackendError("inference_failed", True)

    def test_connection_refused(self):
        backend = backends.InferenceBackend(support.closed_port_url(), "k")
        with self.assertRaises(backends.BackendError) as ctx:
            backend.generate("req_12345678", "/9j/", "p", 1.0)
        self.assertEqual((ctx.exception.code, ctx.exception.retryable), ("unavailable", True))

    def test_no_budget_left(self):
        self.assertBackendError("timeout", True, timeout_s=0)
        self.assertEqual(self.server.requests, [])


class BedrockBackendTest(unittest.TestCase):
    def generate(self, client):
        backend = backends.BedrockBackend("us.amazon.nova-lite-v1:0", client_factory=client.factory)
        return backend.generate("req_12345678", support.jpeg(), "describe", 3.2)

    def test_success_and_timeout_floor(self):
        client = support.FakeBedrockClient(text="A bench is right.")
        gen = self.generate(client)
        self.assertEqual((gen.text, gen.backend, gen.silicon), ("A bench is right.", "bedrock", "unspecified"))
        self.assertEqual(client.timeouts, [3.0])

    def test_error_mapping(self):
        cases = [
            (support.FakeClientError("ThrottlingException", 429), "unavailable", True),
            (support.FakeClientError("AccessDeniedException", 403), "unavailable", False),
            (support.FakeClientError("ModelTimeoutException", 408), "timeout", True),
            (support.FakeClientError("ValidationException", 400), "inference_failed", False),
            (support.FakeClientError("SomethingNew", 500), "unavailable", True),
            (support.ReadTimeoutError("read timed out"), "timeout", True),
            (support.EndpointConnectionError("no route"), "unavailable", True),
            (RuntimeError("boom"), "inference_failed", True),
        ]
        for exc, code, retryable in cases:
            with self.subTest(exc=type(exc).__name__, code=code):
                with self.assertRaises(backends.BackendError) as ctx:
                    self.generate(support.FakeBedrockClient(error=exc))
                self.assertEqual((ctx.exception.code, ctx.exception.retryable), (code, retryable))

    def test_missing_boto3_is_unavailable(self):
        def factory(_timeout):
            raise ModuleNotFoundError("No module named 'boto3'")

        backend = backends.BedrockBackend(client_factory=factory)
        with self.assertRaises(backends.BackendError) as ctx:
            backend.generate("req_12345678", support.jpeg(), "p", 2.0)
        self.assertEqual(ctx.exception.code, "unavailable")

    def test_rejects_other_models(self):
        with self.assertRaises(ValueError):
            backends.BedrockBackend("anthropic.claude-3-haiku")

    def test_empty_content(self):
        client = support.FakeBedrockClient()
        client.converse = lambda **kw: {"output": {"message": {"content": []}}}
        self.assertEqual(self.generate(client).text, "")
        client.converse = lambda **kw: {"output": {}}
        with self.assertRaises(backends.BackendError):
            self.generate(client)


class LoadSecretTest(unittest.TestCase):
    def setUp(self):
        backends.clear_secret_cache()
        self.addCleanup(backends.clear_secret_cache)

    def test_env_wins_over_ssm(self):
        with mock.patch.dict(os.environ, {"K": "direct", "K_PARAM": "/p"}, clear=True), \
                mock.patch.object(backends, "_ssm_get_parameter", side_effect=AssertionError("no SSM")):
            self.assertEqual(backends.load_secret("K", "K_PARAM"), "direct")

    def test_neither_configured(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(backends.SecretError):
                backends.load_secret("K", "K_PARAM")

    def test_empty_ssm_value_rejected(self):
        with mock.patch.dict(os.environ, {"K_PARAM": "/p"}, clear=True), \
                mock.patch.object(backends, "_ssm_get_parameter", return_value=""):
            with self.assertRaises(backends.SecretError):
                backends.load_secret("K", "K_PARAM")


if __name__ == "__main__":
    unittest.main()

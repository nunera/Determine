import base64
import json
import os
import unittest
from unittest import mock

import support
from support import API_KEY, INFERENCE_KEY, REQUEST_ID, event, invoke, request_body

import backends
import handler
import safety


class HandlerTestCase(unittest.TestCase):
    """Runs every test with a clean environment and secret cache."""

    extra_env: dict = {}

    def setUp(self):
        backends.clear_secret_cache()
        self.server = support.FakeInferenceServer().__enter__()
        self.addCleanup(self.server.__exit__, None, None, None)
        env = {
            "DESCRIBE_API_KEY": API_KEY,
            "DESCRIBE_BACKEND": "inf2",
            "INFERENCE_URL": self.server.url,
            "INFERENCE_KEY": INFERENCE_KEY,
        }
        env.update(self.extra_env)
        patcher = mock.patch.dict(os.environ, env, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(backends.clear_secret_cache)

    def assertError(self, evt, status, code, retryable=False, request_id=None):
        response, body, records, _ = invoke(evt)
        self.assertEqual(response["statusCode"], status, body)
        self.assertEqual(body, {"request_id": request_id, "error": code, "retryable": retryable})
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["status"], status)
        self.assertEqual(records[0]["error"], code)
        return response, body, records


class SuccessTest(HandlerTestCase):
    def test_contract_response(self):
        response, body, records, _ = invoke(event())
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(response["headers"]["Content-Type"], "application/json")
        self.assertEqual(body["request_id"], REQUEST_ID)
        self.assertEqual(body["text"], "A door is open on your left. Two steps go up ahead.")
        self.assertEqual(body["sentences"], ["A door is open on your left.", "Two steps go up ahead."])
        self.assertEqual(body["model"], "HuggingFaceTB/SmolVLM-256M-Instruct")
        self.assertEqual((body["backend"], body["silicon"]), ("inf2", "Inferentia2"))
        timings = body["timings_ms"]
        self.assertEqual(set(timings), {"inference", "api", "total"})
        self.assertEqual(timings["inference"] + timings["api"], timings["total"])
        self.assertEqual(set(body), {"request_id", "text", "sentences", "model", "backend", "silicon", "timings_ms"})

    def test_inference_request_follows_contract(self):
        invoke(event())
        self.assertEqual(len(self.server.requests), 1)
        sent = self.server.requests[0]
        self.assertEqual(sent["path"], "/v1/generate")
        self.assertEqual(sent["headers"].get("X-Determine-Key"), INFERENCE_KEY)
        self.assertEqual(sent["body"]["request_id"], REQUEST_ID)
        self.assertEqual(sent["body"]["prompt"], safety.PROMPT)
        self.assertEqual(sent["body"]["image_jpeg_b64"], request_body()["image_jpeg_b64"])
        self.assertTrue(1 <= sent["body"]["max_new_tokens"] <= 128)
        self.assertTrue(0.0 <= sent["body"]["temperature"] <= 1.0)

    def test_device_reported_by_server_wins(self):
        self.server.device = "cpu"
        _, body, _, _ = invoke(event())
        self.assertEqual((body["backend"], body["silicon"]), ("cpu", "CPU"))
        self.server.device = "trn2"
        _, body, _, _ = invoke(event())
        self.assertEqual((body["backend"], body["silicon"]), ("trn2", "Trainium2"))

    def test_base64_encoded_event_body(self):
        response, body, _, _ = invoke(event(b64=True))
        self.assertEqual(response["statusCode"], 200, body)

    def test_api_gateway_stage_prefix(self):
        response, _, _, _ = invoke(event(path="/prod/v1/describe"))
        self.assertEqual(response["statusCode"], 200)

    def test_filter_applied_to_output(self):
        self.server.text = "The path is clear. A bench is on your right. A door is ahead."
        _, body, records, _ = invoke(event())
        self.assertEqual(body["sentences"], ["A bench is on your right."])
        self.assertEqual(records[0]["filter"]["dropped_reassurance"], 1)

    def test_everything_filtered_is_502_not_retryable(self):
        self.server.text = "The path ahead is clear and safe. A young woman is smiling."
        self.assertError(event(), 502, "inference_failed", False, REQUEST_ID)

    def test_empty_model_output_is_502(self):
        self.server.text = "   "
        self.assertError(event(), 502, "inference_failed", False, REQUEST_ID)


class AuthTest(HandlerTestCase):
    def test_missing_header(self):
        response, _, _ = self.assertError(event(token=None), 401, "unauthorized")
        self.assertEqual(response["headers"]["WWW-Authenticate"], "Bearer")
        self.assertEqual(self.server.requests, [])

    def test_wrong_key(self):
        self.assertError(event(token="not-the-key"), 401, "unauthorized")
        self.assertError(event(token=API_KEY + "x"), 401, "unauthorized")

    def test_wrong_scheme(self):
        evt = event(token=None, headers={"authorization": f"Basic {API_KEY}"})
        self.assertError(evt, 401, "unauthorized")

    def test_scheme_case_insensitive(self):
        evt = event(token=None, headers={"authorization": f"bearer {API_KEY}"})
        response, _, _, _ = invoke(evt)
        self.assertEqual(response["statusCode"], 200)

    def test_auth_checked_before_body(self):
        self.assertError(event(token="nope", raw_body="not json"), 401, "unauthorized")

    def test_no_key_configured_fails_closed(self):
        with mock.patch.dict(os.environ, {"DESCRIBE_API_KEY": ""}):
            self.assertError(event(), 503, "unavailable", False)

    def test_key_from_ssm_fetched_once(self):
        calls = []

        def fake_ssm(name):
            calls.append(name)
            return API_KEY

        with mock.patch.dict(os.environ, {"DESCRIBE_API_KEY": "", "DESCRIBE_API_KEY_PARAM": "/determine/describe/api-key"}), \
                mock.patch.object(backends, "_ssm_get_parameter", fake_ssm):
            for _ in range(3):
                response, _, _, _ = invoke(event())
                self.assertEqual(response["statusCode"], 200)
        self.assertEqual(calls, ["/determine/describe/api-key"])

    def test_ssm_failure_is_retryable_503_and_not_cached(self):
        attempts = []

        def flaky_ssm(name):
            attempts.append(name)
            if len(attempts) == 1:
                raise RuntimeError("throttled")
            return API_KEY

        with mock.patch.dict(os.environ, {"DESCRIBE_API_KEY": "", "DESCRIBE_API_KEY_PARAM": "/k"}), \
                mock.patch.object(backends, "_ssm_get_parameter", flaky_ssm):
            self.assertError(event(), 503, "unavailable", True)
            response, _, _, _ = invoke(event())
            self.assertEqual(response["statusCode"], 200)

    def test_inference_key_from_ssm(self):
        with mock.patch.dict(os.environ, {"INFERENCE_KEY": "", "INFERENCE_KEY_PARAM": "/determine/describe/inference-key"}), \
                mock.patch.object(backends, "_ssm_get_parameter", lambda name: "ssm-inference-key"):
            response, _, _, _ = invoke(event())
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(self.server.requests[0]["headers"].get("X-Determine-Key"), "ssm-inference-key")


class ValidationTest(HandlerTestCase):
    def test_request_id_rules(self):
        for bad in ["short7_", "x" * 65, "has space1", "semi;colon", "ünïcode12", 12345678, None]:
            with self.subTest(request_id=bad):
                self.assertError(event(request_body(request_id=bad)), 400, "bad_request", False, None)
        for good in ["a" * 8, "Z" * 64, "abc-DEF_123"]:
            with self.subTest(request_id=good):
                response, body, _, _ = invoke(event(request_body(request_id=good)))
                self.assertEqual(response["statusCode"], 200)
                self.assertEqual(body["request_id"], good)

    def test_redacted_must_be_true(self):
        for value in [False, "true", 1, None]:
            with self.subTest(redacted=value):
                self.assertError(event(request_body(redacted=value)), 400, "bad_request", False, REQUEST_ID)
        missing = request_body()
        del missing["redacted"]
        self.assertError(event(missing), 400, "bad_request", False, REQUEST_ID)
        self.assertEqual(self.server.requests, [])  # nothing unredacted ever reaches a model

    def test_locale(self):
        for value in ["fr", "EN", "", None]:
            with self.subTest(locale=value):
                self.assertError(event(request_body(locale=value)), 400, "bad_request", False, REQUEST_ID)

    def test_dimensions(self):
        for name in ["width", "height"]:
            for bad in [15, 2049, 0, -640, "640", 640.0, True, None]:
                with self.subTest(field=name, value=bad):
                    self.assertError(event(request_body(**{name: bad})), 400, "bad_request", False, REQUEST_ID)
            for good in [16, 2048]:
                with self.subTest(field=name, value=good):
                    response, _, _, _ = invoke(event(request_body(**{name: good})))
                    self.assertEqual(response["statusCode"], 200)

    def test_image_must_be_base64_jpeg(self):
        for bad in ["not base64!!", "", base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * 64).decode(), 123]:
            with self.subTest(image=str(bad)[:20]):
                self.assertError(event(request_body(image_jpeg_b64=bad)), 400, "bad_request", False, REQUEST_ID)

    def test_image_size_boundary(self):
        exact = base64.b64encode(support.jpeg(handler.MAX_IMAGE_BYTES)).decode()
        response, _, records, _ = invoke(event(request_body(image_jpeg_b64=exact)))
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual(records[0]["image_bytes"], 512 * 1024)
        over = base64.b64encode(support.jpeg(handler.MAX_IMAGE_BYTES + 1)).decode()
        self.assertError(event(request_body(image_jpeg_b64=over)), 413, "too_large", False, REQUEST_ID)

    def test_oversized_body_rejected_before_parsing(self):
        raw = json.dumps(request_body(image_jpeg_b64="A" * (handler.MAX_BODY_BYTES + 10)))
        self.assertError(event(raw_body=raw), 413, "too_large", False, None)

    def test_body_not_json_object(self):
        self.assertError(event(raw_body="{not json"), 400, "bad_request")
        self.assertError(event(raw_body="[1,2]"), 400, "bad_request")
        evt = event()
        evt["body"] = None
        self.assertError(evt, 400, "bad_request")

    def test_routes(self):
        self.assertError(event(path="/v1/other"), 404, "not_found")
        response, _, _ = self.assertError(event(method="GET"), 405, "method_not_allowed")
        self.assertEqual(response["headers"]["Allow"], "POST")


class BackendErrorTest(HandlerTestCase):
    def test_not_ready_is_503_retryable(self):
        self.server.status = 503
        self.assertError(event(), 503, "unavailable", True, REQUEST_ID)

    def test_server_error_is_502(self):
        self.server.status = 500
        self.server.payload = {"error": "generate_failed", "retryable": False}
        self.assertError(event(), 502, "inference_failed", False, REQUEST_ID)

    def test_timeout_is_504(self):
        self.server.delay_s = 1.0
        with mock.patch.object(handler, "INFERENCE_BUDGET_S", 0.3):
            _, _, records = self.assertError(event(), 504, "timeout", True, REQUEST_ID)
        self.assertLess(records[0]["timings_ms"]["total"], 900)

    def test_connection_refused_is_503(self):
        with mock.patch.dict(os.environ, {"INFERENCE_URL": support.closed_port_url()}):
            self.assertError(event(), 503, "unavailable", True, REQUEST_ID)

    def test_missing_inference_url_is_503_not_retryable(self):
        with mock.patch.dict(os.environ, {"INFERENCE_URL": ""}):
            self.assertError(event(), 503, "unavailable", False, REQUEST_ID)

    def test_unknown_backend_setting(self):
        with mock.patch.dict(os.environ, {"DESCRIBE_BACKEND": "gpu"}):
            self.assertError(event(), 503, "unavailable", False, REQUEST_ID)


class FallbackTest(HandlerTestCase):
    extra_env = {"DESCRIBE_FALLBACK": "bedrock"}

    def setUp(self):
        super().setUp()
        self.bedrock = support.FakeBedrockClient()
        patcher = mock.patch.object(backends, "_default_bedrock_client", self.bedrock.factory)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_primary_unavailable_uses_bedrock(self):
        self.server.status = 503
        response, body, records, _ = invoke(event())
        self.assertEqual(response["statusCode"], 200, body)
        self.assertEqual((body["backend"], body["silicon"]), ("bedrock", "unspecified"))
        self.assertEqual(body["model"], "us.amazon.nova-lite-v1:0")
        self.assertEqual(body["sentences"], ["A bench is on your right.", "A doorway is ahead."])
        self.assertTrue(records[0]["fallback_used"])
        self.assertEqual(records[0]["primary_error"], "unavailable")
        self.assertEqual(records[0]["backend"], "bedrock")

    def test_converse_request_shape(self):
        self.server.status = 503
        invoke(event())
        call = self.bedrock.calls[0]
        self.assertEqual(call["modelId"], "us.amazon.nova-lite-v1:0")
        content = call["messages"][0]["content"]
        self.assertEqual(call["messages"][0]["role"], "user")
        self.assertEqual(content[0], {"image": {"format": "jpeg", "source": {"bytes": support.jpeg()}}})
        self.assertEqual(content[1], {"text": safety.PROMPT})
        self.assertEqual(call["inferenceConfig"]["maxTokens"], backends.MAX_NEW_TOKENS)

    def test_primary_timeout_uses_bedrock_within_budget(self):
        self.server.delay_s = 1.5
        with mock.patch.object(handler, "PRIMARY_BUDGET_WITH_FALLBACK_S", 0.3), \
                mock.patch.object(handler, "INFERENCE_BUDGET_S", 2.0), \
                mock.patch.object(handler, "MIN_FALLBACK_S", 0.5):
            response, body, records, _ = invoke(event())
        self.assertEqual(response["statusCode"], 200, body)
        self.assertEqual(body["backend"], "bedrock")
        self.assertEqual(records[0]["primary_error"], "timeout")
        # Bedrock got what was left of the 2 s budget, floored to 0.5 s steps.
        self.assertEqual(self.bedrock.timeouts, [1.5])

    def test_primary_answer_means_no_bedrock_call(self):
        _, body, records, _ = invoke(event())
        self.assertEqual(body["backend"], "inf2")
        self.assertEqual(self.bedrock.calls, [])
        self.assertFalse(records[0]["fallback_used"])

    def test_inference_failed_does_not_fall_back(self):
        self.server.status = 500
        self.assertError(event(), 502, "inference_failed", True, REQUEST_ID)
        self.assertEqual(self.bedrock.calls, [])

    def test_no_fallback_when_budget_spent(self):
        self.server.delay_s = 1.0
        with mock.patch.object(handler, "PRIMARY_BUDGET_WITH_FALLBACK_S", 0.3), \
                mock.patch.object(handler, "INFERENCE_BUDGET_S", 0.6):
            self.assertError(event(), 504, "timeout", True, REQUEST_ID)
        self.assertEqual(self.bedrock.calls, [])

    def test_fallback_failure_is_reported(self):
        self.server.status = 503
        self.bedrock.error = support.FakeClientError("ThrottlingException")
        self.assertError(event(), 503, "unavailable", True, REQUEST_ID)
        self.assertEqual(len(self.bedrock.calls), 1)

    def test_fallback_disabled(self):
        self.server.status = 503
        with mock.patch.dict(os.environ, {"DESCRIBE_FALLBACK": "none"}):
            self.assertError(event(), 503, "unavailable", True, REQUEST_ID)
        self.assertEqual(self.bedrock.calls, [])

    def test_bedrock_as_primary(self):
        with mock.patch.dict(os.environ, {"DESCRIBE_BACKEND": "bedrock", "BEDROCK_MODEL_ID": "amazon.nova-lite-v1:0"}):
            response, body, _, _ = invoke(event())
        self.assertEqual(response["statusCode"], 200)
        self.assertEqual((body["backend"], body["model"]), ("bedrock", "amazon.nova-lite-v1:0"))
        self.assertEqual(self.server.requests, [])
        self.assertEqual(self.bedrock.timeouts, [handler.INFERENCE_BUDGET_S])


class LogLineTest(HandlerTestCase):
    def test_one_line_without_text_or_image(self):
        self.server.text = "A red mailbox is on your left. A glass door is ahead."
        image_b64 = base64.b64encode(support.jpeg(4096)).decode()
        _, body, records, raw_log = invoke(event(request_body(image_jpeg_b64=image_b64)))
        self.assertEqual(len(raw_log.splitlines()), 1)
        record = records[0]
        self.assertEqual(record["request_id"], REQUEST_ID)
        self.assertEqual(record["image_bytes"], 4096)
        self.assertEqual(record["backend"], "inf2")
        self.assertEqual(record["status"], 200)
        self.assertEqual(record["timings_ms"], body["timings_ms"])
        for secret in ["red mailbox", "glass door", image_b64[:40], API_KEY, INFERENCE_KEY]:
            self.assertNotIn(secret, raw_log)
        self.assertNotIn("text", record)
        self.assertNotIn("sentences", record)

    def test_error_path_logs_once_without_payload(self):
        image_b64 = base64.b64encode(b"\x89PNG" + b"1" * 300).decode()
        _, _, records, raw_log = invoke(event(request_body(image_jpeg_b64=image_b64)))
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["status"], 400)
        self.assertNotIn(image_b64[:40], raw_log)

    def test_unauthorized_logs_without_key(self):
        _, _, records, raw_log = invoke(event(token="guess-1234"))
        self.assertEqual(records[0]["status"], 401)
        self.assertNotIn("guess-1234", raw_log)
        self.assertNotIn(API_KEY, raw_log)


if __name__ == "__main__":
    unittest.main()

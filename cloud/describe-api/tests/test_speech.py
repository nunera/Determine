import json
import io
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app'))
import handler
import speech


class SpeechChecks(unittest.TestCase):
    def test_unconfigured_providers_cannot_be_used(self):
        with patch.dict('os.environ', {}, clear=True):
            self.assertTrue(all(not p['available'] and not p['voices'] for p in speech.catalog()['providers']))
            with self.assertRaises(handler.ApiError) as error:
                speech.open_stream(json.dumps({'provider': 'openai', 'text': 'A door is ahead.', 'voice': 'alloy'}).encode())
            self.assertEqual(error.exception.code, 'unavailable')

    def test_invalid_requests_do_not_reach_provider(self):
        base = {'provider': 'openai', 'text': 'A door is ahead.', 'voice': 'alloy'}
        for change in [{'provider': 'unknown'}, {'text': ''}, {'text': 'x' * 2001}, {'voice': '../escape'},
                       {'speed': True}, {'speed': float('nan')}, {'speed': 9}, {'voice': 'not_a_voice'}]:
            with self.subTest(change=change), patch.dict('os.environ', {'OPENAI_API_KEY': 'test-key'}), patch('urllib.request.urlopen') as network:
                with self.assertRaises(handler.ApiError) as error:
                    speech.open_stream(json.dumps(base | change).encode())
                self.assertEqual(error.exception.code, 'bad_request')
                network.assert_not_called()

    def test_openrouter_uses_existing_key_and_only_verified_pcm_models(self):
        upstream = {"data": [
            {"id": "elevenlabs/eleven-flash-v2.5", "name": "Eleven Flash", "supported_voices": ["george", "sarah"]},
            {"id": "microsoft/mai-voice-2.1", "name": "MAI Voice", "supported_voices": ["en-US-Harper:MAI-Voice-2.1"]},
            {"id": "unknown/audio-model", "name": "Unknown format", "supported_voices": ["voice"]},
        ]}
        with patch.dict("os.environ", {"OPENROUTER_API_KEY": "router-key"}, clear=True), \
             patch.object(speech, "_models_cache", None), patch.object(speech, "_models_cached_at", 0.0), \
             patch("urllib.request.urlopen", return_value=io.BytesIO(json.dumps(upstream).encode())):
            providers = speech.catalog()["providers"]
        router = next((p for p in providers if p["id"] == "openrouter"), None)
        self.assertIsNotNone(router, "Existing OpenRouter credentials must enable speech without another key")
        self.assertTrue(router["available"])
        self.assertEqual([m["id"] for m in router["models"]], ["elevenlabs/eleven-flash-v2.5", "microsoft/mai-voice-2.1"])
        self.assertEqual([v["id"] for v in router["models"][0]["voices"]], ["george", "sarah"])
        self.assertEqual(router["models"][1]["voices"][0]["id"], "en-US-Harper:MAI-Voice-2.1")

    def test_router_rejects_model_voice_mismatch_without_generating_audio(self):
        model = {"id": "microsoft/mai-voice-2.1", "voices": [{"id": "en-US-Harper:MAI-Voice-2.1"}]}
        body = {"provider": "openrouter", "model": model["id"], "voice": "george", "text": "A chair is ahead."}
        with patch.dict("os.environ", {"OPENROUTER_API_KEY": "router-key"}, clear=True), \
             patch.object(speech, "_router_models", return_value=[model]), patch("urllib.request.urlopen") as network:
            with self.assertRaises(handler.ApiError) as error:
                speech.open_stream(json.dumps(body).encode())
            self.assertEqual(error.exception.code, "bad_request")
            network.assert_not_called()

    def test_router_does_not_send_incompatible_pcm_to_phone(self):
        model = {"id": "elevenlabs/eleven-flash-v2.5", "voices": [{"id": "george"}]}
        body = {"provider": "openrouter", "model": model["id"], "voice": "george", "text": "A chair is ahead."}
        response = io.BytesIO(b"wrong sample rate")
        response.headers = {"Content-Type": "audio/pcm;rate=44100;channels=1"}
        with patch.dict("os.environ", {"OPENROUTER_API_KEY": "router-key"}, clear=True), \
             patch.object(speech, "_router_models", return_value=[model]), \
             patch("urllib.request.urlopen", return_value=response):
            with self.assertRaises(handler.ApiError) as error:
                speech.open_stream(json.dumps(body).encode())
            self.assertEqual(error.exception.code, "unavailable")
            self.assertTrue(response.closed)


if __name__ == '__main__':
    unittest.main()

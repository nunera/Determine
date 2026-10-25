"""Authenticated speech proxy. Provider credentials never leave the server.
PCM format: signed little-endian 16-bit, mono, 24 kHz.
Sources: https://openrouter.ai/docs/guides/overview/multimodal/tts
https://platform.openai.com/docs/api-reference/audio/createSpeech
https://elevenlabs.io/docs/api-reference/text-to-speech/stream
"""
import json
import math
import os
import re
import threading
import time
import urllib.error
import urllib.request
import handler

OPENAI_VOICES = ('alloy', 'ash', 'ballad', 'coral', 'echo', 'fable', 'onyx', 'nova', 'sage', 'shimmer', 'verse', 'marin', 'cedar')
# These models were probed with the live account: audio/pcm;rate=24000;channels=1.
# Other catalog models may have incompatible formats; do not advertise them blindly.
ROUTER_MODELS = (
    'elevenlabs/eleven-flash-v2.5',
    'elevenlabs/eleven-multilingual-v2',
    'microsoft/mai-voice-2.1-flash',
    'microsoft/mai-voice-2.1',
)
DEFAULT_ROUTER_MODEL = ROUTER_MODELS[0]
_models_cache = None
_models_cached_at = 0.0
_models_lock = threading.Lock()


def _key(provider):
    names = {'openrouter': 'OPENROUTER_API_KEY', 'openai': 'OPENAI_API_KEY', 'elevenlabs': 'ELEVENLABS_API_KEY'}
    if provider == 'openrouter' and os.environ.get('OPENROUTER_API_KEY_PARAM'):
        try:
            return handler._openrouter_client().key
        except handler.backends.BackendError:
            return ''
    return os.environ.get(names[provider], '').strip()


def _router_base():
    return (os.environ.get('OPENROUTER_BASE_URL', '').strip() or 'https://openrouter.ai/api/v1').rstrip('/')


def _router_models():
    global _models_cache, _models_cached_at
    with _models_lock:
        if _models_cache is not None and time.monotonic() - _models_cached_at < 300:
            return _models_cache
        with urllib.request.urlopen(_router_base() + '/models?output_modalities=speech', timeout=8) as response:
            raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise ValueError('voice catalog too large')
        data = json.loads(raw)
        if not isinstance(data, dict) or not isinstance(data.get('data'), list):
            raise ValueError('invalid model catalog')
        indexed = {m['id']: m for m in data['data'] if isinstance(m, dict) and isinstance(m.get('id'), str)}
        models = []
        for model_id in ROUTER_MODELS:
            model = indexed.get(model_id)
            if not model or not isinstance(model.get('supported_voices'), list):
                continue
            voices = [{'id': voice, 'name': voice.split(':', 1)[0].replace('_', ' ').title()}
                      for voice in model['supported_voices']
                      if isinstance(voice, str) and re.fullmatch(r'[A-Za-z0-9_.:\-]{1,100}', voice)]
            if voices:
                models.append({'id': model_id, 'name': model.get('name') or model_id, 'voices': voices})
        _models_cache, _models_cached_at = models, time.monotonic()
        return models


def _eleven_voices():
    req = urllib.request.Request('https://api.elevenlabs.io/v2/voices?page_size=100', headers={'xi-api-key': _key('elevenlabs')})
    with urllib.request.urlopen(req, timeout=8) as response:
        raw = response.read(256 * 1024 + 1)
    if len(raw) > 256 * 1024:
        raise ValueError('voice catalog too large')
    return [{'id': v['voice_id'], 'name': v['name']} for v in json.loads(raw).get('voices', [])
            if isinstance(v.get('voice_id'), str) and isinstance(v.get('name'), str)]


def catalog():
    providers = []
    for provider, name in [('openrouter', 'OpenRouter'), ('openai', 'OpenAI (direct)'), ('elevenlabs', 'ElevenLabs (direct)')]:
        available = bool(_key(provider))
        reason = None if available else 'Server API key not configured'
        voices = [{'id': v, 'name': v.capitalize()} for v in OPENAI_VOICES] if available and provider == 'openai' else []
        models = []
        if available and provider in ('openrouter', 'elevenlabs'):
            try:
                if provider == 'openrouter':
                    models = _router_models()
                    if not models:
                        available, reason = False, 'No compatible streaming models available'
                else:
                    voices = _eleven_voices()
                    if not voices:
                        available, reason = False, 'No voices available on this account'
            except (OSError, ValueError, KeyError, TypeError):
                available, reason = False, 'Could not load provider voices; refresh to retry'
        providers.append({'id': provider, 'name': name, 'available': available, 'reason': reason, 'voices': voices, 'models': models})
    return {'providers': providers}


def open_stream(raw):
    try:
        p = json.loads(raw)
        provider, text, voice = p.get('provider'), p.get('text'), p.get('voice')
        speed = p.get('speed', 1.0)
        if provider not in ('openrouter', 'openai', 'elevenlabs') or not isinstance(text, str) or not 1 <= len(text.strip()) <= 2000:
            raise ValueError()
        pattern = r'[A-Za-z0-9_.:\-]{1,100}' if provider == 'openrouter' else r'[A-Za-z0-9_-]{1,100}'
        if not isinstance(voice, str) or not re.fullmatch(pattern, voice):
            raise ValueError()
        if isinstance(speed, bool) or not isinstance(speed, (int, float)) or not math.isfinite(speed) or not 0.7 <= speed <= 1.2:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise handler.ApiError('bad_request') from None
    key = _key(provider)
    if not key:
        raise handler.ApiError('unavailable', False, 'speech provider not configured')
    if provider == 'openrouter':
        model_id = p.get('model', DEFAULT_ROUTER_MODEL)
        if not isinstance(model_id, str) or model_id not in ROUTER_MODELS:
            raise handler.ApiError('bad_request')
        try:
            model = next((m for m in _router_models() if m['id'] == model_id), None)
        except (OSError, ValueError, KeyError, TypeError):
            raise handler.ApiError('unavailable', True, 'model catalog unreachable') from None
        if model is None:
            raise handler.ApiError('unavailable', False, 'model not available')
        if voice not in {v['id'] for v in model['voices']}:
            raise handler.ApiError('bad_request', False, 'voice does not belong to model')
        url = _router_base() + '/audio/speech'
        # Speed is applied pitch-preservingly on the iPhone: support differs between routed models.
        body = {'model': model_id, 'voice': voice, 'input': text, 'response_format': 'pcm',
                'provider': handler._openrouter_client()._provider()}
        headers = {'Authorization': 'Bearer ' + key}
    elif provider == 'openai':
        if voice not in OPENAI_VOICES:
            raise handler.ApiError('bad_request')
        url = 'https://api.openai.com/v1/audio/speech'
        body = {'model': 'gpt-4o-mini-tts', 'voice': voice, 'input': text, 'response_format': 'pcm', 'speed': speed}
        headers = {'Authorization': 'Bearer ' + key}
    else:
        url = 'https://api.elevenlabs.io/v1/text-to-speech/' + voice + '/stream?output_format=pcm_24000'
        body = {'text': text, 'model_id': 'eleven_flash_v2_5', 'voice_settings': {'speed': speed}}
        headers = {'xi-api-key': key}
    headers['Content-Type'] = 'application/json'
    try:
        response = urllib.request.urlopen(urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers), timeout=20)
        if provider == 'openrouter':
            content_type = response.headers.get('Content-Type', '').lower().replace(' ', '')
            if 'audio/pcm' not in content_type or 'rate=24000' not in content_type or 'channels=1' not in content_type:
                response.close()
                raise handler.ApiError('unavailable', False, 'incompatible speech audio format')
        return response
    except urllib.error.HTTPError as exc:
        exc.close()
        raise handler.ApiError('unavailable', exc.code == 429 or exc.code >= 500, 'speech provider refused request') from None
    except OSError:
        raise handler.ApiError('unavailable', True, 'speech provider unreachable') from None

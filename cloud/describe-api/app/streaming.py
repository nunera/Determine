"""Real OpenRouter SSE -> sentence-filtered NDJSON. No raw model token leaves this module.
Source: https://openrouter.ai/docs/api/reference/streaming
"""
from __future__ import annotations
import json
import os
import re
import socket
import time
import urllib.error
import urllib.request
import backends
import handler
import safety

LENGTHS = {'brief': (1, 180), 'standard': (2, 360), 'detailed': (5, 1000)}
STYLES = {
    'direct': 'Use plain, direct factual sentences. No introduction or filler.',
    'conversational': 'Use natural, approachable language without greetings or filler.',
    'spatial': 'Organize visible objects by left, center and right in the image. Do not give walking directions or estimate distances.',
}


def preferences(payload):
    length, style = payload.get('length', 'standard'), payload.get('style', 'direct')
    if length not in LENGTHS or style not in STYLES:
        raise ValueError('unsupported description preferences')
    return length, style


class SentenceGate:
    """Hold incomplete sentences until they can be filtered; cap emitted text, not just the final answer."""
    def __init__(self, length):
        self.limit, self.budget = LENGTHS[length]
        self.pending = ''
        self.text = ''
        self.count = 0

    def _accept(self, sentence):
        if self.count >= self.limit or safety.asserts_safety_or_absence(sentence) or safety.guesses_person_attribute(sentence):
            return []
        room = self.budget - len(self.text) - bool(self.text)
        if room <= 0:
            return []
        if len(sentence) > room:
            sentence = sentence[:room].rsplit(' ', 1)[0].rstrip(',;:') + '.'
            if len(sentence) > room:
                return []
        piece = (' ' if self.text else '') + sentence
        self.text += piece
        self.count += 1
        return [piece]

    def feed(self, delta):
        self.pending += delta
        if len(self.pending) > 8192:
            raise backends.BackendError('inference_failed', False, 'sentence too large')
        out = []
        # A period at the current chunk end may belong to an abbreviation; wait for whitespace or EOF.
        while True:
            match = re.search(r'[.!?][\"\u201d\u2019\)]*\s+', self.pending)
            if not match:
                break
            candidate = self.pending[:match.end()].strip()
            parts = safety.split_sentences(candidate)
            if len(parts) == 1 and re.search(r'\b(?:' + '|'.join(re.escape(x) for x in safety.ABBREVIATIONS) + r')\.$', candidate, re.I):
                # Find the next terminator without dropping the abbreviation prefix.
                following = re.search(r'[.!?][\"\u201d\u2019\)]*\s+', self.pending[match.end():])
                if not following:
                    break
                match_end = match.end() + following.end()
                candidate = self.pending[:match_end].strip()
            else:
                match_end = match.end()
            self.pending = self.pending[match_end:]
            for part in safety.split_sentences(candidate):
                out.extend(self._accept(part))
        return out

    def finish(self):
        out = []
        for sentence in safety.split_sentences(self.pending.strip()):
            out.extend(self._accept(sentence))
        self.pending = ''
        return out


def prepare(event):
    handler._check_auth(handler._headers(event))
    raw = handler._body_bytes(event)
    req = handler._parse_request(raw, {})
    try:
        length, style = preferences(json.loads(raw))
    except (ValueError, TypeError):
        raise handler.ApiError('bad_request') from None
    if os.environ.get('DESCRIBE_BACKEND', 'inf2') != 'openrouter':
        raise handler.ApiError('unavailable', False, 'streaming requires OpenRouter')
    return req, length, style


def events(req, length, style):
    client = handler._openrouter_client()
    count, chars = LENGTHS[length]
    prompt = safety.prompt_for(req.question)
    prompt += f' For this answer replace the two-sentence limit with at most {count} sentences and {chars} characters. ' + STYLES[style]
    body = {'model': os.environ.get('OPENROUTER_MODEL') or 'openai/gpt-6-luna',
            'stream': True, 'max_tokens': 800 if length == 'detailed' else 400,
            'temperature': 0.2, 'provider': client._provider(),
            'messages': [{'role': 'user', 'content': [
                {'type': 'text', 'text': prompt},
                {'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,' + req.image_b64}}]}]}
    request = urllib.request.Request(client.url, data=json.dumps(body).encode(), headers={
        'Authorization': 'Bearer ' + client.key, 'Content-Type': 'application/json', 'Accept': 'text/event-stream'})
    gate = SentenceGate(length)
    deadline = time.monotonic() + 20
    consumed = 0
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            ended = False
            fields = []
            for raw in response:
                consumed += len(raw)
                if consumed > 256 * 1024:
                    raise backends.BackendError('inference_failed', False, 'stream too large')
                if time.monotonic() > deadline:
                    raise backends.BackendError('timeout', True, 'stream deadline')
                line = raw.decode('utf-8').rstrip('\r\n')
                if line.startswith('data:'):
                    fields.append(line[5:].lstrip())
                    continue
                if line or not fields:
                    continue
                data = '\n'.join(fields)
                fields = []
                if data == '[DONE]':
                    ended = True
                    break
                chunk = json.loads(data)
                if 'error' in chunk:
                    raise backends.BackendError('inference_failed', True, 'upstream streaming error')
                for choice in chunk.get('choices', []):
                    if choice.get('finish_reason') == 'error':
                        raise backends.BackendError('inference_failed', True, 'upstream streaming error')
                    delta = choice.get('delta', {}).get('content') or ''
                    if not isinstance(delta, str):
                        raise backends.BackendError('inference_failed', False, 'invalid delta')
                    for text in gate.feed(delta):
                        yield {'type': 'delta', 'text': text}
                    if gate.count >= gate.limit:
                        ended = True
                        break
                if gate.count >= gate.limit:
                    break
            if not ended:
                raise backends.BackendError('inference_failed', True, 'incomplete stream')
            for text in gate.finish():
                yield {'type': 'delta', 'text': text}
        if not gate.text:
            raise backends.BackendError('inference_failed', False, 'safety filter left nothing')
        yield {'type': 'done', 'text': gate.text, 'request_id': req.request_id}
    except urllib.error.HTTPError as exc:
        raise client._map_http_error(exc) from None
    except (TimeoutError, socket.timeout):
        raise backends.BackendError('timeout', True, 'stream timeout') from None
    except (urllib.error.URLError, OSError):
        raise backends.BackendError('unavailable', True, 'stream connection failed') from None
    except (ValueError, UnicodeError, AttributeError):
        raise backends.BackendError('inference_failed', False, 'invalid upstream stream') from None

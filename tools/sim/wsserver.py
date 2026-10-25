"""Minimal threaded HTTP/1.1 + WebSocket (RFC 6455) server, Python 3 stdlib only.

- `GET <ws_path>` with `Upgrade: websocket` → a WebSocket: text and binary messages, fragmented incoming
  messages, ping/pong, close handshake. Client frames must be masked (RFC 6455 §5.1).
- Other `GET`/`HEAD`: static files from `static_dir` (`/` → index.html); no directory listings.
- `POST <path>`: `post_routes[path](body, content_type) -> (status, content_type, body)`.

Every connection is served on its own thread (ThreadingHTTPServer). Outgoing WebSocket frames go through a
per-connection queue and writer thread, so `broadcast_text` never blocks the caller on a slow client: a client
that falls more than SEND_QUEUE frames behind is disconnected instead.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import http.server
import json
import os
import queue
import socket
import struct
import threading
from typing import BinaryIO, Callable, Iterator
from urllib.parse import urlsplit

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
OP_CONT, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA
MAX_MESSAGE = 8 << 20  # largest reassembled incoming message (camera JPEGs are far smaller)
MAX_POST = 6 << 20
SEND_QUEUE = 1024

PostRoute = Callable[[bytes, str], "tuple[int, str, bytes]"]


class ProtocolError(Exception):
    """A peer violated RFC 6455; `code` is the close status to send."""

    def __init__(self, code: int, reason: str):
        super().__init__(reason)
        self.code = code
        self.reason = reason


# ---------------------------------------------------------------------- framing (pure)

def accept_key(key: str) -> str:
    """Sec-WebSocket-Accept for a client's Sec-WebSocket-Key (RFC 6455 §4.2.2)."""
    return base64.b64encode(hashlib.sha1((key + GUID).encode("ascii")).digest()).decode("ascii")


def _xor(data: bytes, mask: bytes) -> bytes:
    n = len(data)
    if n == 0:
        return b""
    m = (mask * (n // 4 + 1))[:n]
    return (int.from_bytes(data, "big") ^ int.from_bytes(m, "big")).to_bytes(n, "big")


def encode_frame(opcode: int, payload: bytes, *, fin: bool = True, mask: bytes | None = None) -> bytes:
    """One frame. Servers send unmasked frames; pass a 4-byte `mask` to build a client frame."""
    b0 = (0x80 if fin else 0) | opcode
    mbit = 0x80 if mask else 0
    n = len(payload)
    if n < 126:
        hdr = struct.pack("!BB", b0, mbit | n)
    elif n < 1 << 16:
        hdr = struct.pack("!BBH", b0, mbit | 126, n)
    else:
        hdr = struct.pack("!BBQ", b0, mbit | 127, n)
    if mask:
        if len(mask) != 4:
            raise ValueError("mask must be 4 bytes")
        return hdr + mask + _xor(payload, mask)
    return hdr + payload


def close_payload(code: int, reason: str = "") -> bytes:
    return struct.pack("!H", code) + reason.encode("utf-8")[:123]


def _read_exact(rfile: BinaryIO, n: int) -> bytes:
    data = rfile.read(n) if n else b""
    if len(data) != n:
        raise ConnectionError("peer closed the connection")
    return data


def read_frame(rfile: BinaryIO, require_mask: bool = True) -> tuple[bool, int, bytes]:
    """(fin, opcode, unmasked payload) of the next frame."""
    b0, b1 = _read_exact(rfile, 2)
    if b0 & 0x70:
        raise ProtocolError(1002, "reserved bits set without an extension")
    fin, opcode = bool(b0 & 0x80), b0 & 0x0F
    masked, n = bool(b1 & 0x80), b1 & 0x7F
    if n == 126:
        n = struct.unpack("!H", _read_exact(rfile, 2))[0]
    elif n == 127:
        n = struct.unpack("!Q", _read_exact(rfile, 8))[0]
        if n >> 63:
            raise ProtocolError(1002, "64-bit length with the high bit set")
    if opcode >= 0x8 and (not fin or n > 125):
        raise ProtocolError(1002, "control frames must be final and at most 125 bytes")
    if require_mask and not masked:
        raise ProtocolError(1002, "client frames must be masked")
    if n > MAX_MESSAGE:
        raise ProtocolError(1009, "message too big")
    mask = _read_exact(rfile, 4) if masked else None
    payload = _read_exact(rfile, n)
    return fin, opcode, (_xor(payload, mask) if mask else payload)


def read_messages(rfile: BinaryIO, require_mask: bool = True) -> Iterator[tuple[int, bytes]]:
    """Yield complete data messages (OP_TEXT/OP_BINARY, fragments joined) and control frames as they arrive
    (control frames may interleave with the fragments of a message)."""
    op: int | None = None
    parts: list[bytes] = []
    size = 0
    while True:
        fin, opcode, payload = read_frame(rfile, require_mask)
        if opcode in (OP_CLOSE, OP_PING, OP_PONG):
            yield opcode, payload
            continue
        if opcode == OP_CONT:
            if op is None:
                raise ProtocolError(1002, "continuation frame without a message")
        elif opcode in (OP_TEXT, OP_BINARY):
            if op is not None:
                raise ProtocolError(1002, "new message before the previous one finished")
            op = opcode
        else:
            raise ProtocolError(1002, f"unknown opcode {opcode:#x}")
        parts.append(payload)
        size += len(payload)
        if size > MAX_MESSAGE:
            raise ProtocolError(1009, "message too big")
        if fin:
            yield op, b"".join(parts)
            op, parts, size = None, [], 0


# ---------------------------------------------------------------------- connections

class WSConn:
    """One accepted WebSocket. send_* never block; frames go out on this connection's writer thread."""

    def __init__(self, sock: socket.socket, peer: str):
        self.sock = sock
        self.peer = peer
        self._q: queue.Queue = queue.Queue(SEND_QUEUE)
        self._lock = threading.Lock()
        self._closing = False
        self._writer = threading.Thread(target=self._write_loop, name=f"ws-tx-{peer}", daemon=True)
        self._writer.start()

    def send_text(self, text: str) -> bool:
        return self.send_frame(encode_frame(OP_TEXT, text.encode("utf-8")))

    def send_binary(self, data: bytes) -> bool:
        return self.send_frame(encode_frame(OP_BINARY, data))

    def send_frame(self, frame: bytes) -> bool:
        with self._lock:
            if self._closing:
                return False
            try:
                self._q.put_nowait(frame)
                return True
            except queue.Full:
                pass
        self.abort()  # too slow to keep up with the event stream: disconnect rather than stall the senders
        return False

    def close(self, code: int = 1000, reason: str = ""):
        """Send a close frame (once), then end the writer."""
        with self._lock:
            if self._closing:
                return
            self._closing = True
            try:
                self._q.put_nowait(encode_frame(OP_CLOSE, close_payload(code, reason)))
                self._q.put_nowait(None)
            except queue.Full:
                pass
            else:
                return
        self.abort()

    def abort(self):
        with self._lock:
            self._closing = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)  # unblocks the reader and the writer
        except OSError:
            pass
        try:
            self._q.put_nowait(None)
        except queue.Full:
            pass

    def wait_closed(self, timeout: float):
        self._writer.join(timeout)

    def _write_loop(self):
        while True:
            frame = self._q.get()
            if frame is None:
                return
            try:
                self.sock.sendall(frame)
            except OSError:
                with self._lock:
                    self._closing = True
                return


# ---------------------------------------------------------------------- server

class _Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "determine-world-bridge/1"
    extensions_map = {
        "": "application/octet-stream",
        ".html": "text/html; charset=utf-8",
        ".js": "text/javascript; charset=utf-8",
        ".mjs": "text/javascript; charset=utf-8",
        ".css": "text/css; charset=utf-8",
        ".json": "application/json",
        ".map": "application/json",
        ".svg": "image/svg+xml",
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".woff2": "font/woff2",
        ".woff": "font/woff",
        ".txt": "text/plain; charset=utf-8",
    }
    app: "WebSocketServer"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=self.app.static_dir or os.getcwd(), **kwargs)

    # -- HTTP
    def do_GET(self):
        if urlsplit(self.path).path == self.app.ws_path:
            self._websocket()
        elif self.app.static_dir is None:
            self._send(404, "text/plain; charset=utf-8", b"not found\n")
        else:
            super().do_GET()

    def do_HEAD(self):
        if self.app.static_dir is None:
            self._send(404, "text/plain; charset=utf-8", b"")
        else:
            super().do_HEAD()

    def list_directory(self, path):
        self.send_error(404, "not found")
        return None

    def do_POST(self):
        route = self.app.post_routes.get(urlsplit(self.path).path)
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            length = -1
        if length < 0 or length > MAX_POST:
            self.close_connection = True
            self._send_json(413 if length > MAX_POST else 400, {"ok": False, "error": "bad or oversized body"})
            return
        body = self.rfile.read(length) if length else b""
        if route is None:
            self._send_json(404, {"ok": False, "error": "not found"})
            return
        try:
            status, ctype, data = route(body, self.headers.get("Content-Type", ""))
        except Exception as e:  # a route bug must not take the server down; report it to the client
            self._send_json(500, {"ok": False, "error": f"{type(e).__name__}: {e}"})
            return
        self._send(status, ctype, data)

    def _send_json(self, status: int, obj):
        self._send(status, "application/json", json.dumps(obj).encode("utf-8"))

    def _send(self, status: int, ctype: str, data: bytes):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def log_message(self, format, *args):  # noqa: A002 — BaseHTTPRequestHandler signature; keep the console quiet
        pass

    # -- WebSocket
    def _websocket(self):
        h = self.headers
        if "websocket" not in h.get("Upgrade", "").lower() or "upgrade" not in h.get("Connection", "").lower():
            self._send(400, "text/plain; charset=utf-8", b"expected a WebSocket upgrade\n")
            return
        if h.get("Sec-WebSocket-Version", "").strip() != "13":
            self.send_response(426)
            self.send_header("Sec-WebSocket-Version", "13")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        key = h.get("Sec-WebSocket-Key", "").strip()
        try:
            ok = len(base64.b64decode(key, validate=True)) == 16
        except (binascii.Error, ValueError):
            ok = False
        if not ok:
            self._send(400, "text/plain; charset=utf-8", b"bad Sec-WebSocket-Key\n")
            return
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept_key(key))
        self.end_headers()
        self.close_connection = True  # after the WebSocket ends, this TCP connection is done

        app = self.app
        conn = WSConn(self.connection, f"{self.client_address[0]}:{self.client_address[1]}")
        app._add(conn)
        try:
            app.on_connect(conn)
            for op, payload in read_messages(self.rfile):
                if op == OP_TEXT:
                    try:
                        text = payload.decode("utf-8")
                    except UnicodeDecodeError:
                        raise ProtocolError(1007, "text message is not UTF-8") from None
                    app.on_text(conn, text)
                elif op == OP_BINARY:
                    app.on_binary(conn, payload)
                elif op == OP_PING:
                    conn.send_frame(encode_frame(OP_PONG, payload))
                elif op == OP_CLOSE:
                    code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else 1000
                    conn.close(code if 1000 <= code < 5000 and code not in (1005, 1006, 1015) else 1002)
                    break
        except ProtocolError as e:
            conn.close(e.code, e.reason)
        except (ConnectionError, OSError):
            pass
        finally:
            app._remove(conn)
            conn.close(1000)
            conn.wait_closed(1.0)
            app.on_disconnect(conn)


def _noop(*_args):
    return None


class WebSocketServer:
    def __init__(self, bind: str, port: int, *, ws_path: str = "/ws", static_dir: str | None = None,
                 post_routes: dict[str, PostRoute] | None = None,
                 on_connect: Callable[[WSConn], None] = _noop,
                 on_text: Callable[[WSConn, str], None] = _noop,
                 on_binary: Callable[[WSConn, bytes], None] = _noop,
                 on_disconnect: Callable[[WSConn], None] = _noop):
        self.ws_path = ws_path
        self.static_dir = os.path.abspath(static_dir) if static_dir else None
        self.post_routes = dict(post_routes or {})
        self.on_connect, self.on_text, self.on_binary, self.on_disconnect = on_connect, on_text, on_binary, on_disconnect
        self._conns: set[WSConn] = set()
        self._lock = threading.Lock()
        handler = type("Handler", (_Handler,), {"app": self})
        self.httpd = http.server.ThreadingHTTPServer((bind, port), handler)
        self.httpd.daemon_threads = True
        self.address = self.httpd.server_address[:2]
        self._thread: threading.Thread | None = None

    @property
    def port(self) -> int:
        return self.address[1]

    def _add(self, conn: WSConn):
        with self._lock:
            self._conns.add(conn)

    def _remove(self, conn: WSConn):
        with self._lock:
            self._conns.discard(conn)

    def clients(self) -> list[WSConn]:
        with self._lock:
            return list(self._conns)

    def broadcast_text(self, text: str):
        frame = encode_frame(OP_TEXT, text.encode("utf-8"))
        for c in self.clients():
            c.send_frame(frame)

    def start(self):
        self._thread = threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.2},
                                        name="http", daemon=True)
        self._thread.start()

    def stop(self):
        if self._thread is not None:  # shutdown() waits for serve_forever and would hang if it never ran
            self.httpd.shutdown()
        for c in self.clients():
            c.close(1001, "server shutting down")
        for c in self.clients():
            c.wait_closed(1.0)
            c.abort()
        self.httpd.server_close()

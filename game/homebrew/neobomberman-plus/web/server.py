#!/usr/bin/env python3
"""Local-only development preview: compile the original logic as a shared library.

Run from the repository root: python3 game/homebrew/neobomberman-plus/web/server.py
This is NOT a Neo Geo emulator, ROM server or production multi-user service.
"""
import ctypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import os

WEB = Path(__file__).resolve().parent
PROJECT = WEB.parent
LIB = PROJECT / 'build' / 'web-bomber.so'
LIB.parent.mkdir(exist_ok=True)
subprocess.run(['gcc', '-std=c99', '-O2', '-Wall', '-Wextra', '-Werror', '-fPIC', '-shared',
                str(PROJECT / 'bomber.c'), str(WEB / 'bridge.c'), '-o', str(LIB)], check=True)
engine = ctypes.CDLL(str(LIB))
engine.web_new.argtypes = [ctypes.c_uint, ctypes.c_uint]
engine.web_tick.argtypes = [ctypes.c_uint, ctypes.c_uint, ctypes.c_uint]
engine.web_state.restype = ctypes.c_char_p
engine.web_new(123456, 1)
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def send(self, status, body, mime):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ('/', '/index.html', '/style.css', '/game.js'):
            name = 'index.html' if self.path == '/' else self.path[1:]
            mime = 'text/html; charset=utf-8' if name.endswith('html') else (
                'text/css; charset=utf-8' if name.endswith('css') else 'text/javascript; charset=utf-8')
            self.send(200, (WEB / name).read_bytes(), mime)
        elif self.path == '/api/state':
            with lock:
                state = engine.web_state()
            self.send(200, state, 'application/json; charset=utf-8')
        else:
            self.send(404, b'Not found', 'text/plain')

    def do_POST(self):
        if self.path not in ('/api/new', '/api/tick'):
            return self.send(404, b'Not found', 'text/plain')
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if size < 1 or size > 256:
                raise ValueError('invalid request length')
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict):
                raise ValueError('invalid JSON object')
            with lock:
                if self.path == '/api/new':
                    players = int(data.get('players', 1))
                    if players not in (1, 2):
                        raise ValueError('players must be 1 or 2')
                    engine.web_new(int(data.get('seed', 123456)) & 0xffffffff, players)
                else:
                    inputs = [int(data.get(k, 0)) for k in ('p1', 'p2')]
                    frames = int(data.get('frames', 4))
                    if any(v < 0 or v > 31 for v in inputs) or not 1 <= frames <= 8:
                        raise ValueError('invalid input')
                    engine.web_tick(*inputs, frames)
                state = engine.web_state()
            self.send(200, state, 'application/json; charset=utf-8')
        except (ValueError, TypeError, KeyError, json.JSONDecodeError):
            self.send(400, b'{"error":"bad request"}', 'application/json')


if __name__ == '__main__':
    port = int(os.environ.get('PORT', '8000'))
    httpd = ThreadingHTTPServer(('0.0.0.0', port), Handler)
    print(f'Bomber Plus browser preview listening on 0.0.0.0:{port}', flush=True)
    httpd.serve_forever()

#!/usr/bin/env python3
"""CI network conditioner: CONNECT tunnels keep real map URLs and end-to-end TLS intact.

Only the emulator uses this proxy, only during download smoke checks. Production
code does not know about it. GitHub streams are rate-limited; Google connectivity
checks pass normally. No requests, URLs, credentials or payloads are logged.
"""
import argparse
import select
import socket
import socketserver
import time
from urllib.parse import urlsplit


def allowed(host):
    return host == 'github.com' or any(host == suffix or host.endswith('.' + suffix)
            for suffix in ('githubusercontent.com', 'google.com', 'gstatic.com', 'googleapis.com'))


class Tunnel(socketserver.StreamRequestHandler):
    def handle(self):
        upstream = None
        try:
            self.connection.settimeout(20)
            first = self.rfile.readline(8193)
            if not first or len(first) > 8192: return
            parts = first.decode('ascii').strip().split(' ')
            if len(parts) != 3: return
            method, target, version = parts
            headers = []
            for _ in range(64):
                line = self.rfile.readline(8193)
                if len(line) > 8192: return
                if line in (b'\r\n', b'\n', b''): break
                headers.append(line)
            else: return
            if method == 'CONNECT':
                host, raw_port = target.rsplit(':', 1)
                port = int(raw_port)
                request = None
            else:
                url = urlsplit(target)
                if url.scheme != 'http' or url.username or url.password: return
                host, port = url.hostname, url.port or 80
                path = url.path or '/'
                if url.query: path += '?' + url.query
                request = f'{method} {path} {version}\r\n'.encode() + b''.join(
                        line for line in headers if not line.lower().startswith(b'proxy-connection:')) + b'\r\n'
            if port not in (80, 443) or not host or not allowed(host):
                self.connection.sendall(b'HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n'); return
            upstream = socket.create_connection((host, port), timeout=20)
            upstream.settimeout(30)
            if request is None:
                self.connection.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
            else: upstream.sendall(request)
            github = host == 'github.com' or host.endswith('.githubusercontent.com')
            began, downloaded = time.monotonic(), 0
            while True:
                readable, _, _ = select.select([self.connection, upstream], [], [], 30)
                if not readable: return
                for source in readable:
                    data = source.recv(32768)
                    if not data: return
                    destination = upstream if source is self.connection else self.connection
                    if source is upstream and github:
                        downloaded += len(data)
                        delay = downloaded / self.server.rate - (time.monotonic() - began)
                        if delay > 0: time.sleep(delay)
                    destination.sendall(data)
        except (OSError, ValueError, UnicodeError):
            return
        finally:
            if upstream is not None: upstream.close()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=9090)
    parser.add_argument('--bytes-per-second', type=int, default=524288)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 1024 <= args.bytes_per_second <= 16777216:
        parser.error('Invalid network conditioner configuration')
    with Server(('0.0.0.0', args.port), Tunnel) as server:
        server.rate = args.bytes_per_second
        print('Download network conditioner ready', flush=True)
        server.serve_forever()

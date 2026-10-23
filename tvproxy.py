#!/usr/bin/env python3
"""电视机本地小代理：给直播流补跨域头，让网页电视机能播所有源。

在手机 Termux 或电脑上运行，监听 127.0.0.1:8899（只限本机，不对外）。
电视机（app.benfen.net/tv/）设置里打开「本地代理」即可，地址就是
http://127.0.0.1:8899，不用改。

它只做三件事：
  1. 转发直播流（列表和分片）；
  2. 给每个响应补上跨域头（Access-Control-Allow-Origin 等）；
  3. 改写 m3u8 列表里的每一行地址，让分片也走代理补头。

纯标准库，无第三方依赖。Ctrl+C 退出。
"""
import sys
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, quote, urljoin

PORT = 8899
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/120.0 Mobile Safari/537.36"}


def _why(e):
    """把上游错误翻成一句人话，日志和回执都用它，不打堆栈。"""
    s = str(e)
    if "Errno 103" in s or "connection abort" in s.lower():
        return "连接被对方中止（源不稳或挑线路）"
    if "Errno 111" in s or "Connection refused" in s:
        return "对方拒绝连接"
    if "timed out" in s.lower() or "Timeout" in s:
        return "连接超时（网络慢或源无响应）"
    if "Name or service not known" in s or "Errno -2" in s or "getaddrinfo" in s:
        return "域名解析失败"
    if "CERTIFICATE" in s or "certificate" in s:
        return "对方证书有问题"
    if "HTTP Error" in s:
        return s.split(":")[0]  # 如 HTTP Error 404
    return s[:80]


def wrap(url):
    return f"http://127.0.0.1:{PORT}/proxy?url={quote(url, safe='')}"


def rewrite_m3u8(text, base):
    """把列表里的子列表/分片/密钥地址全部改写为走代理。"""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            out.append(line)
        elif s.startswith("#"):
            if 'URI="' in s:  # EXT-X-KEY / EXT-X-MAP 等
                pre, rest = s.split('URI="', 1)
                uri, post = rest.split('"', 1)
                out.append(pre + 'URI="' + wrap(urljoin(base, uri)) + '"' + post)
            else:
                out.append(line)
        else:  # 子列表或分片地址（相对地址先转绝对）
            out.append(wrap(urljoin(base, s)))
    return "\n".join(out) + "\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "tvproxy/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("· %s\n" % (fmt % args))

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Allow-Private-Network", "true")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/ping":  # 电视机用它检测代理在不在
            body = b'{"ok":true,"app":"tvproxy","version":"1.1"}'
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/":  # 手滑打开代理地址时，给一句人话
            body = ("电视机本地小代理正在运行 ✓\n"
                    "回到电视机（https://app.benfen.net/tv/）设置里打开「本地代理」即可看电视。\n"
                    "保持运行代理的窗口开着，关掉就停了。").encode("utf-8")
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        q = parse_qs(urlparse(self.path).query)
        target = (q.get("url") or [""])[0]
        if not target.startswith(("http://", "https://")):
            body = "用法：/proxy?url=直播流地址".encode("utf-8")
            self.send_response(400)
            self._cors()
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        req = urllib.request.Request(target, headers=UA)
        rng = self.headers.get("Range")
        if rng:
            req.add_header("Range", rng)
        try:
            resp = urllib.request.urlopen(req, timeout=15)
        except Exception as e:
            print(f"  ✗ 拉不到 {urlparse(target).netloc}：{_why(e)}", flush=True)
            body = f"上游拉不到：{_why(e)}".encode("utf-8")
            try:
                self.send_response(502)
                self._cors()
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        final_url = resp.geturl()
        ctype = resp.headers.get("Content-Type", "") or ""
        looks_m3u8 = ("mpegurl" in ctype.lower()
                      or final_url.split("?")[0].lower().endswith((".m3u8", ".m3u")))
        print("  →", final_url[:110], flush=True)
        self.send_response(resp.status)
        self._cors()
        try:
            if looks_m3u8:
                text = resp.read().decode("utf-8", "ignore")
                data = rewrite_m3u8(text, final_url).encode("utf-8")
                self.send_header("Content-Type", "application/vnd.apple.mpegurl")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            else:
                if ctype:
                    self.send_header("Content-Type", ctype)
                cr = resp.headers.get("Content-Range")
                if cr:
                    self.send_header("Content-Range", cr)
                cl = resp.headers.get("Content-Length")
                if cl:
                    self.send_header("Content-Length", cl)
                    self.end_headers()
                    while True:
                        chunk = resp.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                else:  # 上游没给长度：读全量再发，保证连接干净收尾
                    data = resp.read()
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            resp.close()


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # 浏览器先断开（切台/关页面）是常态，不值得刷一屏堆栈
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError)):
            return
        print(f"  ✗ 处理请求出错：{_why(exc) if exc else '未知'}")


if __name__ == "__main__":
    import os
    try:
        srv = Server(("127.0.0.1", PORT), Handler)
    except OSError:
        print("端口 8899 已经被占用——多半是已经开着一个代理了，不用重复开。")
        print("直接回电视机里打开「本地代理」开关即可。")
        sys.exit(0)
    print("=" * 52)
    print(" 电视机本地小代理已启动 ✓")
    print(f" 地址：http://127.0.0.1:{PORT}")
    print(" 接下来：打开电视机 https://app.benfen.net/tv/")
    print("         在设置里打开「本地代理」开关，就能看了。")
    print(" 保持本窗口开着（关掉代理就停）。Ctrl+C 退出。")
    print("=" * 52)
    # 桌面环境自动帮用户把电视机打开（Termux 里没有浏览器，跳过）
    if not os.environ.get("TERMUX_VERSION"):
        try:
            import webbrowser
            webbrowser.open("https://app.benfen.net/tv/")
        except Exception:
            pass
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出。")

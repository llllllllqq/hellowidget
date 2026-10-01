#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CI 专用的极简 WebDAV 测试替身服务（仅依赖 Python 3 标准库，零第三方依赖）。

为什么需要它
------------
Android 仪器化测试跑在 GitHub Actions 的模拟器里，需要一台**真实可达**的
WebDAV 服务端，用来验证客户端的上传 / 下载 / 同步语义（PUT、MKCOL、MOVE、
COPY、PROPFIND、条件请求等）。模拟器用 http://10.0.2.2:PORT 访问宿主机回环，
所以本脚本默认绑定 127.0.0.1，并把每条请求落成一行可 grep 的日志到 --log，
让 CI 能断言「客户端究竟发了什么」。它只服务于测试，绝不能用于生产。

设计取舍
--------
* 存储全在内存里：``files``（路径 -> bytes）、``etags``、``mtimes``、
  ``collections``，由一把可重入锁串行化。Android 客户端每条请求新建 TCP
  连接，服务端用 ``ThreadingHTTPServer`` 并发接待，互不踩踏。
* ETag 带双引号（HTTP 规范如此，客户端会把原值回填到 If-Match），且**每次
  成功写入都换新值**：哈希输入里掺入单调递增的写计数，因此「原样重写同样
  字节」也会被客户端识别为远端变更。
* ``/dav`` 是 DAV 命名空间的根，**隐式存在**：客户端既可以先 ``MKCOL /dav``
  也可以直接 ``PUT /dav/x.txt``；对 ``/dav`` 的首次 MKCOL 返回 201，重复
  MKCOL 返回 405（RFC 4918 语义，客户端可容忍）。
* ``/__control__/*`` 是无鉴权的本地测试控制面（reset / log / count / file /
  etag / exists），仅供回环地址上的测试脚本使用。
* 不发任何 3xx：Android 客户端不会跟随重定向。
* 每个请求都包在 try/except 里：畸形请求返回 4xx/500，绝不拖垮进程。
"""

import argparse
import base64
import hashlib
import hmac
import os
import sys
import threading
import time
import xml.sax.saxutils as saxutils
from datetime import datetime, timezone
from email.utils import formatdate, parsedate_to_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlsplit

# --------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------

DAV_ROOT = "/dav"
CONTROL_PREFIX = "/__control__"

#: OPTIONS 与 405 响应里返回的方法集合（顺序固定，便于测试断言）。
ALLOW = "OPTIONS, HEAD, GET, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY"

REALM = "webdav"

#: 单条请求体的上限，避免 CI 上被超大请求打爆内存。
MAX_BODY = 256 * 1024 * 1024

#: 读请求头的超时（秒），防止半开连接把线程永久挂住。
SOCKET_TIMEOUT = 30.0

_TEXT = "text/plain; charset=utf-8"


class BodyError(Exception):
    """请求体读取失败（帧格式不可信，需要断开连接）。"""


# --------------------------------------------------------------------------
# 小工具
# --------------------------------------------------------------------------


def now_iso():
    """当前 UTC 时间的 ISO8601 形式，例如 ``2026-10-01T03:15:00Z``。"""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def http_date(epoch):
    """把 Unix 时间戳格式化成 RFC 1123 GMT，例如 ``Wed, 01 Oct 2026 03:15:00 GMT``。"""
    return formatdate(float(epoch), usegmt=True)


def parse_http_date(value):
    """解析 HTTP 日期，失败返回 None（按 RFC 7232，无法解析的条件头直接忽略）。"""
    if not value:
        return None
    try:
        dt = parsedate_to_datetime(value)
    except (TypeError, ValueError, IndexError, OverflowError):
        return None
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    try:
        return int(dt.timestamp())
    except (OverflowError, OSError, ValueError):
        return None


def normalize_path(raw):
    """把原始路径归一化成 ``/a/b`` 形式：去重复斜杠、解 ``.``/``..``、去尾斜杠。"""
    if not raw:
        return "/"
    parts = []
    for seg in raw.split("/"):
        if seg in ("", "."):
            continue
        if seg == "..":
            if parts:
                parts.pop()
            continue
        parts.append(seg)
    if not parts:
        return "/"
    return "/" + "/".join(parts)


def parent_path(path):
    """返回父路径；根路径的父仍是根。"""
    if not path or path == "/":
        return "/"
    idx = path.rfind("/")
    if idx <= 0:
        return "/"
    return path[:idx]


def one_line(value, limit=300):
    """把任意头部值压成单行，保证日志始终一行一条、可 grep。"""
    if value is None:
        return ""
    text = " ".join(str(value).split())
    if len(text) > limit:
        text = text[:limit] + "..."
    return text


def xml_escape(text):
    """XML 文本转义：& < >（引号在文本节点里无需转义，保留原文便于断言）。"""
    return saxutils.escape(text)


def quoted_etag(matches_header, current):
    """If-Match / If-None-Match 取值是否命中当前 ETag（对无引号回填也宽容）。"""
    if matches_header is None or current is None:
        return False
    for token in matches_header.split(","):
        token = token.strip()
        if not token:
            continue
        if token.startswith("W/") or token.startswith("w/"):
            token = token[2:].strip()
        if token == current:
            return True
        # 少数客户端会把引号剥掉再回填，这里做一次宽松比较。
        if token.strip('"') == current.strip('"'):
            return True
    return False


# --------------------------------------------------------------------------
# 内存存储
# --------------------------------------------------------------------------


class StubState:
    """线程安全的内存 WebDAV 存储 + 请求日志。"""

    def __init__(self, log_path, user, password):
        self.lock = threading.RLock()
        self.user = user
        self.password = password
        self.log_path = log_path

        #: 归一化绝对路径 -> 文件字节（规格要求的主存储结构）。
        self.files = {}
        #: 归一化绝对路径 -> 带双引号的 ETag。
        self.etags = {}
        #: 归一化绝对路径 -> 最后修改的 Unix 时间戳（浮点）。
        self.mtimes = {}
        #: 显式 MKCOL 出来的集合路径（``/dav`` 本身隐式存在，见 is_collection）。
        self.collections = set()

        #: 单调递增写计数：让「同样的字节重写一次」也产生新的 ETag。
        self.counter = 0
        self.start_time = time.time()
        self.log_lines = []

        self._open_log()

    # -- 日志 ------------------------------------------------------------

    def _open_log(self):
        directory = os.path.dirname(os.path.abspath(self.log_path))
        if directory:
            try:
                os.makedirs(directory, exist_ok=True)
            except OSError:
                pass
        # 每次启动清空，保证一轮 CI 的日志互不污染。
        with open(self.log_path, "w", encoding="utf-8"):
            pass

    def log(self, method, path, status, detail=""):
        line = "[{}] {} {} -> {}".format(now_iso(), method, path, status)
        detail = one_line(detail)
        if detail:
            line += " " + detail
        with self.lock:
            self.log_lines.append(line)
            try:
                with open(self.log_path, "a", encoding="utf-8") as handle:
                    handle.write(line + "\n")
                    handle.flush()
            except OSError:
                pass
        # 同时打到 stdout，CI 控制台里也能直接看到交互过程。
        try:
            print(line, flush=True)
        except Exception:
            pass

    def reset(self):
        """清空存储与请求日志（控制面用）。"""
        with self.lock:
            self.files.clear()
            self.etags.clear()
            self.mtimes.clear()
            self.collections.clear()
            self.log_lines = []
            self.counter = 0
            try:
                with open(self.log_path, "w", encoding="utf-8"):
                    pass
            except OSError:
                pass

    # -- 存储原语（调用方需持有 self.lock，或依赖 RLock 重入） --------------

    def new_etag(self, data):
        """写入计数 + 内容的 sha1，带双引号；同一份字节重写也会换值。"""
        self.counter += 1
        digest = hashlib.sha1()
        digest.update(data)
        digest.update(b"|")
        digest.update(str(self.counter).encode("ascii"))
        return '"' + digest.hexdigest() + '"'

    def store_file(self, path, data):
        self.files[path] = data
        self.etags[path] = self.new_etag(data)
        self.mtimes[path] = time.time()
        self.collections.discard(path)

    def is_collection(self, path):
        # /dav 是命名空间根，永远可当集合使用。
        return path == DAV_ROOT or path in self.collections

    def exists(self, path):
        return path in self.files or self.is_collection(path)

    def exists_tree(self, path):
        """路径本身或其后代是否存在。"""
        if self.exists(path) or path in self.collections:
            return True
        prefix = path.rstrip("/") + "/"
        for key in self.files:
            if key.startswith(prefix):
                return True
        for key in self.collections:
            if key.startswith(prefix):
                return True
        return False

    def parent_exists(self, path):
        """父集合是否存在；``/dav`` 自身允许被创建。"""
        if path == DAV_ROOT:
            return True
        return self.is_collection(parent_path(path))

    def children(self, path):
        """直接子资源（文件 + 集合），已排序去重。"""
        kids = set()
        for key in list(self.files.keys()) + list(self.collections):
            if key != path and parent_path(key) == path:
                kids.add(key)
        return sorted(kids)

    def delete_tree(self, path):
        """删除路径本身及其所有后代。"""
        self.files.pop(path, None)
        self.etags.pop(path, None)
        self.mtimes.pop(path, None)
        self.collections.discard(path)
        prefix = path.rstrip("/") + "/"
        for key in [k for k in self.files if k.startswith(prefix)]:
            self.files.pop(key, None)
            self.etags.pop(key, None)
            self.mtimes.pop(key, None)
        for key in [k for k in self.collections if k.startswith(prefix)]:
            self.collections.discard(key)
            self.mtimes.pop(key, None)

    def collection_mtime(self, path):
        return self.mtimes.get(path, self.start_time)

    def listing(self, path):
        """集合的纯文本列表（GET / HEAD 集合共用，保证两者头部一致）。"""
        lines = ["{} (collection)".format(path)]
        for kid in self.children(path):
            lines.append(kid + ("/" if self.is_collection(kid) and kid not in self.files else ""))
        return ("\n".join(lines) + "\n").encode("utf-8")


# --------------------------------------------------------------------------
# HTTP 处理器
# --------------------------------------------------------------------------


class WebDavStubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "WebDavStub/1.0"
    sys_version = ""
    timeout = SOCKET_TIMEOUT

    # -- 兜底：任何未显式实现的 HTTP 方法都走统一分发（→ 401/405/404） -----

    def __getattr__(self, name):
        if name.startswith("do_"):
            return self._handle_any_method
        raise AttributeError(name)

    def _handle_any_method(self):
        self._dispatch(self.command)

    def log_message(self, fmt, *args):
        # 默认会往 stderr 打访问日志；这里静音，请求日志统一走 StubState.log。
        pass

    # -- 分发 ------------------------------------------------------------

    def _dispatch(self, method):
        self._responded = False
        state = self.server.state
        raw = self.path or "/"
        if raw == "*":  # OPTIONS * 探活
            path = "/"
        else:
            path = normalize_path(unquote(urlsplit(raw).path))
        loggable = not (path == CONTROL_PREFIX or path.startswith(CONTROL_PREFIX + "/"))

        try:
            body = self._read_body()
        except BodyError as exc:
            self.close_connection = True
            self._send_checked(400, [("Content-Type", _TEXT)],
                               ("400 Bad Request: {}\n".format(exc)).encode("utf-8"))
            if loggable:
                state.log(method, path, 400, "body-error=" + one_line(str(exc)))
            return

        try:
            status, headers, payload, detail = self._route(method, path, body)
        except Exception as exc:  # 任何意外都变成 500，绝不拖垮进程
            status = 500
            headers = [("Content-Type", _TEXT)]
            payload = b"500 Internal Server Error\n"
            detail = "error={}: {}".format(type(exc).__name__, one_line(str(exc), 120))
            self.close_connection = True

        if loggable:
            state.log(method, path, status, detail)
        self._send_checked(status, headers, payload)

    def _route(self, method, path, body):
        # 控制面：不鉴权，也不写请求日志。
        if path == CONTROL_PREFIX or path.startswith(CONTROL_PREFIX + "/"):
            return self._route_control(method, path, body)

        # DAV 命名空间之外：只提供探活，其余 404/405。
        if path != DAV_ROOT and not path.startswith(DAV_ROOT + "/"):
            if path == "/":
                if method == "OPTIONS":
                    return 200, [("DAV", "1, 2"), ("Allow", ALLOW)], b"", ""
                if method in ("GET", "HEAD"):
                    return 200, [("Content-Type", _TEXT)], b"webdav stub server\n", ""
                return self._method_not_allowed("")
            return 404, [("Content-Type", _TEXT)], b"404 Not Found\n", "outside-dav"

        # /dav/... 一律要求 Basic 鉴权。
        if not self._check_auth():
            return (401,
                    [("WWW-Authenticate", 'Basic realm="{}"'.format(REALM)),
                     ("Content-Type", _TEXT)],
                    b"401 Unauthorized\n",
                    "auth=missing")

        return self._route_dav(method, path, body)

    def _route_dav(self, method, path, body):
        if method == "OPTIONS":
            return 200, [("DAV", "1, 2"), ("Allow", ALLOW)], b"", self._detail()
        if method == "HEAD":
            return self._handle_get(path, head=True)
        if method == "GET":
            return self._handle_get(path, head=False)
        if method == "PUT":
            return self._handle_put(path, body)
        if method == "DELETE":
            return self._handle_delete(path)
        if method == "MKCOL":
            return self._handle_mkcol(path)
        if method == "PROPFIND":
            return self._handle_propfind(path)
        if method == "MOVE":
            return self._handle_move_copy(path, body, move=True)
        if method == "COPY":
            return self._handle_move_copy(path, body, move=False)
        return self._method_not_allowed(self._detail())

    def _method_not_allowed(self, detail):
        return (405,
                [("Allow", ALLOW), ("Content-Type", _TEXT)],
                b"405 Method Not Allowed\n",
                detail)

    # -- 请求体 ----------------------------------------------------------

    def _read_body(self):
        """按 Content-Length 精确读取；chunked 也支持；畸形帧抛 BodyError。"""
        te_values = self.headers.get_all("Transfer-Encoding") or []
        if any("chunked" in one_line(v).lower() for v in te_values):
            return self._read_chunked()

        raw_lengths = self.headers.get_all("Content-Length") or []
        if not raw_lengths:
            return b""
        unique = set(one_line(v) for v in raw_lengths)
        if len(unique) != 1:
            raise BodyError("conflicting Content-Length headers")
        try:
            length = int(unique.pop())
        except (TypeError, ValueError):
            raise BodyError("invalid Content-Length")
        if length < 0:
            raise BodyError("negative Content-Length")
        if length > MAX_BODY:
            raise BodyError("body larger than {} bytes".format(MAX_BODY))
        if length == 0:
            return b""
        data = self.rfile.read(length)
        if data is None or len(data) != length:
            raise BodyError("short body (expected {} bytes)".format(length))
        return data

    def _read_chunked(self):
        chunks = []
        total = 0
        while True:
            line = self.rfile.readline(65536)
            if not line:
                raise BodyError("truncated chunked body")
            size_text = line.split(b";", 1)[0].strip()
            try:
                size = int(size_text, 16)
            except ValueError:
                raise BodyError("invalid chunk size")
            if size < 0:
                raise BodyError("negative chunk size")
            if size == 0:
                # 吃掉 trailer，直到空行。
                while True:
                    trailer = self.rfile.readline(65536)
                    if not trailer or trailer in (b"\r\n", b"\n"):
                        break
                break
            total += size
            if total > MAX_BODY:
                raise BodyError("chunked body larger than {} bytes".format(MAX_BODY))
            chunk = self.rfile.read(size)
            if chunk is None or len(chunk) != size:
                raise BodyError("short chunk")
            chunks.append(chunk)
            terminator = self.rfile.read(2)
            if terminator not in (b"\r\n", b"\n"):
                raise BodyError("bad chunk terminator")
        return b"".join(chunks)

    # -- 鉴权 / 日志细节 --------------------------------------------------

    def _check_auth(self):
        values = self.headers.get_all("Authorization") or []
        if len(values) != 1:  # 多个 Authorization 一律视为缺失，避免歧义
            return False
        value = one_line(values[0])
        if not value.lower().startswith("basic "):
            return False
        token = value[6:].strip()
        padding = "=" * (-len(token) % 4)
        try:
            decoded = base64.b64decode(token + padding).decode("utf-8", "replace")
        except Exception:
            return False
        if ":" not in decoded:
            return False
        user, _, password = decoded.partition(":")
        try:
            return (hmac.compare_digest(user.encode("utf-8"), self.server.state.user.encode("utf-8"))
                    and hmac.compare_digest(password.encode("utf-8"),
                                            self.server.state.password.encode("utf-8")))
        except Exception:
            return user == self.server.state.user and password == self.server.state.password

    def _hdr(self, name):
        values = self.headers.get_all(name) or []
        if not values:
            return None
        return one_line(values[0])

    def _destination_path(self, raw):
        """从 Destination 头里解析出归一化路径；无法解析返回 None。"""
        if not raw:
            return None
        value = raw.strip()
        lowered = value.lower()
        if lowered.startswith("http://") or lowered.startswith("https://"):
            parsed = urlsplit(value)
            if not parsed.path:
                return None
            return normalize_path(unquote(parsed.path))
        if value.startswith("/"):
            return normalize_path(unquote(urlsplit(value).path))
        return None

    def _detail(self, extras=()):
        """拼装单行日志的 detail 部分。"""
        parts = ["auth=ok"]
        for label, name in (("If-Match", "If-Match"),
                            ("If-None-Match", "If-None-Match"),
                            ("If-Unmodified-Since", "If-Unmodified-Since")):
            value = self._hdr(name)
            if value:
                parts.append("{}={}".format(label, value))
        if self.command == "PROPFIND":
            depth = self._hdr("Depth")
            if depth:
                parts.append("Depth={}".format(depth))
        if self.command in ("MOVE", "COPY"):
            raw_dest = self._hdr("Destination")
            if raw_dest:
                parsed = self._destination_path(raw_dest)
                parts.append("Dest={}".format(parsed if parsed else raw_dest))
            overwrite = self._hdr("Overwrite")
            if overwrite:
                parts.append("Overwrite={}".format(overwrite))
        for extra in extras:
            if extra:
                parts.append(str(extra))
        return " ".join(parts)

    # -- 响应 ------------------------------------------------------------

    def _send(self, status, headers, body):
        head_only = (self.command == "HEAD")
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        if status not in (204, 304):
            self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self._responded = True
        if body and not head_only:
            self.wfile.write(body)
        try:
            self.wfile.flush()
        except Exception:
            pass

    def _send_checked(self, status, headers, body):
        try:
            self._send(status, headers, body)
        except Exception:
            # 客户端提前断开（BrokenPipe 等）不该影响服务端。
            self.close_connection = True

    # -- GET / HEAD ------------------------------------------------------

    def _handle_get(self, path, head):
        state = self.server.state
        with state.lock:
            if path in state.files:
                data = state.files[path]
                headers = [
                    ("Content-Type", "application/octet-stream"),
                    ("ETag", state.etags[path]),
                    ("Last-Modified", http_date(state.mtimes[path])),
                ]
                return 200, headers, data, self._detail(["size={}".format(len(data))])
            if state.is_collection(path):
                listing = state.listing(path)
                headers = [("Content-Type", "httpd/unix-directory")]
                return 200, headers, listing, self._detail(["collection"])
        return (404,
                [("Content-Type", _TEXT)],
                b"404 Not Found\n",
                self._detail(["missing"]))

    # -- PUT -------------------------------------------------------------

    def _handle_put(self, path, body):
        state = self.server.state
        with state.lock:
            if state.is_collection(path):
                return self._method_not_allowed(self._detail(["collection"]))

            exists = path in state.files
            current_etag = state.etags.get(path)

            if_match = self._hdr("If-Match")
            if if_match is not None:
                if not exists or not quoted_etag(if_match, current_etag):
                    return self._precondition_failed(["if-match"])

            if_unmodified = self._hdr("If-Unmodified-Since")
            if if_unmodified is not None and exists:
                limit = parse_http_date(if_unmodified)
                if limit is not None and int(state.mtimes[path]) > limit:
                    return self._precondition_failed(["if-unmodified-since"])

            if_none_match = self._hdr("If-None-Match")
            if if_none_match is not None:
                if if_none_match.strip() == "*":
                    if exists:
                        return self._precondition_failed(["if-none-match=*"])
                elif exists and quoted_etag(if_none_match, current_etag):
                    return self._precondition_failed(["if-none-match"])

            if not exists and not state.parent_exists(path):
                return (409,
                        [("Content-Type", _TEXT)],
                        b"409 Conflict: parent collection missing\n",
                        self._detail(["no-parent"]))

            state.store_file(path, body)
            new_etag = state.etags[path]
            status = 204 if exists else 201
            return (status,
                    [("ETag", new_etag)],
                    b"",
                    self._detail(["len={}".format(len(body)),
                                  "created" if not exists else "replaced"]))

    def _precondition_failed(self, extras):
        return (412,
                [("Content-Type", _TEXT)],
                b"412 Precondition Failed\n",
                self._detail(extras))

    # -- MKCOL -----------------------------------------------------------

    def _handle_mkcol(self, path):
        state = self.server.state
        with state.lock:
            if path in state.files:
                return self._method_not_allowed(self._detail(["exists-as-file"]))
            if path in state.collections:
                return self._method_not_allowed(self._detail(["exists"]))
            if not state.parent_exists(path):
                return (409,
                        [("Content-Type", _TEXT)],
                        b"409 Conflict: parent collection missing\n",
                        self._detail(["no-parent"]))
            state.collections.add(path)
            state.mtimes[path] = time.time()
            return 201, [], b"", self._detail(["created"])

    # -- DELETE ----------------------------------------------------------

    def _handle_delete(self, path):
        state = self.server.state
        with state.lock:
            if not state.exists_tree(path):
                return (404,
                        [("Content-Type", _TEXT)],
                        b"404 Not Found\n",
                        self._detail(["missing"]))
            state.delete_tree(path)
            return 204, [], b"", self._detail(["deleted"])

    # -- PROPFIND --------------------------------------------------------

    def _handle_propfind(self, path):
        state = self.server.state
        depth = (self._hdr("Depth") or "1").lower()
        if depth in ("infinity", "infinite"):
            depth = "1"  # 不递归整棵树，测试只会要 0/1
        if depth not in ("0", "1"):
            return (400,
                    [("Content-Type", _TEXT)],
                    b"400 Bad Request: unsupported Depth\n",
                    self._detail(["bad-depth"]))

        with state.lock:
            if path not in state.files and not state.is_collection(path):
                return (404,
                        [("Content-Type", _TEXT)],
                        b"404 Not Found\n",
                        self._detail(["missing"]))
            targets = [path]
            if depth == "1" and state.is_collection(path):
                targets.extend(state.children(path))
            payload = self._build_multistatus(state, targets)

        return (207,
                [("Content-Type", "application/xml; charset=utf-8")],
                payload,
                self._detail(["resources={}".format(len(targets))]))

    def _build_multistatus(self, state, targets):
        out = ['<?xml version="1.0" encoding="utf-8"?>',
               '<D:multistatus xmlns:D="DAV:">']
        for target in targets:
            is_collection = (target not in state.files) and state.is_collection(target)
            out.append("<D:response>")
            out.append("<D:href>{}</D:href>".format(
                xml_escape(quote(target, safe="/"))))
            out.append("<D:propstat>")
            out.append("<D:prop>")
            if is_collection:
                out.append("<D:resourcetype><D:collection/></D:resourcetype>")
                out.append("<D:getetag/>")
                out.append("<D:getlastmodified>{}</D:getlastmodified>".format(
                    xml_escape(http_date(state.collection_mtime(target)))))
                out.append("<D:getcontentlength>0</D:getcontentlength>")
            else:
                data = state.files[target]
                out.append("<D:resourcetype/>")
                out.append("<D:getetag>{}</D:getetag>".format(
                    xml_escape(state.etags[target])))
                out.append("<D:getlastmodified>{}</D:getlastmodified>".format(
                    xml_escape(http_date(state.mtimes[target]))))
                out.append("<D:getcontentlength>{}</D:getcontentlength>".format(len(data)))
            out.append("</D:prop>")
            out.append("<D:status>HTTP/1.1 200 OK</D:status>")
            out.append("</D:propstat>")
            out.append("</D:response>")
        out.append("</D:multistatus>")
        return "".join(out).encode("utf-8")

    # -- MOVE / COPY -----------------------------------------------------

    def _handle_move_copy(self, path, body, move):
        state = self.server.state
        destination = self._destination_path(self._hdr("Destination"))
        if destination is None:
            return (400,
                    [("Content-Type", _TEXT)],
                    b"400 Bad Request: missing or invalid Destination\n",
                    self._detail(["bad-destination"]))
        overwrite = (self._hdr("Overwrite") or "T").strip().upper()
        if overwrite not in ("T", "F"):
            overwrite = "T"

        with state.lock:
            source_is_file = path in state.files
            if not source_is_file and not state.is_collection(path):
                return (404,
                        [("Content-Type", _TEXT)],
                        b"404 Not Found\n",
                        self._detail(["missing"]))

            # 条件请求针对**源**资源（客户端据此发现并发变更）。
            if_match = self._hdr("If-Match")
            if if_match is not None:
                if not source_is_file or not quoted_etag(if_match, state.etags.get(path)):
                    return self._precondition_failed(["if-match"])
            if_unmodified = self._hdr("If-Unmodified-Since")
            if if_unmodified is not None and source_is_file:
                limit = parse_http_date(if_unmodified)
                if limit is not None and int(state.mtimes[path]) > limit:
                    return self._precondition_failed(["if-unmodified-since"])

            if destination == path:
                return (403,
                        [("Content-Type", _TEXT)],
                        b"403 Forbidden: source equals destination\n",
                        self._detail(["same-path"]))
            if not source_is_file and destination.startswith(path.rstrip("/") + "/"):
                return (409,
                        [("Content-Type", _TEXT)],
                        b"409 Conflict: destination inside source collection\n",
                        self._detail(["dest-in-source"]))

            destination_is_collection = (destination not in state.files
                                         and state.is_collection(destination))
            destination_exists = destination in state.files or destination_is_collection

            if destination_exists and overwrite == "F":
                return self._precondition_failed(["overwrite=F"])

            if destination_exists:
                if source_is_file and destination_is_collection:
                    return (409,
                            [("Content-Type", _TEXT)],
                            b"409 Conflict: cannot overwrite collection with file\n",
                            self._detail(["type-conflict"]))
                if not source_is_file and destination in state.files:
                    return (409,
                            [("Content-Type", _TEXT)],
                            b"409 Conflict: cannot overwrite file with collection\n",
                            self._detail(["type-conflict"]))

            if not state.parent_exists(destination):
                return (409,
                        [("Content-Type", _TEXT)],
                        b"409 Conflict: destination parent missing\n",
                        self._detail(["no-dest-parent"]))

            # 先快照源，再删目标/源，最后写目标（MOVE 时源消失）。
            if source_is_file:
                snapshot_files = [(path, state.files[path])]
                snapshot_collections = []
            else:
                prefix = path.rstrip("/") + "/"
                snapshot_files = [(k, state.files[k]) for k in sorted(state.files)
                                  if k == path or k.startswith(prefix)]
                snapshot_collections = [c for c in sorted(state.collections)
                                        if c == path or c.startswith(prefix)]

            if destination_exists:
                state.delete_tree(destination)
            if move:
                state.delete_tree(path)

            base_suffix = "" if source_is_file else path.rstrip("/")
            if not source_is_file and path != DAV_ROOT:
                state.collections.add(destination)
                state.mtimes[destination] = time.time()
            for collection in snapshot_collections:
                if collection == path and collection == DAV_ROOT:
                    continue
                suffix = collection[len(base_suffix):] if base_suffix else ""
                new_path = destination + suffix
                state.collections.add(new_path)
                state.mtimes[new_path] = time.time()
            for key, data in snapshot_files:
                suffix = key[len(base_suffix):] if base_suffix else ""
                state.store_file(destination + suffix, data)

            status = 204 if destination_exists else 201
            return (status,
                    [],
                    b"",
                    self._detail(["len={}".format(len(body)),
                                  "moved" if move else "copied",
                                  "replaced" if destination_exists else "created"]))

    # -- 控制面（无鉴权，仅回环） ------------------------------------------

    def _route_control(self, method, path, body):
        state = self.server.state
        endpoint = path[len(CONTROL_PREFIX):].strip("/")
        query = parse_qs(urlsplit(self.path or "").query, keep_blank_values=True)
        target = normalize_path(unquote((query.get("path") or [""])[0]))

        if endpoint == "reset":
            state.reset()
            return 200, [("Content-Type", _TEXT)], b"ok", "control"

        if endpoint == "log":
            text = "\n".join(state.log_lines)
            if text:
                text += "\n"
            return 200, [("Content-Type", _TEXT)], text.encode("utf-8"), "control"

        if endpoint == "count":
            return 200, [("Content-Type", _TEXT)], str(len(state.log_lines)).encode("ascii"), "control"

        if endpoint == "file":
            if not target or target == "/":
                return 400, [("Content-Type", _TEXT)], b"missing path\n", "control"
            if method == "POST":
                with state.lock:
                    state.store_file(target, body)
                return 200, [("Content-Type", _TEXT)], b"ok", "control"
            with state.lock:
                if target in state.files:
                    data = state.files[target]
                else:
                    data = None
            if data is None:
                return 404, [("Content-Type", _TEXT)], b"404 Not Found\n", "control"
            return 200, [("Content-Type", "application/octet-stream")], data, "control"

        if endpoint == "etag":
            with state.lock:
                etag = state.etags.get(target)
            if etag is None:
                return 404, [("Content-Type", _TEXT)], b"404 Not Found\n", "control"
            return 200, [("Content-Type", _TEXT)], etag.encode("utf-8"), "control"

        if endpoint == "exists":
            with state.lock:
                found = state.exists(target) if target else False
            return 200, [("Content-Type", _TEXT)], (b"1" if found else b"0"), "control"

        return 404, [("Content-Type", _TEXT)], b"404 Not Found\n", "control"


class StubServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 64


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------


def build_parser():
    parser = argparse.ArgumentParser(
        description="CI-only minimal WebDAV stub server (stdlib only).")
    parser.add_argument("--port", type=int, required=True, help="监听端口（必填）")
    parser.add_argument("--host", default="127.0.0.1",
                        help="监听地址，默认 127.0.0.1（模拟器经 10.0.2.2 访问宿主机回环）")
    parser.add_argument("--user", default="test", help="Basic 鉴权用户名，默认 test")
    parser.add_argument("--password", default="test", help="Basic 鉴权密码，默认 test")
    parser.add_argument("--log", default="/tmp/webdav_stub.log",
                        help="请求日志文件，启动时清空，默认 /tmp/webdav_stub.log")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    state = StubState(args.log, args.user, args.password)
    server = StubServer((args.host, args.port), WebDavStubHandler)
    server.state = state

    print("[{}] LISTEN http://{}:{}{} user={} log={}".format(
        now_iso(), args.host, args.port, DAV_ROOT, args.user, args.log), flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

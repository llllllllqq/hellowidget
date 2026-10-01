#!/usr/bin/env bash
#
# CI 端到端测试编排：先在 runner 上拉起仓库自带的零依赖 WebDAV 桩服务器，
# 再让模拟器里的仪器化测试（SyncE2eInstrumentedTest）打它。
#
# 为什么必须单独放一个脚本文件：
#   ReactiveCircus/android-emulator-runner 会把 `script:` 的**每一行**当成一条独立命令执行
#   （Actions 日志里能看到每行一个 `/usr/bin/sh -c <行>`），因此：
#     * 变量赋值无法跨行生效（PORT=8080 会随那一个 shell 一起消失）
#     * `for` 循环、`if` 块、反斜杠续行都会被拆散
#     * 用的是 /usr/bin/sh（dash），不能用 `set -o pipefail` 等 bash 特性
#   所以把编排收进这个脚本，workflow 里只留一行 `bash .github/scripts/run_webdav_e2e.sh`。
#
# 本地复现同样可用：模拟器/真机连上后直接运行本脚本即可。
set -uo pipefail

PORT="${WEBDAV_STUB_PORT:-8080}"
STUB_LOG="${WEBDAV_STUB_LOG:-/tmp/webdav_stub.log}"
STUB_OUT="/tmp/webdav_stub_stdout.log"
SERVER_PID=""

cleanup() {
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "启动 WebDAV 桩服务器：127.0.0.1:${PORT}（模拟器侧通过 10.0.2.2 访问）"
python3 .github/scripts/webdav_stub_server.py \
  --port "$PORT" --user test --password test --log "$STUB_LOG" > "$STUB_OUT" 2>&1 &
SERVER_PID=$!

READY=0
for _ in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:${PORT}/__control__/count" > /dev/null 2>&1; then
    READY=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

if [ "$READY" != "1" ]; then
  echo "::error::WebDAV 桩服务器未就绪"
  cat "$STUB_OUT" || true
  exit 1
fi
echo "WebDAV 桩服务器已就绪（进程 $SERVER_PID）"

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.webdavUrl="http://10.0.2.2:${PORT}/dav/" \
  -Pandroid.testInstrumentationRunnerArguments.webdavControlUrl="http://10.0.2.2:${PORT}/__control__/" \
  --stacktrace
STATUS=$?

echo "===== WebDAV 桩服务器请求日志（WebDAV 动词实证）====="
cat "$STUB_LOG" || true
exit "$STATUS"

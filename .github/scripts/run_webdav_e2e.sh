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
NUTSTORE_PORT="${WEBDAV_NUTSTORE_STUB_PORT:-8081}"
NUTSTORE_LOG="${WEBDAV_NUTSTORE_STUB_LOG:-/tmp/webdav_stub_nutstore.log}"
NUTSTORE_OUT="/tmp/webdav_stub_nutstore_stdout.log"
SERVER_PID=""
NUTSTORE_PID=""

cleanup() {
  for pid in "$SERVER_PID" "$NUTSTORE_PID"; do
    if [ -n "$pid" ]; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT

echo "启动 WebDAV 桩服务器：127.0.0.1:${PORT}（模拟器侧通过 10.0.2.2 访问）"
python3 .github/scripts/webdav_stub_server.py \
  --port "$PORT" --user test --password test --log "$STUB_LOG" > "$STUB_OUT" 2>&1 &
SERVER_PID=$!

# 第二台桩服务器：打开 --nutstore，按坚果云的真实脾气回非标准 409，
# 专门回归「客户端对坚果云 409 的兼容」（SyncNutstoreQuirkE2eInstrumentedTest）。
echo "启动坚果云模拟桩服务器：127.0.0.1:${NUTSTORE_PORT}（--nutstore）"
python3 .github/scripts/webdav_stub_server.py \
  --port "$NUTSTORE_PORT" --user test --password test \
  --log "$NUTSTORE_LOG" --nutstore > "$NUTSTORE_OUT" 2>&1 &
NUTSTORE_PID=$!

wait_ready() {
  ready=0
  for _ in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:$1/__control__/count" > /dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 0.5
  done
  echo "$ready"
}

READY=$(wait_ready "$PORT")
if [ "$READY" != "1" ]; then
  echo "::error::WebDAV 桩服务器未就绪"
  cat "$STUB_OUT" || true
  exit 1
fi
echo "WebDAV 桩服务器已就绪（进程 $SERVER_PID）"

READY=$(wait_ready "$NUTSTORE_PORT")
if [ "$READY" != "1" ]; then
  echo "::error::坚果云模拟桩服务器未就绪"
  cat "$NUTSTORE_OUT" || true
  exit 1
fi
echo "坚果云模拟桩服务器已就绪（进程 $NUTSTORE_PID）"

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.webdavUrl="http://10.0.2.2:${PORT}/dav/" \
  -Pandroid.testInstrumentationRunnerArguments.webdavControlUrl="http://10.0.2.2:${PORT}/__control__/" \
  -Pandroid.testInstrumentationRunnerArguments.webdavNutstoreUrl="http://10.0.2.2:${NUTSTORE_PORT}/dav/" \
  -Pandroid.testInstrumentationRunnerArguments.webdavNutstoreControlUrl="http://10.0.2.2:${NUTSTORE_PORT}/__control__/" \
  --stacktrace
STATUS=$?

echo "===== WebDAV 桩服务器请求日志（WebDAV 动词实证）====="
cat "$STUB_LOG" || true
echo "===== 坚果云模拟桩服务器请求日志（409 兼容性实证）====="
cat "$NUTSTORE_LOG" || true

# ---------- 顶部导航栏截图：肉眼可核验的产物 ----------
# 断言能证明「图标真的被画出来了」（MainActivityEntryInstrumentedTest 里直接数像素），
# 但截图最直观：流水线每次都在两个 API 上留下真实渲染图，随产物归档。
#
# 主截图来自**测试自己**（saveScreenshot：此刻 Activity 必然在前台且已布局完成）。
# 上一版用 `adb shell am start` + screencap 拍到了桌面而不是应用（keyguard / 时序不可靠），
# 所以这里改为从应用外部私有目录 pull，并保留一条「唤醒 + monkey 拉起」的兜底截图。
SHOT_DIR="${SHOT_DIR:-topbar-screenshots}"
API_LEVEL="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
mkdir -p "$SHOT_DIR"

echo "===== 取回测试自己拍的截图（应用外部私有目录）====="
adb pull /sdcard/Android/data/moe.hellowidget/files/ "$SHOT_DIR/" 2>&1 | tail -3 || true

# 兜底：唤醒并解锁屏幕后再拉起应用截一张
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

launch_and_capture() {
  shot_name="$1"
  adb shell am force-stop moe.hellowidget >/dev/null 2>&1 || true
  adb shell monkey -p moe.hellowidget -c android.intent.category.LAUNCHER 1 2>&1 | tail -2 || true
  sleep 5
  echo "当前前台窗口："
  adb shell dumpsys window 2>/dev/null | grep -m2 -E 'mCurrentFocus|mFocusedApp' || true
  adb exec-out screencap -p > "$SHOT_DIR/${shot_name}_api${API_LEVEL}.png" 2>/dev/null || true
  ls -l "$SHOT_DIR/${shot_name}_api${API_LEVEL}.png" 2>/dev/null || true
}

launch_and_capture topbar

# 「高刘海」RRO 模拟：把顶部系统栏 inset 抬到接近整条导航栏的高度 ——
# 这正是 v7.7 线上事故的条件（旧实现在该条件下标题只剩一条 7px 的缝、4 个按钮全不可见）。
if adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall >/dev/null 2>&1; then
  echo "===== 已启用「高刘海」模拟：顶部系统栏 inset 被抬高，用于复现 v7.7 事故条件 ====="
  launch_and_capture topbar_tall_cutout
  adb shell cmd overlay disable com.android.internal.display.cutout.emulation.tall >/dev/null 2>&1 || true
else
  echo "::warning::高刘海模拟覆盖层不可用，跳过该截图（不影响测试结论）"
fi

ls -l "$SHOT_DIR" || true
exit "$STATUS"

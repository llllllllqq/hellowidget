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

# ---------- 必须显式打开「有硬件键盘时也显示软键盘」 ----------
# ReactiveCircus/android-emulator-runner 的 `enable-hw-keyboard: false` 其实是**空操作**：
# 它只在为 true 时才往 AVD 的 config.ini 追加 `hw.keyboard=yes`，从不会主动写成 no
# （action 源码 emulator-manager.ts：`if (enableHardwareKeyboard) configEntries.push('hw.keyboard=yes')`；
#  同理 `settings put secure show_ime_with_hard_keyboard 0` 也只在 true 时执行）。
# 于是「系统镜像自带的硬件键盘」这一档不会被去掉，命中 Android 默认策略
# `show_ime_with_hard_keyboard=0`：点输入框时**软键盘根本不显示**，
# 应用拿到的 ime insets 恒为 0 —— 而 ImeTracker 仍然会打 onShown、LatinIME 也会 Starting input，
# 所以表现为「输入法看起来正常，但 inset 用例失败」。
#
# 真实取证（v8.0 加 API 36 腿时）：
#   API 35：pIme=294（键盘占 640 里的 294px），我们的根布局 padB=294 —— 通过
#   API 36：pIme=0（窗口高度也没变，仍是 640），padB=48（只剩导航栏）—— 失败
# 两者的 softInputMode、窗口尺寸、isVisible(ime()) 完全相同，差别只在「键盘有没有真的画出来」。
# 打开这个开关后，接了硬件键盘的模拟器也会显示软键盘，ime insets 才会正常出现。
adb shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1 || true

echo "===== 输入法 / 键盘诊断（用于复查上面这条前提）====="
echo "android sdk: $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
echo "show_ime_with_hard_keyboard: $(adb shell settings get secure show_ime_with_hard_keyboard 2>/dev/null | tr -d '\r')"
echo "可用输入法: $(adb shell ime list -s 2>/dev/null | tr '\n' ' ')"
if [ -f "${ANDROID_AVD_HOME:-/home/runner/.android/avd}/test.avd/config.ini" ]; then
  grep -i "hw.keyboard" "${ANDROID_AVD_HOME:-/home/runner/.android/avd}/test.avd/config.ini" \
    || echo "(AVD config.ini 未显式设置 hw.keyboard —— 沿用系统镜像默认值)"
else
  echo "(未找到 AVD config.ini)"
fi

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

echo "===== 取回测试自己拍的截图（测试已复制到 /sdcard/Download/hellowidget-shots）====="
adb pull /sdcard/Download/hellowidget-shots/ "$SHOT_DIR/" 2>&1 | tail -3 || true

# connectedAndroidTest 结束后 AGP 会把应用卸载掉（实测 `monkey` 报 No activities found），
# 因此想要「脚本自己拉起应用截图」就必须先重新安装 debug 包。
DEBUG_APK=app/build/outputs/apk/debug/app-debug.apk
if [ -f "$DEBUG_APK" ]; then
  echo "===== 重新安装 debug 包以便截图 ====="
  adb install -r "$DEBUG_APK" 2>&1 | tail -2 || true
else
  echo "::warning::未找到 $DEBUG_APK，脚本兜底截图可能失败"
fi

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

# 同步相关的应用日志：CI 日志里能直接看到「有没有排上系统重试任务、闸门为什么跳过、
# 同步为什么失败、系统重试任务有没有被执行」。v7.9 那次 setBackoffCriteria 参数顺序事故
# 就是靠这类证据定位的 —— 而 logcat 默认不会出现在 Gradle 输出里。
echo "===== 同步相关 logcat（同步编排 / 前台服务 / 系统重试任务）====="
adb logcat -d -v brief -s SyncRetry:* SyncRetryJob:* SyncManager:* SyncLauncher:* SyncService:* SyncNotifier:* 2>&1 | tail -100 || true

exit "$STATUS"

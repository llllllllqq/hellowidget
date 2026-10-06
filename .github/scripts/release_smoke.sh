#!/usr/bin/env bash
#
# Release 包冒烟：把**真正要发布的那个 release APK** 装进模拟器跑起来。
#
# 为什么需要它：v8.0 把 AGP 升到 9.4.1 之后，release 变体的收缩行为变了 ——
#   * `android.r8.optimizedResourceShrinking` 默认 true：资源收缩完全并入 R8，
#     R8 会同时看代码与资源来决定保留什么；
#   * `android.r8.strictFullModeForKeepRules` 默认 true：`-keep class A` 不再隐含保留默认构造器；
#   * 资源收缩一直是 `isShrinkResources = true`（v7.0 起）。
# 而 CI 里的仪器化测试跑的是 **debug** 包 —— 也就是说「release 包能不能起来」此前完全没有验证。
# 本脚本补上这个盲区：安装 → 启动主界面 → 进程存活 → 无 FATAL EXCEPTION → 截图留证。
#
# 已知不覆盖：桌面小组件的 RemoteViews 真实渲染（需要真实 launcher 宿主，留作后续）。
#
# 由 workflow 的 release-smoke job 调用，前提是该 job 已把 `apks` 产物下载到工作区。
set -uo pipefail

APK="$(find . -name 'app-release.apk' | head -1)"
if [ -z "$APK" ]; then
  echo "::error::未找到 app-release.apk"
  exit 1
fi
echo "release APK: $APK"

# fork PR / 无 secrets 时是未签名包，装不上 —— 与 release job 的判定保持一致，跳过而不是失败。
# 判定用 apksigner 而不是 META-INF/CERT：minSdk ≥ 24 时 AGP 默认关闭 v1（JAR）签名
# （v2/v3 已足够覆盖 API 24+），已签名的包里没有 META-INF/CERT*。
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
if ! "${BT}apksigner" verify "$APK" >/dev/null 2>&1; then
  echo "::warning::release APK 未通过 apksigner 校验（fork PR / 无 secrets 时为未签名包）—— 无法安装，跳过 release 冒烟"
  exit 0
fi
echo "apksigner 校验通过（v1 已按 minSdk 24 关闭，v2/v3 签名块存在即可安装）"

adb logcat -c >/dev/null 2>&1 || true
if ! adb install -r "$APK" 2>&1 | tail -3; then
  echo "::error::release APK 安装失败"
  exit 1
fi

if ! adb shell am start -W -n moe.hellowidget/.MainActivity 2>&1 | tail -5; then
  echo "::error::release 包启动失败"
  exit 1
fi

# 启动 + 首帧 + 一次自动保存（DataStore 初始化）都落定
sleep 6

PID="$(adb shell pidof moe.hellowidget 2>/dev/null | tr -d '\r')"
if [ -z "$PID" ]; then
  echo "::error::release 包启动后进程已消失（R8 可能删掉了必需的类或资源）"
  adb logcat -d -v brief 2>/dev/null | tail -200
  exit 1
fi
echo "release 包进程存活：pid=$PID"

echo "===== release 包崩溃检查 ====="
# 只看 AndroidRuntime 里与我们包名相关或明确 FATAL 的行：
# 直接把整个 logcat 里任何 "E AndroidRuntime" 都算失败会误伤系统进程自己的日志。
if adb logcat -d -v brief 2>/dev/null | grep -E "AndroidRuntime" | grep -E "FATAL EXCEPTION|moe\.hellowidget"; then
  echo "::error::release 包出现崩溃（见上方日志）"
  exit 1
fi
echo "无 FATAL EXCEPTION"

mkdir -p release-smoke
adb exec-out screencap -p > "release-smoke/release_api$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r').png" 2>/dev/null || true
ls -l release-smoke || true

echo "===== release 包元数据（安装后的实际版本）====="
adb shell dumpsys package moe.hellowidget 2>/dev/null | grep -E "versionName|versionCode|targetSdk" | head -4 || true

exit 0

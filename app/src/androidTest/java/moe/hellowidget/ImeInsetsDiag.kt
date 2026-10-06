package moe.hellowidget

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 诊断工具（仅 androidTest）：把「输入法到底有没有真的把窗口顶起来」所需的全部原始数据打出来。
 *
 * 起因：v8.0 把模拟器矩阵加到 API 36 后，两条 IME 用例在 36 上失败、35 上通过，失败点都在
 * `getInsets(ime()).bottom > 0`，而同一视图的 `isVisible(ime())` 在同一时刻却是 true
 * （Logcat 里也能看到 ImeTracker 的 onShown）。两种解释需要被区分开：
 *   A. 平台真的没把 ime inset 发给应用（那么是我们的适配在 36 上失效，必须改产品代码）；
 *   B. 应用收到了并正确应用了（根布局 paddingBottom == 键盘高度），只是**测试查询这个视图**时
 *      读到的值在 36 上语义变了（消费语义/缓存），那么该改的是测试的取值方式。
 * 关键区分点就是 dump 里根布局的 `padB` 是否等于键盘高度。
 */
object ImeInsetsDiag {

    private const val TAG = "ImeInsetsDiag"

    fun dump(activity: Activity): String {
        val content = activity.findViewById<View>(android.R.id.content)
        val layoutRoot = (content as? ViewGroup)?.getChildAt(0)
        val sb = StringBuilder()

        sb.append("sdk=").append(Build.VERSION.SDK_INT)
        sb.append(" softInputMode=0x").append(Integer.toHexString(activity.window.attributes.softInputMode))
        sb.append(" multiWindow=").append(activity.isInMultiWindowMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = activity.windowManager.currentWindowMetrics.bounds
            sb.append(" win=").append(b.width()).append('x').append(b.height())
        }
        sb.append(" decor=").append(activity.window.decorView.height)
        sb.append(" content=").append(content.height)
        layoutRoot?.let { sb.append(" layoutRoot=").append(it.height) }

        appendView(sb, "content", content)
        appendView(sb, "layoutRoot", layoutRoot)
        appendView(sb, "decor", activity.window.decorView)

        val line = sb.toString()
        Log.i(TAG, line)
        return line
    }

    private fun appendView(sb: StringBuilder, name: String, view: View?) {
        if (view == null) {
            sb.append(" | ").append(name).append("=<null>")
            return
        }
        sb.append(" | ").append(name).append('{')
        val compat = ViewCompat.getRootWindowInsets(view)
        if (compat == null) {
            sb.append("compat=null,")
        } else {
            sb.append("cIme=").append(compat.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            sb.append(",cSys=").append(compat.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            sb.append(",cNav=").append(compat.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            sb.append(",cVisIme=").append(compat.isVisible(WindowInsetsCompat.Type.ime()))
            sb.append(",cVisSys=").append(compat.isVisible(WindowInsetsCompat.Type.systemBars()))
            sb.append(',')
        }
        val platform = view.rootWindowInsets
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && platform != null) {
            sb.append("pIme=").append(platform.getInsets(WindowInsets.Type.ime()).bottom)
            sb.append(",pSys=").append(platform.getInsets(WindowInsets.Type.systemBars()).bottom)
            sb.append(",pVisIme=").append(platform.isVisible(WindowInsets.Type.ime()))
            sb.append(',')
        }
        sb.append("padB=").append(view.paddingBottom)
        sb.append(",h=").append(view.height)
        sb.append('}')
    }
}

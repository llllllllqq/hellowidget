package moe.hellowidget

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class HelloWidgetApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 跟随系统深色模式（AppCompat 默认即跟随系统，这里显式声明保证行为一致）
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        // 初始化全局唯一的内容存储（DataStore 单实例，保证同文件无竞争写入）
        ContentStore.init(this)
    }
}

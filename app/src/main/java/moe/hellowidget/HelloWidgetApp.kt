package moe.hellowidget

import android.app.Application

class HelloWidgetApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 初始化全局唯一的内容存储（DataStore 单实例，保证同文件无竞争写入）
        ContentStore.init(this)
    }
}

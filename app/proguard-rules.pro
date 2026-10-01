# 项目专属 R8 规则（release 已开启 isMinifyEnabled + isShrinkResources）
#
# 为什么这里几乎是空的：
#   * 清单中声明的组件（Application / Activity / Receiver / Service）由 AGP 自动生成 keep 规则，
#     不需要手写 —— 原来的 `-keep class moe.hellowidget.** { *; }` 属于多余且有害：
#     它会保留全部代码，使 R8 压缩/混淆彻底失效。
#   * RemoteViews 通过字符串方法名调用的都是 android.view.* 框架方法（setBackgroundColor /
#     setTextSize / setTextColor / setMinimumHeight），框架类不参与混淆，无需 keep。
#   * DataStore 的 Serializer / DataMigration 实现由代码直接引用，R8 按可达性保留。
#   * 布局中的 R.id / R.layout / R.string 都是直接引用，资源收缩会自动保留。

# 保留行号信息（略微增大 APK，换取线上崩溃栈可定位）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

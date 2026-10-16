# 日恒 Android v1.1 兼容性与缺陷复核

## 已修复
1. 移除设置中的五套主题切换，固定默认绿意主题；忽略旧版本主题偏好，保留习惯和打卡数据。
2. 原生 WebView 增加 `onShowFileChooser`，让自定义习惯图标可以调用 Android 系统图片选择器。
3. 小组件不再固定显示 5 行；按桌面分配的高度动态显示 2–5 行，降低内容裁切风险。
4. 小组件最小高度调整为 220dp，行高压缩到 34dp；启用每 30 分钟系统刷新，降低日期跨天不刷新的风险。
5. 保留标准 Android AppWidgetProvider / RemoteViews / PendingIntent 实现，不依赖 ColorOS 私有 API。

## 验证范围
- JavaScript 语法、XML 解析、JSON 清单格式、ZIP 完整性均可在构建前静态验证。
- 当前容器无 Android SDK / Gradle wrapper，未在此处完成 APK 编译。
- 未在 OnePlus 13T / ColorOS 17 真机上执行安装、桌面小组件拖拽、字体缩放、深浅色模式和进程被系统回收后的实测。

## 已知限制
- Android RemoteViews 小组件中的自定义上传图片暂时显示为默认叶子图标；预设 Emoji 正常显示。
- 小组件通过标准 Android AppWidget API 实现，系统和启动器支持基础能力；ColorOS 桌面具体布局、圆角遮罩、刷新策略仍以真机结果为准。

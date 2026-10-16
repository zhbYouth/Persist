# 日恒 Android v1.1（含桌面小组件）

这是可用 Android Studio 打开的原生 Android 项目。主界面运行日恒 v0.9 网页应用的本地副本；原生桌面小组件直接读取共享数据，可在桌面查看今日计划并点按打卡。

## 小组件功能
- 显示今天已完成数量和计划习惯
- 根据小组件高度显示 2–5 项今日习惯
- 点右侧圆圈即可直接打卡/取消打卡
- 点习惯名称或“打开日恒”进入应用
- 小组件打卡与应用数据通过原生 SharedPreferences 同步
- 支持从桌面添加、调整大小

## 项目检查说明
- 主界面 JavaScript 已通过 `node --check` 语法检查。
- 由于当前构建环境没有 Android SDK / Gradle，无法在此环境直接生成或运行验证 APK；项目包含 GitHub Actions 工作流，推送到 GitHub 的 main/master 分支或手动运行 workflow 后，会构建 `app-debug.apk` 并作为构建产物提供下载。

## 构建 APK
1. 用 Android Studio 打开项目根目录。
2. 安装 Android SDK Platform 35 和 JDK 17。
3. 等待 Gradle 同步完成。
4. 选择 Build > Build Bundle(s) / APK(s) > Build APK(s)。
5. APK 通常位于 `app/build/outputs/apk/debug/app-debug.apk`。

也可将整个项目上传到 GitHub，进入 Actions → Build Android APK → Run workflow，完成后下载 `riheng-debug-apk` artifact。

## 测试桌面小组件
安装 APK 后，长按 Android 桌面空白处 → 小组件 → 搜索“日恒” → 添加“日恒打卡小组件”。不同 Android 启动器的操作名称可能略有差异。

## 数据同步与限制
- 网页主界面本地存储仍保存在应用的 WebView 内；原生小组件的数据副本保存在应用私有 SharedPreferences 中，并通过 JavaScript bridge 同步。
- 自定义图片图标在应用内可上传和显示；由于 Android RemoteViews 的兼容性，小组件中暂时将自定义图片显示为 🌿，预设 Emoji 会正常显示。
- 当前版本不含云同步、账号系统或通知提醒。


## v1.1 本轮兼容性调整
- 设置中移除所有主题选择，固定为日恒默认主题；旧版本保存的主题偏好会被忽略，不影响习惯数据。
- Android WebView 增加系统图片选择器支持，修复原生 APP 中点击上传自定义习惯图标无响应的问题。
- 小组件根据桌面分配的高度动态显示 2–5 项，避免固定 5 行在小尺寸下被裁切。
- 小组件最小高度调整为 220dp，并设置 30 分钟系统周期刷新，减少日期跨天后不更新的问题。
- ColorOS 17 / Android 17 的实际真机行为仍需在目标设备安装 APK 后验证。

# Persist

[![最新版本](https://img.shields.io/github/v/release/zhbYouth/Persist?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](../../releases/latest)

**把小事做成日常。** 一个轻量、好看、可以自己定制的 Android 习惯打卡应用，带桌面小组件、统计、每日提醒和可选的云同步。

## 功能
- **今日**：当天计划列表，点圆圈打卡，未完成在上、已完成沉到底部；按住手柄拖动排序。
- **习惯**：自定义名称、图标库图标或自己的图片、重复日（每天 / 每周几 / 自定义）。
- **统计**：周 / 月 / 年三档，完成率、全部完成的天数与连续天数、每日完成率图表；点任一习惯看它的日历和星期分布。
- **日历**：翻看任意一天的完成情况。
- **桌面小组件**：在桌面直接看今日计划并打卡。
- **每日提醒**：设置里选一个时间，还有没完成的习惯才会提醒。
- **账号与云同步**（可选）：邮箱注册 / 登录，数据自动同步，换手机不丢。不登录也能完整使用。
- **备份**：设置里可导出 / 导入数据。

## 下载安装
到 **[Releases](../../releases/latest)** 下载最新的 `Persist-x.y.z.apk` 安装。升级直接覆盖安装，数据不会丢（包名 `com.riheng.app` 与签名保持不变）。
每次推送到 `main`，GitHub Actions 会自动构建并发布新的 Release。

添加小组件：长按桌面空白处 → 小组件 → 搜索「Persist」。

## 技术说明
主界面是 WebView 加载的单页应用（`app/src/main/assets/index.html`），桌面小组件、提醒和文件导出由原生 Kotlin 实现。云同步使用 Supabase（邮箱登录 + 一张按用户隔离的表，建表 SQL 见 [docs/supabase.sql](docs/supabase.sql)）。

自行构建：用 Android Studio 打开项目根目录（Android SDK Platform 35、JDK 17），Build > Build APK(s)；或直接用 GitHub Actions。
> `app/riheng.jks` 是个人自用的固定签名文件，密码写在 `app/build.gradle.kts` 里，仅用于保证覆盖安装，不适合上架，也不要用它签名需要保密的应用。

## 数据与限制
- 数据保存在应用内（WebView 本地存储 + 供小组件读取的原生副本）。**卸载应用会清除数据**，请登录同步或定期导出备份。
- 小组件里自定义图片图标显示为 🌿，图标库图标显示对应 Emoji。
- 只有浅色主题。

## 文档
[更新记录](CHANGELOG.md) · [缺陷复核记录](docs/BUG_AUDIT.md) · [第三方声明](THIRD_PARTY_NOTICES.md)

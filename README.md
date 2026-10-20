# 清屏 (ClearScreen) - 安卓广告拦截与统计应用

## 目录结构
- `app/src/main/AndroidManifest.xml`：权限配置、无障碍服务声明、前台常驻服务与开机广播配置
- `app/src/main/res/xml/accessibility_service_config.xml`：无障碍服务监听事件定义
- `app/src/main/java/com/clearscreen/app/service/ClearScreenAccessibilityService.java`：无障碍跳过广告引擎，自动匹配关键词/类名/ID并点击
- `app/src/main/java/com/clearscreen/app/service/KeepAliveForegroundService.java`：前台保活服务，防后台误杀
- `app/src/main/java/com/clearscreen/app/receiver/BootCompletedReceiver.java`：开机自启动与更新重启广播监听
- `app/src/main/java/com/clearscreen/app/data/InterceptDatabaseHelper.java`：SQLite 本地拦截次数与明细持久化
- `app/src/main/java/com/clearscreen/app/ui/MainActivity.java`：状态看板、权限检测向导、按应用统计明细列表

# VPN OnOff

自动根据 WiFi 连接状态切换 Clash Meta VPN 的 Android 应用。

- WiFi 断开 → 自动开启 VPN
- WiFi 连接 → 自动关闭 VPN

支持亮屏、后台、锁屏等所有场景下的自动切换。

## 前置条件

本应用需要配合以下两个应用使用：

1. **[Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid)** — 需提前安装并完成订阅配置，确保手动启动 VPN 可正常使用
2. **[Shizuku](https://github.com/RikkaApps/Shizuku)** — 用于在锁屏等受限场景下控制 VPN 切换。需提前安装并启动 Shizuku 服务

## 权限设置

安装后打开 APP，界面会显示各项权限状态。请确保以下权限均已授予：

| 权限 | 说明 | 授予方式 |
|------|------|----------|
| **Shizuku 授权** | 核心功能，用于后台/锁屏控制 VPN | 点击 APP 内状态文字，弹窗授权 |
| **悬浮窗权限** | 后台运行所需 | 点击 APP 内状态文字跳转设置 |
| **后台弹出界面** | MIUI/HyperOS 设备专属 | 点击 APP 内状态文字跳转设置 |
| **通知权限** | 前台服务通知 | 首次启动时弹窗授权 |

> 非小米设备不会显示"后台弹出界面"选项。

## 使用方法

1. 确保 Clash Meta 已配置好并能正常使用
2. 确保 Shizuku 已启动且正在运行
3. 打开 VPN OnOff，确认所有权限状态为绿色
4. 点击「开始监听」
5. 完成！APP 会在后台自动根据 WiFi 状态切换 VPN

## 注意事项

- 首次通过本 APP 启动 Clash Meta VPN 时，系统会弹出 VPN 连接确认对话框，点击允许即可，后续不会再弹出
- 服务开启后支持开机自启，无需每次手动启动
- 建议在系统设置中将本 APP 加入电池优化白名单，避免被系统杀后台

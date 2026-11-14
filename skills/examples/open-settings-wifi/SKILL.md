---
name: open-settings-wifi
description: 打开系统设置的 WLAN 页面，读出当前连接的网络
apps: [com.android.settings]
channel: deeplink-then-gui
deeplinks:
  internet: "xiaoman://internet/open"
---
# 打开 WLAN 设置

1. 先用 deeplink 打开 `xiaoman://internet/open`（省略 packageName）。如果已经能读出 WLAN 开关状态和当前网络，直接跳到第 3 步。入口不可用或信息不全时，启动系统设置（com.android.settings）。
2. 点「WLAN」或「Wi-Fi」或「网络和互联网」→「WLAN」；找不到时向下滑动或使用设置里的搜索框搜索「WLAN」。
3. 读出 WLAN 开关状态和当前连接的网络名称，不要切换开关，也不要连接或忘记任何网络。

看到 WLAN 页面后 finish，summary 写开关状态和网络名称。

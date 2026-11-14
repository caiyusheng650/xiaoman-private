---
name: android-system-actions
description: "用公开 Android 系统入口展开快捷设置或通知栏、查看最近任务、锁屏、系统截屏、设置倒计时，或打开网络、音量、显示和蓝牙设置"
channel: deeplink-then-gui
deeplinks:
  quick_settings: "xiaoman://quick-settings/open"
  recents: "xiaoman://recents/open"
  notifications: "xiaoman://notifications/open"
  lock_screen: "xiaoman://lock-screen/lock"
  screenshot: "xiaoman://screenshot/take"
  timer: "xiaoman://timer/set?seconds={seconds}&label={label}"
  internet: "xiaoman://internet/open"
  volume: "xiaoman://volume/open"
  display: "xiaoman://display/open"
  bluetooth: "xiaoman://bluetooth/open"
---
# Android 系统快捷操作

只处理用户明确提出的目标。先选一个匹配的系统入口，使用 `deeplink` 动作传完整的 `uri`，省略 `packageName`。例如：

```json
{"action":{"type":"deeplink","uri":"xiaoman://quick-settings/open","target":"展开快捷设置"}}
```

- 快捷设置、最近任务和通知栏：使用对应入口。若用户只要求打开页面，打开后结束。最近任务可能是切换动作，已展开就不用再执行。
- 锁屏：仅在用户明确要求锁屏时使用。系统接受后结束，不为验证而尝试解锁。
- 截屏：用户要求截取当前画面时使用。系统接受后本轮结束，报告「已请求系统截屏」。若用户还要求后续操作，可给 deeplink 动作加 `"continueAfterScreenshot":true`，继续完成后续步骤。只有确认图片已保存才报告保存成功。
- 倒计时：先将用户给出的时长换成整数秒，范围 1–86400。例如 5 分钟使用 `xiaoman://timer/set?seconds=300`。标签可省略，添加时对标签做 URL 编码。交给系统时钟后检查计时是否已开始，只有仍停在预填页时才点启动。
- 网络、音量、显示和蓝牙：入口只负责打开系统面板。用户要求修改时，先读当前状态，确认目标控件后再操作，并回读结果。若用户仅要求查看，不改变任何设置。

若入口不可用或系统拒绝，重新观察当前画面再用可辨认的系统控件完成这一步。节点可操作时优先用 `nodeId`。找不到可用入口或遇到系统授权、锁屏保护时，请用户接管或说明无法完成。不反复调用同一入口，不使用未登记的内部链接或厂商私有接口。

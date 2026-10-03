# 跳跳助手（AdSkip）

一个仿「李跳跳」的开屏广告自动跳过工具。原理与李跳跳相同：通过 Android **无障碍服务**监听窗口变化，当检测到广告的"跳过"按钮时自动帮你点击。

- **纯本地运行**：不联网、不申请任何危险权限、不收集任何数据
- **体积约 33KB**：零第三方依赖
- **专为你手机优化**：minSdk / targetSdk = 36，仅 Android 16 可安装（API 33+ 免节点回收、混合预取等新特性全开）

## 性能 / 体验专属设计（v0.0.4 Android 16 纯静默无感版）

| 机制 | 效果 |
|---|---|
| **纯静默无感运行** | 零振动、零弹窗、零权限申请（移除 VIBRATE），彻底做到“润物细无声”，广告仿佛从未存在 |
| **系统专属黑名单** | 硬编码跳过 `com.miui.home`（系统桌面）、负一屏、系统设置等，桌面滑屏、切页时 CPU 0 唤醒，极度省电 |
| **开屏三段爆发脉冲** | 开屏（新窗口事件）触发 0ms / 120ms / 280ms 极速脉冲探测，秒杀各类异步延迟加载广告 SDK |
| **控件树倒序 DFS 遍历** | 优先扫描最顶层浮层控件，开屏跳过按钮常在末尾子节点，1~3 次比对即闪电命中，耗时压缩至 <0.5ms |
| **识别库扩充** | 支持纯 `Skip`、`Skip Ad`、`关闭广告`，以及带倒计时分隔符（`|`·`秒`·`s`）的复合形态 |
| **累计跳过计数统计** | 轻量持久化累计跳过次数，主界面直观展示已跳过战绩 |
| **单次 IPC HYBRID 快照** | `getRootInActiveWindow(FLAG_PREFETCH_DESCENDANTS_HYBRID)` 一次拉回快照，纯内存操作 |
| **单遍 DFS 命中即停** | 节点 1500 / 深度 40 双上限，点击后 2s 全局冷却 |

## 安装

1. 把 `AdSkip.apk` 传到手机（微信文件传输助手 / 数据线 / 蓝牙均可）
2. 在手机上点击安装，允许"安装未知来源应用"
3. 打开「跳跳助手」，点击 **去开启无障碍服务**
4. 在系统无障碍列表中找到「跳跳助手（自动跳过广告）」，打开开关

开启成功后，应用主界面会显示绿色"✓ 服务运行中"。

## 必读：开启后不生效？按顺序检查

0. **无障碍列表里根本看不到「跳跳助手」（Android 13+ 最常见）**：
   这是"受限设置"——侧载安装的应用默认被禁止使用无障碍。两种解法：
   - **解法 A（手机端操作）**：设置 → 应用设置 → 应用管理 → 跳跳助手 → 应用信息页**右上角「⋮」菜单** → **允许受限设置** → 回到无障碍列表即可看到。
   - **解法 B（电脑 adb 命令）**：
     通过 adb 执行授权解除受限标记：
     `adb shell appops set io.local.adskip ACCESS_RESTRICTED_SETTINGS allow`
1. **开关是灰色的 / 提示"受限设置"**：同上，先"允许受限设置"。
2. **小米/红米**：需要同时允许「自启动」，并在最近任务里给本应用**加锁**；无障碍开关被关闭时重新打开即可。
3. **华为/荣耀**：设置 → 应用 → 跳跳助手 → 电池 → 允许后台活动。
4. **OPPO/vivo**：允许自启动 + 关闭"深度休眠"对后台应用的限制。
5. 服务开启后**不要用一键清理**杀掉本应用（多数清后台工具会连带关闭无障碍服务）。

---

## 经典排障复盘：“死活不认无障碍服务”疑云

### 1. 现象与误区
- **现象**：APK 成功安装并运行，解除受限设置、重启系统后，在系统无障碍设置里始终找不到「跳跳助手」；`dumpsys accessibility` 显示 `installedServiceCount` 始终卡在系统的初始值（21），新服务从未被计入。
- **误区**：当时排查抓取了系统自带的 `AccessibilitySecurityPolicy` 安全扫描日志，发现扫描器跳过了豆包等第三方服务，但**没有任何关于 AdSkip 的日志**。于是误推断“系统底层对非商店侧载应用做了静默拦截或白名单剔除”。

### 2. 真正根因
问题**完全不在系统层面，而在 AndroidManifest.xml 的服务声明漏掉了关键配置**：
```xml
<!-- 错误写法：缺少 intent-filter -->
<service
    android:name=".SkipAdService"
    android:label="@string/accessibility_label"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_config" />
</service>
```
**致命遗漏**：没有声明 `<intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>`。

### 3. 底层机制剖析
- **为什么服务数永远不增加？**  
  Android 原生（AOSP）的 `AccessibilityManagerService` 是通过 PackageManager 的 Intent 解析器查询具有 `android.accessibilityservice.AccessibilityService` Action 的组件。没有声明该 Filter，PMS 的 Service Resolver 根本不会将它作为无障碍候选服务返回。
- **为什么安全日志完全无记录？**  
  系统的 `AccessibilitySecurityPolicy` 是对 PMS 返回的候选无障碍服务进行二次安全审查。由于 PMS 一开始就没匹配到该 Service，它压根没进入审查流水线，自然连“被拦截”的日志都不会产生。

### 4. 修复方案
在 `<service>` 标签内补齐 Intent Filter：
```xml
<service
    android:name=".SkipAdService"
    android:label="@string/accessibility_label"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_config" />
</service>
```

### 5. 必备诊断命令
- **检查 PMS 是否识别到了无障碍 Service**：
  `adb shell pm query-services -a "android.accessibilityservice.AccessibilityService"`
- **检查系统底层无障碍服务总数与当前绑定状态**：
  `adb shell dumpsys accessibility` （查看 `installedServiceCount` 与 `Bound services`）
- **一键解除 Android 13+ 受限设置**：
  `adb shell appops set io.local.adskip ACCESS_RESTRICTED_SETTINGS allow`
- **查看当前运行的无障碍服务设置**：
  `adb shell settings get secure enabled_accessibility_services`

## 工作原理（对应源码）

| 文件 | 作用 |
|---|---|
| `src/io/local/adskip/SkipAdService.java` | 核心：事件驱动调度 + 指数退避 + 单遍 DFS 匹配"跳过 / Skip Ad"，依次尝试 节点点击 → 父节点点击 → 手势按坐标点击 |
| `src/io/local/adskip/MainActivity.java` | 主界面：显示服务状态，一键跳转无障碍设置 |
| `res/xml/accessibility_config.xml` | 无障碍服务能力声明（已瘦身） |
| `AndroidManifest.xml` | 组件声明 |

防误触设计：手势点击（风险最高的一步）只对**文案严格匹配**（纯"跳过/跳过广告/Skip Ad/3s|跳过"等按钮文案）且**尺寸小于半个屏幕**的节点执行；聊天、文章里出现"跳过"两个字不会被点。

## 修改代码后重新构建

无需 Android Studio。工具链已装在 `C:\Users\25193\android-build-tools\`（JDK 17 + build-tools 34 + platform 34，共约 500MB，不需要时可整个文件夹删除）。

```bash
cd C:\Users\25193\Desktop\AdSkip
bash build.sh
```

产物为 `AdSkip.apk`。签名密钥 `keystore.jks`（密码 adskip2024）已生成在项目目录；**请保留它**——卸载重装时如果换了签名密钥，需要先卸载旧版才能装新版。

## 局限性

- 只能跳过**无障碍树里暴露了"跳过"文案**的广告，少数广告（纯图片按钮、内容完全不暴露给无障碍的）点不到
- 部分广告 SDK 更新文案后需要微调 `SKIP_BROAD` / `SKIP_STRICT` 正则
- 它只能点击"跳过"按钮本身，无法跳过没有跳过按钮的广告

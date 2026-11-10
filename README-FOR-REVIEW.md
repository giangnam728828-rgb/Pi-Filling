# Pi-Filling 0.20.47 — 审查说明

## 这是什么

Android 原生 AI 编码助手，Kotlin + Jetpack Compose。
手机里跑一个 Linux 沙盒（proot + Alpine），通过 Node wrapper 驱动 agent。

- 基线：`fe75bb5`（0.20.47 / versionCode 2470）
- 构建：AGP 8.7.3 / Gradle 8.10.2 / JDK 21 / compileSdk 35 / minSdk 26
- 测试：**234 项单元测试，全绿**

## 现在最需要你判断的问题

### 问题 1：模型分组的设计对不对

用户要的是「**多个独立配置**，各自有服务商、模型、URL、密钥，聊天框里一键切换，不用重填」。

我做的是 `ui/ModelGroup.kt`：

```kotlin
data class ModelGroup(
    val id: String,              // 也是密钥存储槽
    val name: String,
    val providerId: String,
    val model: String,
    val baseUrl: String,
    val apiShape: String,
    val reasoning: Boolean,
)
```

密钥存在 `SecureKeyStore` 的 `group:<id>` 槽。

**关键文件**：
- `ui/ModelGroup.kt` — 数据模型与持久化
- `ui/AppViewModel.kt` 第 159-215 行 — `saveGroup` / `activateGroup` / `deleteGroup`
- `ui/PiFillingApp.kt` 的 `ModelGroupsSheet` / `GroupEditorSheet` — UI

**需要你判断**：
1. `activateGroup` 里调 `setProvider()`，而 `setProvider()` 会重置 `_model.value`——会不会把刚设置的模型冲掉？
2. 密钥和分组的生命周期是否一致（删组时清了密钥，但改 URL 时旧密钥还留在旧槽里）
3. 是否应该保留 `ModelPresets`（旧的仅模型 id 的预设）？现在两套并存

### 问题 2：可选工具链的实现

`sandbox/Toolchain.kt` 定义 6 个工具链，`LinuxSandboxManager.installToolchain()` 执行 `apk add`。

**没在真机联网测过。** 需要你判断：
1. `apk add` 在 proot 里长时间运行（1200 秒超时）会不会被杀
2. `apk info -e` 探测方式是否可靠
3. 安装失败后 `apk` 数据库会不会处于半损坏状态

### 问题 3：设置页结构

现在 6 组：
- 模型配置（1 行，打开分组列表）
- GitHub（Token、工作目录）
- 运行时（沙盒、自动安装、开发工具、诊断、手机控制、清空历史）
- 外观（语言、主题）
- 语音（5 项）
- 关于（存储用量、关于）

**需要你判断**：这个分组是否合理？「运行时」6 项是否太挤？

## 一直没解决的：UI 丑

用户反复说「UI 很丑，跟现在的软件比差得远」。我改了：
- 深色模式文字不可见（`LocalContentColor` 继承问题）→ 修了
- 抽屉透明 → 改成实色
- 卡片「框里套框」→ 移除 shadow 和 border
- 消息气泡配色 → `primary` 改 `primaryContainer`

**但整体观感仍然差。** 我认为问题在于：
1. `PiFillingApp.kt` 是 **4100+ 行单体文件**，聊天/抽屉/设置/16 个弹窗全在一起
2. 没有真正的设计语言——只有零散的 token
3. 配色是「Material 默认 + 两个调整」，没有品牌感

**需要你的意见**：从结构上怎么救？先拆分文件，还是先重做配色/字体？

## 参考

用户提到几个开源 Android App 作为参照：
- `niki914/zafiro` — Open-source BYOK AI agent for Android，Material 3 Expressive
- 还提到 "Mobility Honest"、"Eta"、"Mika"（我没找到确切仓库）

如果你知道这些项目，它们的设置/模型配置界面是怎么组织的，请直接说。

## 代码结构

```
android/app/src/main/kotlin/industries/huginn/pifilling/
├── ui/
│   ├── PiFillingApp.kt        4100+ 行，全部 UI ← 问题所在
│   ├── AppViewModel.kt        ~900 行
│   ├── ModelGroup.kt          新建：分组模型
│   ├── ModelPresets.kt        旧：仅模型 id 的预设（与上一套并存）
│   ├── CustomProviderSettings.kt
│   ├── ChatHistoryStore.kt
│   ├── Markdown.kt / MarkdownView.kt
│   ├── Glass.kt               「毛玻璃」面板
│   └── theme/{Theme,Tokens}.kt
├── sandbox/
│   ├── LinuxSandboxManager.kt 1200+ 行
│   ├── Toolchain.kt           新建
│   ├── ProotExecutor.kt
│   └── meta/                  持久化地基（已实现，spawn 生命周期未挂载）
└── storage/
    ├── SecureKeyStore.kt
    └── StorageUsage.kt        新建
```

## 已知技术债

1. **`PiFillingApp.kt` 4100 行** — 阶段四没拆分，改一处要全局搜
2. **`sandbox/meta/` 未挂载** — `ProcessRegistry`/`EnvironmentGate` 有 200+ 测试但 spawn 登记生命周期未接入（0.19.13–0.19.29 期间因此严重故障，已回退）
3. **两套模型预设并存** — `ModelPresets`（旧）和 `ModelGroup`（新）
4. **版本号方案** — `major*1000+minor*100+patch*10` 跨 minor 会倒退；已加账本校验（`.last-version-code`）但公式本身没改

## 构建

```bash
cd android
./gradlew assembleRelease -Pandroid.aapt2FromMavenOverride=<aapt2>
```

注意：这个项目在 **aarch64 手机**上构建，aapt2 是 x86_64 二进制，通过 `qemu-x86_64` 运行。普通 x86 机器不需要。

## 测试

```bash
cd android && ./gradlew testDebugUnitTest
```

## 不要做的事

- 不引入新依赖
- 不改 `sandbox/meta/` 语义
- 不动四份 `strings.xml` 的同步（values / values-zh / values-zh-rCN / values-zh-rTW，现在各 273 条）

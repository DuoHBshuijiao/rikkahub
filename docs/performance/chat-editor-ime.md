# 长消息编辑时的输入法动画

聊天输入区继续使用 `imePadding()` 跟随键盘。编辑已有消息时，后台消息列表停止键盘滚动补偿和生成过程的自动跟随，用户仍可主动滚动。

`ChatListContentPadding` 在列表测量时读取 Scaffold 的 padding。编辑期间只扣除 IME 超出系统导航栏的高度，保留原有输入区高度、导航栏和消息间距。浮层和搜索定位继续使用完整遮挡范围；搜索定位在执行时读取最新 inset。顶部渐隐的高度读取放在绘制阶段。

`ImeLazyListAutoScroller` 在停用时移除观察，重新启用时以当前 IME 高度为基准。键盘收起的最后一个零高度也参与差值计算，用户滚动期间不抢占滚动。

## 自动验证

- `ChatImeLayoutTest`：通过生产 padding 和滚动组件模拟逐帧 IME 高度，断言编辑时列表锚点、正文和选区不变，且不触发内容组合；检查主动滚动、连续开关、切换高度、重新启用跟随、初始 IME 可见和零导航栏 inset。
- `LongTextEditorInteractionTest` / `FullscreenExtensionEditorTest`：保留长文查找、草稿恢复、选区和全屏往返阅读锚点的回归。
- `MarkdownSearchRelocationTest`：保留长段落与折叠内容的定位回归。
- `LongTextEditorImeSmokeTest`：在 Release 基准包上检查 100 / 50,000 字符及三种背景效果，确认文本长度和键盘开关；不将这类基础交互检查当作性能数据。

## 性能基准

`LongTextEditorImeBenchmarks` 使用独立的内存聊天场景，复用生产 `ChatInput` 和 `ChatList`，不保存测试会话。长文本在 Activity 内生成，避免通过 `am start` 传递超大 Unicode 参数。

默认矩阵：100 / 10,000 / 50,000 字符 × 多段 / 单段 × off / blur / glass。每个场景执行 5 次，每次开关键盘 3 轮，使用 `CompilationMode.Partial()`、`StartupMode.WARM` 和 `FrameTimingMetric`。等待 IME 到达目标高度后，还会等待控件位置稳定，避免下一次点击使用过期坐标。

基准 Activity 仅通过 `src/githubBenchmarkRelease/AndroidManifest.xml` 注册。当前 Baseline Profile 插件会复用 Release 的构建类型 Manifest，因此不能将注册放在 `src/benchmarkRelease/AndroidManifest.xml`。

在配置好匹配 applicationId 的本机 Firebase 文件后，可在 PowerShell 运行完整矩阵：

```powershell
.\gradlew.bat :app:baselineprofile:connectedBenchmarkReleaseAndroidTest `
  '-Prikkahub.abi=arm64-v8a' `
  '-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.baselineprofile.LongTextEditorImeBenchmarks'
```

模拟器改用 `x86_64`。可增加 `editorLength` 和 `editorEffect` 过滤参数，例如：

```powershell
'-Pandroid.testInstrumentationRunnerArguments.editorLength=10000'
'-Pandroid.testInstrumentationRunnerArguments.editorEffect=off'
```

仅检查基准流程时可使用 `editorIterations=1`；正式对比保留默认 5 次。

修改前后使用同一场景、设备、输入法、编译模式和背景设置，分别保存 benchmark JSON 与 Perfetto 轨迹。比较 P95 CPU 帧耗时、P95 帧超时和超时帧比例，并检查列表组合、测量、文本排版及背景效果的耗时。

模拟器结果只用于功能与基准流程验证。最终流畅度仍需在目标真机检查，包含中文候选输入、横屏、不同输入法高度及两种系统导航模式。

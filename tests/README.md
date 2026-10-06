# 测试索引与统一运行入口

## 1. 使用规则

测试按职责保留在原源集/包中，这里只集中**运行入口、分类与证据位置**。使用已有 Gradle、JUnit、NeoForge GameTest 和真实客户端事件夹具，不建立第二套框架。

要求 Java 21。以下测试组属于 NeoForge，脚本在根目录调用 `:neoforge:` 子项目任务。本机依赖/游戏资产已缓存时加 `-Offline`；首次准备环境不要加。组之间串行运行，不同时启动多个写入同一 `neoforge/run/client` 或 `neoforge/run/server` 的进程。客户端组会启动游戏、创建隔离世界并自动退出；不操作用户原有存档。

```powershell
.\tests\run.ps1 -Group baseline -Offline
.\tests\run.ps1 -Group inventory -Offline
.\tests\run.ps1 -Group planning -Offline
.\tests\run.ps1 -Group knowledge -Offline
```

`-DryRun` 只打印映射命令；`-Group all` 顺序执行上述四组。脚本不写入 EULA 或覆盖启动限制；沿用 NeoForge 开发运行环境（原版 Eula 已将开发环境与普通发行专服区分），若实际服务器要求接受 EULA，需本人处理后再运行。任意一步失败立即停止；不要用退出游戏后的进程码代替夹具断言，现有 Gradle 任务还会验证 PASS 标记。

## 2. 分组、底层命令与覆盖

以下底层任务均可在根目录通过 `./gradlew`（Windows 为 `.\gradlew.bat`）调用，并追加 `--offline --console=plain --no-daemon`。IDE 重新导入根 Gradle 项目后，选择对应平台的运行任务。脚本在含 `test` 的组中额外使用 `--no-build-cache`，配合 `cleanTest` 强制实际执行 JUnit，避免将缓存报告当成本轮重跑。

| 组 | 底层 Gradle 参数 | 覆盖 | 不证明什么 |
| --- | --- | --- | --- |
| unit | `:neoforge:cleanTest :neoforge:test` | 模式/状态、手势、输入边界、预算、网络帧、快照接收/传输策略、知识文件、中英文键/格式参数及单行文案；纯 UI 几何的正交路由、遮挡/假接、交叉断口和有界确定性 | 真实菜单渲染、世界事务、局域网 |
| server | `:neoforge:runGameTestServer` | 环境/单步/递归/余料/回滚、见证攻击和差分、身份/续算、随机图、缓存 | 客户端帧、多设备网络延迟 |
| inventory | `:neoforge:runClient -Pm3Smoke` | 原版配方书/筛选/C、实体工作台、切分类、模式和网格/光标返还、三种菜单材料换位/显示稳定、原版药水效果 | 玩家全部装备操作及真实两机体验 |
| planning | `:neoforge:runClient -Pm4Smoke` | 静态目录、筛选复用/调度、完整结论与诊断隔离、背景任务提升/未知终止/NEVER 只读、Shift+C 单一操作/小刷新、自动资源更新/平移保持、混合材料分支、候选/数量/MAX/收支、见证确认、部分、GUI/语言；船展示帧 C 与根成品切换重算 | 所有模组目录、低配/多人高 RTT 的普遍性能保证 |
| knowledge | 连续两次 `:neoforge:runServer -Pm47Smoke` | 同一隔离存档跨进程缓存加载、真实 reload、专服无客户端类污染 | 首次无缓存冷启动（已有缓存时）、不同硬件/I/O 故障全集 |
| baseline | `:neoforge:cleanTest :neoforge:test :neoforge:runGameTestServer :neoforge:build` | 无图形常规回归及 JAR | 人工验收完成 |

运行结果归档在 `run/verification/<时间>-<组>/`：每步控制台日志和相关游戏日志；JUnit 原始报告在 `neoforge/build/test-results/test/`、HTML 在 `neoforge/build/reports/tests/test/`；客户端截图在 `neoforge/run/client/screenshots/`。这些生成物被 Git 忽略。重复截图会覆盖同名文件，需长期留证时连同批次日志另存。GameTest 故障注入中的预期 ERROR 必须结合测试名和最终断言判断。

planning 内的 `m4-sibling-{diamond_pickaxe,repeater}-{missing,real}.png`、`m4-sibling-routing-{lectern,repeater,crafter}-{missing,real}.png` 及 `m4-sibling-routing-smoker-{missing-four,missing-two,real-mixed}.png` 为原图控件的只读离屏样本，检查合并数量、满足/不足材料分支、单输入链、单入点与共享连接观感；不是新增运行入口、实时授权方案或完整交互截图，不替代 C.22–C.23 人工复测。窗口尺寸/裁剪与当前客户端一致，不另写渲染算法；实际生成及通过情况以本轮日志为准。

10-05 新增混合批次的箱子/木棍原控件样本及 `m4-detail-refresh-tooltip.png`、`m4-detail-quantity-three.png`：前两者检查不同材质的准确数量/来源，后两者来自资源改变后的真实详情重开、拖动与悬停渲染。仍不是任意复杂图或真实两设备体验保证，按 C.25 复测。

## 3. 夹具导航

| 位置 | 责任 |
| --- | --- |
| `../neoforge/src` | 按生产包分组的 JUnit；`client/LocalizationTest` 检查实际语言资源的键/参数一致、无内嵌 LF 及退役玩家术语；不保留已退役协议的自循环编解码测试 |
| `client/PlanGraphRoutingTest.java`（JUnit 源集） | 无世界状态的纯 UI 几何反例：正交连接、合法总线、多输入单入点/共享来源单出点、无关共线/遮挡、无法避免交叉的绘制断口、局部路由与有界确定性；不使用 Minecraft Tooltip/renderer，不证明真实窗口观感 |
| `../neoforge/src`、`menu/M3GameTests.java` | 既有环境/菜单/直接合成边界 |
| `execution/M4PlanningGameTests.java`、`M4TransactionGameTests.java`、`M4RandomGraphGameTests.java` | 递归、可逆转换剪枝/改产量反例、全需求缺口数量/有材料起点诊断、账本结果/容量/事务/回滚统计及随机图 |
| `execution/M4DetailsGameTests.java`、`M4InteractionGameTests.java` | 详情/部分意图、令牌、完整见证/空转拒绝、服务器失败证据复用、C/详情缺口一致和失败不消费、成功操作与多批次统计；默认原木解释不绕木头、显式木头选择/桦木产物；钻石/红石跨需求共享批次、已有材料与向上取整、无虚构操作/成本、真实事务单块消耗及六件剩余物品；默认铁锭/块解释止于逆转换、显式选择及实际压缩/拆解保留 |
| `execution/M4MenuResourceGameTests.java` | 原版 2×2/临时背包/实体工作台输入及光标实际取料；结果/非当前网格排除、稳定引用/换位失效、见证零重搜、容量/保护栈及回滚 |
| `execution/M410SearchRegressionGameTests.java`、`M4AdmissionGameTests.java` | 钓鱼竿数量缺口/同核小片、结构省略后保留完整见证/未知不授权部分、静态返还物闭包与合法生产反例；共享 admission 扣除扫描成本，C/详情/确认/见证耗尽零搜索/消耗，完整否定不被诊断超限抹除 |
| `execution/M411CapacityDiagnosticGameTests.java` | 外部原木/一或两空格的缺口一致，普通 C 零消费；显式部分容量拒绝保留网格/光标/外部输入，相同缺口的合法替代路线仍可交付；满包骨粉一批不足空间、两批释放骨头槽的非单调容量；诊断结构截断后仍可找到的部分计划禁止提交/确认，完整见证不被同类截断误拒 |
| `execution/M412BulkPlanningGameTests.java` | 原版 64 原木→32 箱（96 步）、混合原木→42 箱（126 步）；43/64 箱步数超限零展开/零消费/无部分授权；344 已有木板和普通数据包改产量不被下界误拒；真实完整见证/审阅/事务及同一 8 ms 预算观察，保守材料上界与增产/返还/不完整目录反例 |
| `execution/M412WitnessAccountingGameTests.java` | 完整见证复核的多批次路径/祖先和逆配方、整批余料、根产物排除、容器返还、精确组件、溢出与预算边界；不暴露生产测试接口 |
| `planner/M411MissingIdentityGameTests.java` | 缺口比较忽略路径/相同 OR 集合的排序，精确组件仍区分，反序不能掩盖额外材料不足；不新增生产测试接口 |
| `client/recipebook/ClientDiagnosticLifecycleTest.java`（JUnit）、`M4DiagnosticLifecycleScenario.java`（客户端源集） | 结论与诊断分离、同身份未知终止、成功不降级；真实详情提升同一 continuation、失败证据升级和 NEVER 只读边界，沿原 planning 入口运行 |
| `execution/M48ContinuationGameTests.java`、`M4PreviewGameTests.java` | 同核心续算、共享闭包/证据；旧批量 facade 的基线不是现行网络入口 |
| `recipe/M4KnowledgeGameTests.java` | 静态关系与缓存边界 |
| `../neoforge/src` | inventory 唯一入口；历史源集名保留，非 M3 专属测试框架 |
| `client/M4ClientSmoke.java` | planning 唯一入口：准备/浏览、详情/合成、生命周期/展示三个阶段方法 |
| `client/M4BrowsingScenario.java`、`M48SchedulingProbe.java` | 由上述入口驱动的场景/调度观察；前者不新增事件订阅或独立运行开关 |
| `client/M4RepeatCraftScenario.java` | inventory 入口内的四次实际 C 手势，覆盖开/关筛选、显示/列表/页码/会话稳定及权威资源版本推进 |
| `client/M4MenuMoveScenario.java` | inventory 入口末段的小场景；三种菜单使用原版点击网络包移动/拆分主背包、光标与网格材料，固定橡木板帧以隔离不同材质轮播，逐 tick 检查颜色/列表/页码/会话、权威更新和实际 C，不新增运行开关 |
| `client/M4OutputVariantScenario.java` | planning 入口末段的小场景；控制原版动画帧并走真实 C/Shift+C/节点与候选按钮/确认网络，检查桦木船不替换橡木、缺料材质状态、根选择清旧约束/重算收支及 MAX |
| `client/M4DetailQuantityScenario.java` | planning 既有入口末段：真实资源改变后重开详情，拖动已知 MAX 到 3 把镐不等待旧 MAX，保留指针捕获/取消排队操作、精确成本与同作用域见证；实际悬停排队/渲染刷新 Tooltip，后续资源改变使旧见证失效；独立普通大产物反例校验见证虽小、完整投影超界时不替换单份方案槽 |
| `client/M4BulkQuantityScenario.java` | 同一 planning 入口末段：真实 64 橡木原木、Shift+C/MAX 等待和耗时取证、资源未变的刷新不重搜较小数量、拖动已知最大完整数量、固定方案审阅/网络确认，精确扣料和产物、零额外物品、两次新鲜见证验证、零服务端重新搜索；不以冻结时钟测试冒充真实客户端性能 |
| `client/M4MixedMaterialScenario.java` | 原图模块内的混合批次：3 橡木+1 丛林原木合成 2 箱子，1 桦木+2 橡木原木合成 24 木棍；按实际产物/来源拆分并汇总 12+4/8+4 木板，Tooltip 不串材料；锁扣箱共享引用组内 4+1 直接库存合为 5，独立 4/1、生成来源及不同组件不误连，保留全部需求路径、需求/产量/余料及审阅身份 |
| `client/M4GraphPresentationScenario.java` | planning 入口内的只读图投影夹具：批次收支、解析共享来源后的同父等价合并/全部选择路径，不同材料/组件/配方/OR 集合不误合并；截断前缀相同反例及完整证据 4096/总额 16384/同对象复用的隔离目录回归；钻石合并为 3、火把合并为 2，跨父红石保留 2+1、一块来源、六件剩余物品；不同配方/组件/来源隔离、折叠恢复及几何边界；候选卡显式行/无 LF/去 ID、固定和通配输入汇总、船原木叶及手选桦木投影，不新增测试入口 |
| `client/M4GraphRoutingScenario.java` | 同一 planning 入口内的讲台/中继器/合成器缺料及真实路线：已有/不足木板分开、纸/皮革单输入直连、共享红石 2+1 相邻但独立选择、消费者唯一入点/绘制连续；烟熏炉相同完整 OR 缺料 4/2 合并、真实桦木/橡木分别计数；节点/线不遮挡、不相关共线假接、正交交叉绘制断口，以及全部路径/审阅身份/平移/折叠恢复；复用原求解器、冻结目录、投影和图控件，非第二套布局或测试框架 |
| `recipe/M48ClientKnowledgeProbe.java`、`M47ServerSmoke.java` | 客户端目录取证 / knowledge 专服入口 |

GameTest 当前依赖 NeoForge 发现及包内测试接口，继续留在 main 源集；开发客户端夹具不打入 JAR。不能因“所有测试放一起”而暴露核心私有接口或改变发现方式。

## 4. 人工边界与阶段收口

- 当前唯一有效人工清单：[M4 A–L](../docs/18-m4-acceptance.md)。人工测试已结束：此前完整多人、两端重启及 HMCL 实际客户端 A–L 全部通过，登记在验收 2.2；最新修复后单人复测无问题，日志已由用户更名为 `logs/client-M4-single-r.log`，登记在 2.3，本补丁多人未复测。混合 64+64 原木实际制作 42 箱/126 步，符合原 128 步上限；M4 于 2026-10-06 经用户确认正式结束，不要求重做此前整套。用户确认每轮所有机器使用同一份 JAR，具体历史哈希未记录；协议仍为 9，人工/自动与各批次证据分开。
- 人工结果来自用户本地测试，`logs/...` 日志未上传仓库；`run/verification/...` 自动日志、XML 与截图亦仅为本地归档。两目录均由 Git 忽略；文档路径用于本地追溯，不是仓库可下载的附件，本轮不上传这些文件。
- [数据包夹具](datapacks/README.md) 用于真实配方/标签重载；不自动安装到玩家存档。
- 自动计数证明“零服务端逐目标浏览”“见证验证零完整重搜”，玩家不必靠速度猜测或构造恶意包。
- 低配、大目录端到端、1/16/64 容器与 1/2/8 真实客户端高 RTT 矩阵属 M8 扩展观测；不能为了收口把它们标为已通过。
- 前轮 C.25 基线 `run/verification/20261005-015900-907-baseline/`：94 项 JUnit、125 项 GameTest 全通过，XML 已另存 `junit-results/`；planning `20261005-020004-912-planning/`、inventory `20261005-020331-709-inventory/` 全组通过。混合材质、数量拖动、容量诊断和原 UI 的旧证据保留在进展 3.21，不冒称本轮新补丁已验证。
- 本轮新增 1 项 JUnit、11 项 GameTest，覆盖 32/42 箱完整路线、128 步边界、保守材料证书、当前显示见证与共享来源；最新自动执行结果及失败批次详见进展 3.22。更多混材/256 节点回退及所有硬件性能不在有限样本保证范围，2 ms 为协作软预算；未重复 knowledge 跨进程专门组，不把用户完整多人通过计入自动计数。
- 最新同一生产构建：baseline `20261005-211810-470-baseline/` 为 95 项 JUnit（零失败/错误/跳过）、136 项 GameTest 全通过并构建；inventory `20261005-212246-734-inventory/`、planning `20261005-212544-838-planning/` 全组通过。真实 64 原木精确 MAX 32，等待约 552 ms、拖动后约 55 ms，实际消耗 64 得 32 箱、两次见证复验且零服务端重搜；无变化刷新不重新查询较小数量。共享 5 原木和独立 4/1 图样本归档并逐图检查。中间失败保留，复测通过不等于所有负载/冷启动首次请求都不会耗尽；混合 128 原木仍受 128 步表示限制。
- [当前进展与清理证据](../docs/17-m4-progress.md) 记录本轮结果；历史日志保留，不据历史通过替代新构建验收。

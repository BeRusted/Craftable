# 测试索引与统一运行入口

## 1. 使用规则

测试按职责保留在原源集/包中，这里只集中**运行入口、分类与证据位置**。使用已有 Gradle、JUnit、NeoForge GameTest 和真实客户端事件夹具，不建立第二套框架。

要求 Java 21。本机依赖/游戏资产已缓存时加 `-Offline`；首次准备环境不要加。组之间串行运行，不同时启动多个写入同一 `run/client` 或 `run/server` 的进程。客户端组会启动游戏、创建隔离世界并自动退出；不操作用户原有存档。

```powershell
.\tests\run.ps1 -Group baseline -Offline
.\tests\run.ps1 -Group inventory -Offline
.\tests\run.ps1 -Group planning -Offline
.\tests\run.ps1 -Group knowledge -Offline
```

`-DryRun` 只打印映射命令；`-Group all` 顺序执行上述四组。脚本不写入 EULA 或覆盖启动限制；沿用 NeoForge 开发运行环境（原版 Eula 已将开发环境与普通发行专服区分），若实际服务器要求接受 EULA，需本人处理后再运行。任意一步失败立即停止；不要用退出游戏后的进程码代替夹具断言，现有 Gradle 任务还会验证 PASS 标记。

## 2. 分组、底层命令与覆盖

以下底层命令均可直接使用，并追加 `--offline --console=plain --no-daemon`。旧入口保留，不要求 IDE 改启动配置。脚本在含 `test` 的组中额外使用 `--no-build-cache`，配合 `cleanTest` 强制实际执行 JUnit，避免将缓存报告当成本轮重跑。

| 组 | 底层 Gradle 参数 | 覆盖 | 不证明什么 |
| --- | --- | --- | --- |
| unit | `cleanTest test` | 模式/状态、手势、输入边界、预算、网络帧、快照接收/传输策略、知识文件、中英文键/格式参数及单行文案；纯 UI 几何的正交路由、遮挡/假接、交叉断口和有界确定性 | 真实菜单渲染、世界事务、局域网 |
| server | `runGameTestServer` | 环境/单步/递归/余料/回滚、见证攻击和差分、身份/续算、随机图、缓存 | 客户端帧、多设备网络延迟 |
| inventory | `runClient -Pm3Smoke` | 原版配方书/筛选/C、实体工作台、切分类、模式和网格/光标返还、三种菜单材料换位/显示稳定、原版药水效果 | 玩家全部装备操作及真实两机体验 |
| planning | `runClient -Pm4Smoke` | 静态目录、筛选复用/调度、完整结论与诊断隔离、背景任务提升/未知终止/NEVER 只读、Shift+C 单一操作/小刷新、自动资源更新/平移保持、混合材料分支、候选/数量/MAX/收支、见证确认、部分、GUI/语言；船展示帧 C 与根成品切换重算 | 所有模组目录、低配/多人高 RTT 的普遍性能保证 |
| knowledge | 连续两次 `runServer -Pm47Smoke` | 同一隔离存档跨进程缓存加载、真实 reload、专服无客户端类污染 | 首次无缓存冷启动（已有缓存时）、不同硬件/I/O 故障全集 |
| baseline | `cleanTest test runGameTestServer build` | 无图形常规回归及 JAR | 人工验收完成 |

运行结果归档在 `run/verification/<时间>-<组>/`：每步控制台日志和相关游戏日志；JUnit 原始报告在 `build/test-results/test/`、HTML 在 `build/reports/tests/test/`；客户端截图在 `run/client/screenshots/`。这些生成物被 Git 忽略。重复截图会覆盖同名文件，需长期留证时连同批次日志另存。GameTest 故障注入中的预期 ERROR 必须结合测试名和最终断言判断。

planning 内的 `m4-sibling-{diamond_pickaxe,repeater}-{missing,real}.png`、`m4-sibling-routing-{lectern,repeater,crafter}-{missing,real}.png` 及 `m4-sibling-routing-smoker-{missing-four,missing-two,real-mixed}.png` 为原图控件的只读离屏样本，检查合并数量、满足/不足材料分支、单输入链、单入点与共享连接观感；不是新增运行入口、实时授权方案或完整交互截图，不替代 C.22–C.23 人工复测。窗口尺寸/裁剪与当前客户端一致，不另写渲染算法；实际生成及通过情况以本轮日志为准。

## 3. 夹具导航

| 位置 | 责任 |
| --- | --- |
| `src/test/java/org/berusted/craftable/` | 按生产包分组的 JUnit；`client/LocalizationTest` 检查实际语言资源的键/参数一致、无内嵌 LF 及退役玩家术语；不保留已退役协议的自循环编解码测试 |
| `client/PlanGraphRoutingTest.java`（JUnit 源集） | 无世界状态的纯 UI 几何反例：正交连接、合法总线、多输入单入点/共享来源单出点、无关共线/遮挡、无法避免交叉的绘制断口、局部路由与有界确定性；不使用 Minecraft Tooltip/renderer，不证明真实窗口观感 |
| `src/main/java/.../execution/M2GameTests.java`、`menu/M3GameTests.java` | 既有环境/菜单/直接合成边界 |
| `execution/M4PlanningGameTests.java`、`M4TransactionGameTests.java`、`M4RandomGraphGameTests.java` | 递归、可逆转换剪枝/改产量反例、全需求缺口数量/有材料起点诊断、账本结果/容量/事务/回滚统计及随机图 |
| `execution/M4DetailsGameTests.java`、`M4InteractionGameTests.java` | 详情/部分意图、令牌、完整见证/空转拒绝、服务器失败证据复用、C/详情缺口一致和失败不消费、成功操作与多批次统计；默认原木解释不绕木头、显式木头选择/桦木产物；钻石/红石跨需求共享批次、已有材料与向上取整、无虚构操作/成本、真实事务单块消耗及六件剩余物品；默认铁锭/块解释止于逆转换、显式选择及实际压缩/拆解保留 |
| `execution/M4MenuResourceGameTests.java` | 原版 2×2/临时背包/实体工作台输入及光标实际取料；结果/非当前网格排除、稳定引用/换位失效、见证零重搜、容量/保护栈及回滚 |
| `execution/M410SearchRegressionGameTests.java`、`M4AdmissionGameTests.java` | 钓鱼竿数量缺口/同核小片、结构省略后保留完整见证/未知不授权部分、静态返还物闭包与合法生产反例；共享 admission 扣除扫描成本，C/详情/确认/见证耗尽零搜索/消耗，完整否定不被诊断超限抹除 |
| `client/recipebook/ClientDiagnosticLifecycleTest.java`（JUnit）、`M4DiagnosticLifecycleScenario.java`（客户端源集） | 结论与诊断分离、同身份未知终止、成功不降级；真实详情提升同一 continuation、失败证据升级和 NEVER 只读边界，沿原 planning 入口运行 |
| `execution/M48ContinuationGameTests.java`、`M4PreviewGameTests.java` | 同核心续算、共享闭包/证据；旧批量 facade 的基线不是现行网络入口 |
| `recipe/M4KnowledgeGameTests.java` | 静态关系与缓存边界 |
| `src/m3ClientTest/java/.../client/M3ClientSmoke.java` | inventory 唯一入口；历史源集名保留，非 M3 专属测试框架 |
| `client/M4ClientSmoke.java` | planning 唯一入口：准备/浏览、详情/合成、生命周期/展示三个阶段方法 |
| `client/M4BrowsingScenario.java`、`M48SchedulingProbe.java` | 由上述入口驱动的场景/调度观察；前者不新增事件订阅或独立运行开关 |
| `client/M4RepeatCraftScenario.java` | inventory 入口内的四次实际 C 手势，覆盖开/关筛选、显示/列表/页码/会话稳定及权威资源版本推进 |
| `client/M4MenuMoveScenario.java` | inventory 入口末段的小场景；三种菜单使用原版点击网络包移动/拆分主背包、光标与网格材料，固定橡木板帧以隔离不同材质轮播，逐 tick 检查颜色/列表/页码/会话、权威更新和实际 C，不新增运行开关 |
| `client/M4OutputVariantScenario.java` | planning 入口末段的小场景；控制原版动画帧并走真实 C/Shift+C/节点与候选按钮/确认网络，检查桦木船不替换橡木、缺料材质状态、根选择清旧约束/重算收支及 MAX |
| `client/M4GraphPresentationScenario.java` | planning 入口内的只读图投影夹具：批次收支、解析共享来源后的同父等价合并/全部选择路径，不同材料/组件/配方/OR 集合不误合并；截断前缀相同反例及完整证据 4096/总额 16384/同对象复用的隔离目录回归；钻石合并为 3、火把合并为 2，跨父红石保留 2+1、一块来源、六件剩余物品；不同配方/组件/来源隔离、折叠恢复及几何边界；候选卡显式行/无 LF/去 ID、固定和通配输入汇总、船原木叶及手选桦木投影，不新增测试入口 |
| `client/M4GraphRoutingScenario.java` | 同一 planning 入口内的讲台/中继器/合成器缺料及真实路线：已有/不足木板分开、纸/皮革单输入直连、共享红石 2+1 相邻但独立选择、消费者唯一入点/绘制连续；烟熏炉相同完整 OR 缺料 4/2 合并、真实桦木/橡木分别计数；节点/线不遮挡、不相关共线假接、正交交叉绘制断口，以及全部路径/审阅身份/平移/折叠恢复；复用原求解器、冻结目录、投影和图控件，非第二套布局或测试框架 |
| `recipe/M48ClientKnowledgeProbe.java`、`M47ServerSmoke.java` | 客户端目录取证 / knowledge 专服入口 |

GameTest 当前依赖 NeoForge 发现及包内测试接口，继续留在 main 源集；开发客户端夹具不打入 JAR。不能因“所有测试放一起”而暴露核心私有接口或改变发现方式。

## 4. 人工边界与阶段收口

- 当前唯一有效人工清单：[M4 A–L](../docs/18-m4-acceptance.md)。本轮优先 C.24 计算结论与诊断闭环，再完成 C.23 单入线/烟熏炉材料合并和 C.22 配方链有限统一整理、C.20 同父合并/跨层连线/往返解释及 C.19 共享批次数量；C.21 单次软对齐的历史限制由 C.22–C.23 补充/替代。兼顾 C.18 玩家文案、C.17 LF/通配输入/解释链、C.16 缺料叶、C.15 船材质、J.12 光标/输入格换位及原有 UI 回归；协议 9 两端同一 JAR。
- [数据包夹具](datapacks/README.md) 用于真实配方/标签重载；不自动安装到玩家存档。
- 自动计数证明“零服务端逐目标浏览”“见证验证零完整重搜”，玩家不必靠速度猜测或构造恶意包。
- 低配、大目录端到端、1/16/64 容器与 1/2/8 真实客户端高 RTT 矩阵属 M8 扩展观测；不能为了收口把它们标为已通过。
- 前轮 80 项 JUnit/108 项 GameTest 的证据为 `run/verification/20261004-142908-961-baseline/`。本轮计算闭环新增 10 项 JUnit、9 项 GameTest，最终基线 `run/verification/20261004-194449-520-baseline/` 实际执行 90 项 JUnit，失败/错误/跳过均为 0；117 项 GameTest 全通过，XML 已另存 `junit-results/`。最终 planning 为 `run/verification/20261004-193830-831-planning/`，包括诊断生命周期、五轮 887 目标零未知、范围复用和原 UI 回归；13 份图样本已归档，本轮未重新逐图人工验收。inventory `run/verification/20261004-194921-746-inventory/` 完整通过，连续 C 与三种菜单材料换位/显示稳定保留。计算片仍为协作软预算，400 tick 观察最大 20.6277 ms，不宣称 2 ms 硬实时保证。未重跑 knowledge 跨进程专门组；数量不是覆盖率，变更后应核对实际报告而非只对总数。
- [当前进展与清理证据](../docs/17-m4-progress.md) 记录本轮结果；历史日志保留，不据历史通过替代新构建验收。

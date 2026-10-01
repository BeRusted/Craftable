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
| unit | `cleanTest test` | 模式/状态、手势、输入边界、预算、网络帧、快照接收/传输策略、知识文件 | 真实菜单渲染、世界事务、局域网 |
| server | `runGameTestServer` | 环境/单步/递归/余料/回滚、见证攻击和差分、身份/续算、随机图、缓存 | 客户端帧、多设备网络延迟 |
| inventory | `runClient -Pm3Smoke` | 原版配方书/筛选/C、实体工作台、切分类、模式和网格/光标返还、三种菜单材料换位/显示稳定、原版药水效果 | 玩家全部装备操作及真实两机体验 |
| planning | `runClient -Pm4Smoke` | 静态目录、筛选复用/调度、Shift+C 单一操作/小刷新、自动资源更新/平移保持、混合材料分支、候选/数量/MAX/收支、见证确认、部分、GUI/语言 | 所有模组目录、低配/多人高 RTT 的普遍性能保证 |
| knowledge | 连续两次 `runServer -Pm47Smoke` | 同一隔离存档跨进程缓存加载、真实 reload、专服无客户端类污染 | 首次无缓存冷启动（已有缓存时）、不同硬件/I/O 故障全集 |
| baseline | `cleanTest test runGameTestServer build` | 无图形常规回归及 JAR | 人工验收完成 |

运行结果归档在 `run/verification/<时间>-<组>/`：每步控制台日志和相关游戏日志；JUnit 原始报告在 `build/test-results/test/`、HTML 在 `build/reports/tests/test/`；客户端截图在 `run/client/screenshots/`。这些生成物被 Git 忽略。重复截图会覆盖同名文件，需长期留证时连同批次日志另存。GameTest 故障注入中的预期 ERROR 必须结合测试名和最终断言判断。

## 3. 夹具导航

| 位置 | 责任 |
| --- | --- |
| `src/test/java/org/berusted/craftable/` | 按生产包分组的 JUnit；不保留已退役协议的自循环编解码测试 |
| `src/main/java/.../execution/M2GameTests.java`、`menu/M3GameTests.java` | 既有环境/菜单/直接合成边界 |
| `execution/M4PlanningGameTests.java`、`M4TransactionGameTests.java`、`M4RandomGraphGameTests.java` | 递归、可逆转换剪枝/改产量反例、全需求缺口数量/有材料起点诊断、账本结果/容量/事务/回滚统计及随机图 |
| `execution/M4DetailsGameTests.java`、`M4InteractionGameTests.java` | 详情/部分意图、令牌、完整见证/空转拒绝、服务器失败证据复用、C/详情缺口一致和失败不消费、成功操作与多批次统计 |
| `execution/M4MenuResourceGameTests.java` | 原版 2×2/临时背包/实体工作台输入及光标实际取料；结果/非当前网格排除、稳定引用/换位失效、见证零重搜、容量/保护栈及回滚 |
| `execution/M48ContinuationGameTests.java`、`M4PreviewGameTests.java` | 同核心续算、共享闭包/证据；旧批量 facade 的基线不是现行网络入口 |
| `recipe/M4KnowledgeGameTests.java` | 静态关系与缓存边界 |
| `src/m3ClientTest/java/.../client/M3ClientSmoke.java` | inventory 唯一入口；历史源集名保留，非 M3 专属测试框架 |
| `client/M4ClientSmoke.java` | planning 唯一入口：准备/浏览、详情/合成、生命周期/展示三个阶段方法 |
| `client/M4BrowsingScenario.java`、`M48SchedulingProbe.java` | 由上述入口驱动的场景/调度观察；前者不新增事件订阅或独立运行开关 |
| `client/M4RepeatCraftScenario.java` | inventory 入口内的四次实际 C 手势，覆盖开/关筛选、显示/列表/页码/会话稳定及权威资源版本推进 |
| `client/M4MenuMoveScenario.java` | inventory 入口末段的小场景；三种菜单使用原版点击网络包移动/拆分主背包、光标与网格材料，逐 tick 检查颜色/列表/页码/会话、权威更新和实际 C，不新增运行开关 |
| `client/M4GraphPresentationScenario.java` | planning 入口内的只读图投影夹具：多批次收支/返还物汇总、同级子树数量与全部选择路径、不同材料/组件/配方/OR 不误合并、共享批次引用不重复生产；不新增测试入口 |
| `recipe/M48ClientKnowledgeProbe.java`、`M47ServerSmoke.java` | 客户端目录取证 / knowledge 专服入口 |

GameTest 当前依赖 NeoForge 发现及包内测试接口，继续留在 main 源集；开发客户端夹具不打入 JAR。不能因“所有测试放一起”而暴露核心私有接口或改变发现方式。

## 4. 人工边界与阶段收口

- 当前唯一有效人工清单：[M4 A–L](../docs/18-m4-acceptance.md)。本轮优先 J.12 光标/输入格资源与换位无闪烁，兼顾 C.9–14、D.2/D.7 原有 UI 回归；协议 9 两端同一 JAR。
- [数据包夹具](datapacks/README.md) 用于真实配方/标签重载；不自动安装到玩家存档。
- 自动计数证明“零服务端逐目标浏览”“见证验证零完整重搜”，玩家不必靠速度猜测或构造恶意包。
- 低配、大目录端到端、1/16/64 容器与 1/2/8 真实客户端高 RTT 矩阵属 M8 扩展观测；不能为了收口把它们标为已通过。
- 清理批次删除七项退役 JUnit 后为 62 项；连续 C 修复增加三项，共 65 项。可逆转换/统计和缺口诊断修复后有 98 项 GameTest；光标/网格资源修复新增五项，共 103 项。数量不是覆盖率，变更后应核对实际报告而非只对总数。
- [当前进展与清理证据](../docs/17-m4-progress.md) 记录本轮结果；历史日志保留，不据历史通过替代新构建验收。

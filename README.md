# Craftable

> Craft what you can, from what you have.

## 核心主张

只要玩家在当前工作环境中，理论上能够使用现有资源和可用工作站手动完成一组操作，就应允许玩家用一次明确的操作完成它。

Craftable 不增加存储网络、新方块、科技树或资源体系。它把玩家、附近可合法使用的容器与工作站组织成一个临时工作环境，并在服务端安全地规划、执行和解释合成操作。

当前 M0–M3 已验收；M4 已实现普通配方递归、完整/显式部分合成、余料策略及 Shift+C 物品链页，2026-09-10 完成本机自动集成验证，等待单人/局域网人工复测，尚未关闭 M4。单步与递归共用唯一服务端整链事务。炉类、撤销和第三方兼容仍未实现。目标版本为 Minecraft `1.21.1`、NeoForge `21.1.235`、Java 21。

2026-09-12 已实现 [M4.7 共享配方知识与有界持久缓存](docs/19-m4-recipe-knowledge.md)：复用原索引，共享已学会的生产关系，并在当前存档内跨重启保存；库存、可合成状态与执行计划不持久化。自动证据与本次 JAR 见 [验收清单](docs/18-m4-acceptance.md)，人工按 A–I 复测后再关闭 M4。成功路线历史留待 M8 评估。

仅生存/冒险模式在附近有工作台时扩展背包为 3×3；复用原版背包纹理、人物渲染及装备栏位置。创造/旁观无 Craftable 菜单、按钮、预取或 C 操作。背包与实体工作台配方书均增强。左键配方放置沿用原版，只从随身材料填格；使用附近容器材料请按 `C`。原版“选项”页右上角的 Craftable 按钮直达已有配置页。多人测试双方必须使用同一份新 JAR（当前协议版本 **7**，不能与协议 6 混用）。

2026-09-17 已将配方书筛选/悬停、详情、候选和 MAX 接入同一客户端求解器与调度器，复用授权资源下的结论。实际确认仍经服务端新鲜审阅；审阅不同须再次确认。M4.8 完整性能门禁及 M4.9 完整方案见证尚未完成，不能将本批视为全部 M4 收口。最新自动结果与人工复测见 [实施进展](docs/17-m4-progress.md) 和 [验收清单](docs/18-m4-acceptance.md)。

M4 操作：单 C 只做一次完整根配方；首次失败后同目标双击 C 表达部分准备；Shift+C 查看链、换生产配方、选择数量并确认。默认必须容纳成品及全部余料；世界可允许仅溢出余料按原版丢弃，客户端只能收紧。MAX 有界查询未证明最大值时显示已确认下界，不承诺全局最优。人工测试按 [M4 验收清单](docs/18-m4-acceptance.md)。

M4.8 第六批继续在原 2 ms/tick 预算内优化连续派发，每个目标先复用共享闭包；新增五轮动态会话、100 次范围返回、400 实际客户端 tick、授权过期与八份满额传输测试。没有新配方系统或执行路径。额外 1000/5000 普通配方仅用于测试静态构建；大目录/多人/帧耗时完整门禁及 M4.9 仍未完成，新增人工四组见验收清单。

2026-09-13 人工 A 通过，B/C 验收修订：纠正无材料备选路线导致的缺口/解锁误报；详情改为原版进度风格大图、可视化 3×3 配方卡和底部单行操作，Esc 逐层返回。MAX 查询不再使确认按钮周期闪烁；M4 仍需完成修订后的人工复测。

项目标识：

- Mod ID：`craftable`
- 主类：`org.berusted.craftable.Craftable`
- Maven Group：`org.berusted`
- 作者：`BeRusted`
- 许可证：[MIT](LICENSE)

2026-09-15 新增 [M4.8/M4.9 筛选与客户端规划设计](docs/20-m4-filtering-and-client-planning.md)：同资源版本共享闭包与完整判定、同一核心客户端浏览、最小授权快照、服务端完整方案验证。**仅完成文档规划，尚未改代码或生成新 JAR**；部分合成仍由服务端裁定，M4 不关闭。

## 规划文档

- [产品定义](docs/00-product-definition.md)：原则、范围、用户体验和明确不做的事情。
- [实施路线图](docs/01-roadmap.md)：重新划分后的两阶段计划、里程碑和验收门槛。
- [技术架构](docs/02-architecture.md)：环境发现、规划器、事务执行、网络与模块边界。
- [规划与执行语义](docs/03-planner-semantics.md)：递归配方、部分完成、燃料、诊断和撤销。
- [兼容性与配置](docs/04-compatibility-and-config.md)：第三方容器/工作站、JEI 和配置项。
- [测试与风险](docs/05-testing-and-risks.md)：测试矩阵、性能预算和风险登记。
- [M0.2 原型说明](docs/06-m0.2-prototype-notes.md)：当前实现边界、明确限制和客户端验收矩阵。
- [M0.2 验收报告](docs/07-m0.2-acceptance.md)：实际游戏测试结果、根因、参考实现评估和后续约束。
- [M1 严格实施计划](docs/08-m1-implementation-plan.md)：范围预算、依赖方向、不变量和防漂移规则。
- [M1 验收报告](docs/09-m1-acceptance.md)：落地契约、验证结果和留给 M2/M3 的边界。
- [M2 严格实施计划](docs/10-m2-implementation-plan.md)：单步范围、状态机、不变量和防架构偏移预算。
- [M2 验收报告](docs/11-m2-acceptance.md)：自动验证、已知限制和玩家人工复测清单。
- [M3 严格实施计划](docs/12-m3-implementation-plan.md)：原版配方书状态统一、可见页预取和实体 3×3 菜单的分阶段边界。
- [M3 验收清单](docs/13-m3-acceptance.md)：自动验证证据、分类切换崩溃回归、菜单生命周期和局域网人工复测。
- [M4 行为规格](docs/14-m4-spec.md)：递归、批次数量、安全部分完成、容量、诊断和确认语义。
- [M4 严格实施计划](docs/15-m4-implementation-plan.md)：M4.0–M4.9 门禁、架构迁移预算、自动测试及人工复测草案。
- [M4 游玩交互与效率](docs/16-m4-interaction-and-performance.md)：余料溢出掉落、双击 C、可编辑物品链、数量滑槽/MAX 改链提示与集中优化阶段。
- [M4 实施取证](docs/17-m4-progress.md)：自动测试、实际失败、修复与性能边界。
- [M4 人工验收](docs/18-m4-acceptance.md)：单人/局域网复测清单、已知限制和交付记录。
- [M4.7 共享配方知识](docs/19-m4-recipe-knowledge.md)：已实现的原索引共享、静态/动态生命周期、跨重启缓存边界、失败降级与实施门禁。
- [M4.8/M4.9 筛选与客户端规划](docs/20-m4-filtering-and-client-planning.md)：待实现的版本化浏览、隐私/同步、同核心迁移、完整见证验证与性能门禁。
- [ADR-0001：使用原版配方书作为默认前端](docs/adr/0001-vanilla-recipe-book-frontend.md)：记录原版配方书与 JEI 并存的架构决策。
- [ADR-0002：环境快照是短期服务端契约](docs/adr/0002-m1-environment-snapshot-contract.md)：记录缓存、世代和强制刷新决策。
- [ADR-0003：原版式反馈与设置入口](docs/adr/0003-vanilla-feedback-and-settings-surfaces.md)：记录通知分流、配置界面和客户端/世界规则边界。
- [ADR-0004：M2 补偿式单步事务](docs/adr/0004-m2-compensated-single-craft-transaction.md)：记录托管、回滚和提交后通知边界。
- [ADR-0005：M3 配方书与菜单](docs/adr/0005-m3-recipe-book-and-menu.md)：记录状态投影、批次预算、真实槽位及索引安全边界。
- [ADR-0006：M4 有界规划与整链事务](docs/adr/0006-m4-bounded-planning-and-chain-transaction.md)：已采纳，避免通用图引擎和逐步提交导致的架构偏移。
- [ADR-0007：共享配方知识](docs/adr/0007-shared-recipe-knowledge.md)：M4.7 已落地决策，可重建知识允许持久化，资源与执行授权不允许持久化。
- [ADR-0008：授权快照与客户端规划](docs/adr/0008-snapshot-scoped-client-planning.md)：M4.8/M4.9 待实现决策，同核心浏览与完整见证验证的边界。

## 已确定的方向

- 所有资源变更由服务端新鲜授权并事务化提交；当前重新规划，M4.9 完整见证拟改为真实配方/来源/容量验证重建。客户端不能下达槽位写入指令或授权产量；部分判断仍由服务端负责。
- 第一阶段直接增强原版配方书，把它作为默认的搜索、浏览和直接合成入口，保持“游戏本来就应该这样”的原版体验。
- 第一阶段只围绕原版资源、工作站和配方建立可靠体验；JEI 与其他模组兼容后移，不作为核心架构和发布节奏的主导因素。
- 第二阶段接入 JEI 时，原版配方书增强仍会保留；模组工作站必须通过适配器明确声明语义，不对未知机器做猜测执行。
- 不加载范围外或尚未加载的区块，不绕过锁、领地、队伍权限或容器自身访问规则。
- “无等待熔炼”是有意的时间压缩，不完全等同于原版手动操作，因此必须可由服务端配置关闭。

## 设计依据

- [NeoForge 1.21.1 Capabilities](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/)
- [NeoForge 1.21.1 Networking](https://docs.neoforged.net/docs/1.21.1/networking/)
- [NeoForge 1.21.1 Menus](https://docs.neoforged.net/docs/1.21.1/gui/menus/)
- [NeoForge 1.21.1 Screens](https://docs.neoforged.net/docs/1.21.1/gui/screens/)
- [NeoForge 1.21.1 Configuration](https://docs.neoforged.net/docs/1.21.1/misc/config/)
- [NeoForge 1.21.1 Recipe Book Categories API](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/event/RegisterRecipeBookCategoriesEvent.java)
- [JEI 官方仓库与 API](https://github.com/mezz/JustEnoughItems)

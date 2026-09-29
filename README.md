# Craftable

> Craft what you can, from what you have.

## 核心主张

只要玩家在当前工作环境中，理论上能够使用现有资源和可用工作站手动完成一组操作，就应允许玩家用一次明确的操作完成它。Craftable 不增加存储网络、新方块、科技树或资源体系，专注原版体验与减少冗余交互。

## 当前状态

- Minecraft `1.21.1`、NeoForge `21.1.235`、Java 21。
- M0–M3 已验收；M4.0–M4.9 开发已交付，人工验收未完成，**M4 尚未关闭**。当前网络协议 **9**，联机两端必须使用同一新 JAR。
- 支持普通原版配方递归、显式部分准备、容量/余料策略和可编辑物品链。配方知识由原索引共享，服务端有界持久缓存仅保存静态知识。
- 浏览、诊断、详情和 MAX 在客户端使用同一规划核心；完整详情方案由服务端新鲜验证并进入唯一事务。单 C、部分裁定和必要主动回退仍在服务端。
- 生存/冒险附近有工作台时背包扩展为 3×3；装备、人物、药水效果和配方书复用原版。实体工作台也增强；创造/旁观不提供能力。
- 炉类、Z 撤销、实体输入、JEI 和第三方适配尚未实现；低配/大目录端到端/多人高 RTT 扩展测量归入 M8。

## 操作与验证

单 C 制作一次完整根配方；首次失败后同目标双击 C 表达部分准备；Shift+C 查看链、换配方、选数量并确认。MAX 未证明时只显示可行下界。左键仍按原版从随身材料填格；附近容器材料请用 C。选项页 Craftable 按钮进入配置，客户端不能放宽世界规则。

- 开发测试入口：[测试索引](tests/README.md)（分组命令、覆盖范围、输出与人工边界）。
- 当前交付及证据：[M4 进展](docs/17-m4-progress.md)。
- 玩家复测：[M4 验收 A–L](docs/18-m4-acceptance.md)。
- 文档职责：规格描述当前契约，计划描述迁移顺序，进展描述实际状态，历史仅保留证据；不在文档顶部叠加覆盖正文的日期补丁。

## 项目标识

- Mod ID：`craftable`；主类：`org.berusted.craftable.Craftable`。
- Maven Group：`org.berusted`；作者：`BeRusted`；许可证：[MIT](LICENSE)。

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
- [M4.8/M4.9 筛选与客户端规划](docs/20-m4-filtering-and-client-planning.md)：已实现的版本化浏览、隐私/同步、同核心迁移、完整见证验证与性能门禁。
- [ADR-0001：使用原版配方书作为默认前端](docs/adr/0001-vanilla-recipe-book-frontend.md)：记录原版配方书与 JEI 并存的架构决策。
- [ADR-0002：环境快照是短期服务端契约](docs/adr/0002-m1-environment-snapshot-contract.md)：记录缓存、世代和强制刷新决策。
- [ADR-0003：原版式反馈与设置入口](docs/adr/0003-vanilla-feedback-and-settings-surfaces.md)：记录通知分流、配置界面和客户端/世界规则边界。
- [ADR-0004：M2 补偿式单步事务](docs/adr/0004-m2-compensated-single-craft-transaction.md)：记录托管、回滚和提交后通知边界。
- [ADR-0005：M3 配方书与菜单](docs/adr/0005-m3-recipe-book-and-menu.md)：记录状态投影、批次预算、真实槽位及索引安全边界。
- [ADR-0006：M4 有界规划与整链事务](docs/adr/0006-m4-bounded-planning-and-chain-transaction.md)：已采纳，避免通用图引擎和逐步提交导致的架构偏移。
- [ADR-0007：共享配方知识](docs/adr/0007-shared-recipe-knowledge.md)：M4.7 已落地决策，可重建知识允许持久化，资源与执行授权不允许持久化。
- [ADR-0008：授权快照与客户端规划](docs/adr/0008-snapshot-scoped-client-planning.md)：M4.8/M4.9 已落地决策，同核心浏览与完整见证验证的边界。

## 已确定的方向

- 所有资源变更由服务端新鲜授权并事务化提交；单 C/部分由服务端规划，完整详情见证由真实配方/来源/容量验证重建。客户端不能下达槽位写入指令或授权产量；部分判断仍由服务端负责。
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

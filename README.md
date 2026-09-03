# Craftable

> Craft what you can, from what you have.

## 核心主张

只要玩家在当前工作环境中，理论上能够使用现有资源和可用工作站手动完成一组操作，就应允许玩家用一次明确的操作完成它。

Craftable 不增加存储网络、新方块、科技树或资源体系。它把玩家、附近可合法使用的容器与工作站组织成一个临时工作环境，并在服务端安全地规划、执行和解释合成操作。

当前仓库已完成 M0.1、M0.2 和 M1，M1.1 修复与规划也已实现：纯原版工作环境现在具有服务端配置、稳定资源/工作站端点、按玩家短缓存、单调环境世代、结构化请求序号和受限调试命令；手动测试收口修复了环境命令翻译参数，并接入原版风格失败 Toast 与 NeoForge 内置配置页。原版配方书原型仍保留 M0.2 验收中确认的显示缺口，统一状态与实体 3×3 菜单将在 M3 正式解决。目标版本为 Minecraft `1.21.1`、NeoForge `21.1.235`、Java 21。

项目标识：

- Mod ID：`craftable`
- 主类：`org.berusted.craftable.Craftable`
- Maven Group：`org.berusted`
- 作者：`BeRusted`
- 许可证：[MIT](LICENSE)

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
- [ADR-0001：使用原版配方书作为默认前端](docs/adr/0001-vanilla-recipe-book-frontend.md)：记录原版配方书与 JEI 并存的架构决策。
- [ADR-0002：环境快照是短期服务端契约](docs/adr/0002-m1-environment-snapshot-contract.md)：记录缓存、世代和强制刷新决策。
- [ADR-0003：原版式反馈与设置入口](docs/adr/0003-vanilla-feedback-and-settings-surfaces.md)：记录通知分流、配置界面和客户端/世界规则边界。

## 已确定的方向

- 所有资源变更由服务端重新扫描、重新规划并事务化提交；客户端不能指定“从哪个槽位拿多少物品”。
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

# Craftable

> Craft what you can, from what you have.

## 核心主张

只要玩家在当前工作环境中，理论上能够使用现有资源和可用工作站手动完成一组操作，就应允许玩家用一次明确的操作完成它。

Craftable 不增加存储网络、新方块、科技树或资源体系。它把玩家、附近可合法使用的容器与工作站组织成一个临时工作环境，并在服务端安全地规划、执行和解释合成操作。

当前仓库已完成基础初始化，尚未开始功能实现。目标版本为 Minecraft `1.21.1`、NeoForge `21.1.235`、Java 21。

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
- [ADR-0001：使用原版配方书作为默认前端](docs/adr/0001-vanilla-recipe-book-frontend.md)：记录原版配方书与 JEI 并存的架构决策。

## 已确定的方向

- 所有资源变更由服务端重新扫描、重新规划并事务化提交；客户端不能指定“从哪个槽位拿多少物品”。
- 第一阶段直接增强原版配方书，把它作为默认的搜索、浏览和直接合成入口，保持“游戏本来就应该这样”的原版体验。
- 原版配方书与 JEI 是并存的客户端前端，共享同一套“选中目标 → 请求计划 → 三态展示 → C/Z 操作”接口；安装 JEI 不会关闭原版配方书增强。
- 通用物品容器优先使用 NeoForge `IItemHandler` 能力；模组工作站必须通过适配器明确声明语义，不对未知机器做猜测执行。
- 不加载范围外或尚未加载的区块，不绕过锁、领地、队伍权限或容器自身访问规则。
- “无等待熔炼”是有意的时间压缩，不完全等同于原版手动操作，因此必须可由服务端配置关闭。

## 设计依据

- [NeoForge 1.21.1 Capabilities](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/)
- [NeoForge 1.21.1 Networking](https://docs.neoforged.net/docs/1.21.1/networking/)
- [NeoForge 1.21.1 Menus](https://docs.neoforged.net/docs/1.21.1/gui/menus/)
- [NeoForge 1.21.1 Screens](https://docs.neoforged.net/docs/1.21.1/gui/screens/)
- [NeoForge 1.21.1 Recipe Book Categories API](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/event/RegisterRecipeBookCategoriesEvent.java)
- [JEI 官方仓库与 API](https://github.com/mezz/JustEnoughItems)

# 兼容性与配置

## 1. 兼容等级

对外说明应区分四个等级，不能笼统写“支持所有模组容器/机器”。

| 等级 | 含义 | 第一阶段承诺 |
|---|---|---|
| 原生 | 内建适配器并进入持续测试 | 玩家库存、末影库存、原版箱类、工作台、炉类 |
| 通用能力 | 通过 NeoForge 标准能力访问 | 大多数正确暴露 `IItemHandler` 的物品容器 |
| 显式适配 | 对方或本模组提供专用适配器 | 按兼容模块逐个声明 |
| 未知 | 只能发现外观/配方，语义不足 | 不自动执行 |

## 2. 第三方容器

NeoForge `Capabilities.ItemHandler.BLOCK`、`ENTITY` 和 `ITEM` 能覆盖大量存储对象，但第一阶段只默认启用方块容器。实体容器和物品内部容器在第二阶段加入，因为它们有移动、递归、所有权和失效问题。

兼容要求：

- 使用实际侧面查询，遵守 `extractItem(..., simulate)`；
- 处理能力失效；
- 不假定处理器实现 `IItemHandlerModifiable`；
- 不直接改槽位或复制返回的 `ItemStack` 引用；
- 对模拟不纯、返回数量异常或提交不一致的处理器熔断；
- 专用适配器可覆盖通用能力适配器；
- 方块名字相同不等于端点身份相同。

## 3. 权限与领地

能力 API 不负责玩家权限。需要独立 `PermissionProvider` SPI：

```text
ALLOW     明确允许
DENY      明确拒绝，立即停止
PASS      不知道，交给下一个提供者
```

服务端配置决定所有提供者都 `PASS` 时的默认结果。单人可默认允许，专用服务器建议对非原版或疑似受保护区域默认拒绝。

兼容测试至少覆盖：锁箱、旁观者、冒险模式、领地内非成员、队伍共享容器和玩家离线后的所有权变化。Craftable 不应通过直接能力提取绕过正常方块交互被取消的权限。

## 4. 第三方工作站

没有通用安全方案。兼容模块必须说明：

- 哪类配方可执行；
- 实际输入、输出、副产物与容器剩余物；
- 能量/燃料/热量/催化剂成本；
- 机器是否必须空闲、完整、多方块成型或拥有升级；
- 玩家权限；
- 批量与随机输出；
- 模拟与补偿能力；
- 是否允许时间压缩。

随机输出、概率副产物或会改变世界的大型机器，默认不进入递归规划，除非适配器提供确定的服务端执行契约。

## 5. JEI

### 5.1 依赖方式

- JEI API 使用 `compileOnly`；
- 开发运行时可用 `localRuntime` 测试；
- `neoforge.mods.toml` 将 JEI 声明为可选；
- JEI 类只存在于隔离的兼容包，不能被通用类的签名或静态初始化引用；
- 只使用 JEI 公共 API，不依赖内部 GUI 类。

当前 1.21.1 生态常见 JEI 版本属于 19.x，实际锁定版本要在 M0.2 的 JEI 原型时从官方 Maven/发布页确认，并用最低/目标两个版本测试。

### 5.2 交互规则

- JEI 与增强后的原版配方书同时保留，任何一方都不替代另一方；
- 两者显示相同的可合成/部分可合成/不可合成状态、环境状态、计划预览和失败原因；
- 鼠标悬停在 JEI 可识别目标上按 `C`：制作一个；
- 批量制作使用可重新绑定的独立操作，避免硬编码组合键；
- 按 `Z`：请求撤销最近可撤销事务，而不是让 JEI 执行反向配方；
- 若 JEI 只提供材料而无法唯一确定配方，打开配方选择或使用服务端首选配方，并在预览中明确显示。

## 6. 原版配方书增强

Craftable 不开发独立的 JEI 风格主浏览器。第一阶段增强原版配方书：

- 保留原版搜索、图标网格、分页、分类、配方切换、提示和叙述；
- 附近有工作台时以有效 3×3 上下文展示配方；
- 显示三态、所需工作站、当前可制作数量和缺口；
- `C` 直接制作；
- 配方选择记忆。

第二阶段将更多第三方配方、村民交易和生物动作接入配方书，并在背包界面加入或兼容更多工作站 UI。原版配方书与 JEI 共享前端桥接和状态模型，但渲染层互不依赖。

## 7. 配置

### 7.1 M1 已实现的服务端/世界配置

| 键 | 默认 | 范围/值 | 说明 |
|---|---:|---|---|
| `environment.horizontalRadius` | `8` | 1–16 | 水平检测范围；上限控制立方扫描体积 |
| `environment.verticalRadius` | `4` | 0–8 | 垂直检测范围 |
| `environment.previewCacheTicks` | `5` | 0–20 | 只用于预览；创建请求始终强制刷新 |
| `environment.includeEnderChest` | `true` | 布尔 | 需附近存在可用末影箱 |

`loadedChunksOnly=true` 是安全不变量，不开放为可关闭配置。当前扫描形状是水平/垂直分别限界的方盒；它不是欧式球体，不应在 UI 中误标为“欧式距离”。M1 没有为尚未实现的功能注册死配置。

### 7.2 后续里程碑候选配置

| 键 | 建议默认 | 范围/值 | 说明 |
|---|---:|---|---|
| `enabled` | `true` | 布尔 | 总开关 |
| `detectionMode` | `BOX` | `BOX/EUCLIDEAN/LINE_OF_SIGHT/REACHABLE` | M9 加入；保留当前方盒行为并允许选择欧式距离或可达性 |
| `allowBlockInventories` | `true` | 布尔 | 普通方块容器 |
| `allowEntityInventories` | `false` | 布尔 | M9；运输矿车等实体库存 |
| `allowDroppedItems` | `false` | 布尔 | M9；遵守拾取冷却、所有者和实体竞态 |
| `allowLivingEntityActions` | `false` | 布尔 | M10 实体动作总开关，不代替动作子开关 |
| `allowVillagerTrading` | `false` | 布尔 | M10；只有交易执行器完整时才注册 |
| `allowShearing` | `false` | 布尔 | M10；第一批显式白名单生物互动候选 |
| `recipeVisibility` | `ALL` | `ALL/UNLOCKED_ONLY` | 是否尊重配方书解锁作为显示过滤；不改变服务端配方合法性 |
| `instantProcessing` | `true` | 布尔 | 时间压缩总开关；整合包/公服可关闭 |
| `partialExecution` | `AUTO_SAFE` | `NEVER/CONFIRM/AUTO_SAFE` | 部分完成策略上限 |
| `maxCraftBatch` | `64` | 1–4096 | 一次请求的目标批量 |
| `maxPlanDepth` | `12` | 1–64 | 递归深度 |
| `maxPlanStates` | `20000` | 100–200000 | 搜索状态预算 |
| `maxPlanMillis` | `20` | 1–40 | 单次同步规划预算，超出需分时 |
| `scanPositionsPerTick` | `512` | 64–4096 | 环境扫描预算 |
| `unknownPermissionPolicy` | `DENY` | `ALLOW/DENY` | 专用服务器建议 DENY |
| `undoWindowSeconds` | `15` | 0–120 | 0 表示关闭撤销 |
| `auditSuccessfulTransactions` | `false` | 布尔 | 默认只审计异常/拒绝 |

这些键在所属功能实现前不注册，名称和范围仍可由性能测试修正。

### 7.3 M1 已实现的客户端配置

| 键 | 默认 | 说明 |
|---|---:|---|
| `presentation.recipeBookEnhancements` | `true` | 启用当前原版配方书入口；关闭后恢复原版判断 |
| `presentation.detailedFailureFeedback` | `true` | 显示服务端结构化失败原因 |

以下是后续候选项，尚未注册：

| 键 | 建议默认 | 说明 |
|---|---:|---|
| `enableJeiIntegration` | `true` | JEI 存在时启用同等状态与快捷操作，不影响原版配方书 |
| `showRecipeStatus` | `true` | 显示可合成/部分可合成/不可合成状态 |
| `showPlanPreview` | `true` | 显示消耗与工作站 |
| `feedbackDetail` | `NORMAL` | 简洁/普通/调试 |
| `rememberRecipeChoice` | `true` | 记住同一输出的配方偏好 |
| `confirmPartialExecution` | `false` | 服务端允许自动时客户端仍可要求确认 |
| `showEnvironmentIndicator` | `true` | 显示工作台/炉/容器可用状态 |
| `feedbackMode` | `NORMAL` | `NORMAL/QUIET`；控制客户端展示密度，不改变服务端结果 |
| `narrateCraftingFailures` | `true` | 允许旁白读取主动操作的失败原因 |

键位通过标准 KeyMapping 注册并允许玩家在控制设置中修改：

- `Create One`：默认 `C`；
- `Undo Last Craft`：默认 `Z`；
- `Create Max`：默认不绑定，避免与其他模组冲突。

`C` 和 `Z` 只在支持的屏幕上下文、存在有效悬停目标或撤销令牌时消费，不能妨碍聊天框和搜索框输入。

## 8. 配置同步与变更

- 世界规则由服务端拥有；客户端界面显示服务端有效值；
- M1.1 使用 NeoForge 内置 `ConfigurationScreen` 提供“模组 → Craftable → 配置”入口；它复用原版 `OptionsSubScreen` 风格、校验、撤销与默认值机制；
- 单人游戏可以从该界面编辑本地世界服务端配置；远程多人客户端不得用本地 `SERVER` TOML 覆盖服务器规则；
- M3 评估通过公开 `ScreenEvent` 在原版“选项”页加入同一配置页的直达按钮。扫描、交易、掉落物和时间压缩不直接放进“辅助功能”；那里只允许显示、颜色、通知和旁白偏好；
- M1 将完整扫描设置纳入缓存身份，范围、缓存时间或末影箱策略变化后旧快照不能复用；
- 客户端偏好不能放宽服务端限制；
- 配置项使用翻译键、范围说明和重启/即时生效标记；
- 配置迁移需要版本号，未知旧值记录警告后回落到安全默认。

具体界面与反馈决策见 [ADR-0003](adr/0003-vanilla-feedback-and-settings-surfaces.md)。

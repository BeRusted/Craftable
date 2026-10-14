# 开发指南与文档规范

## 1. 阅读顺序与状态来源

1. [README](../README.md) / [玩家指南](22-user-guide.md)：当前能力、操作和明确未实现项。
2. [Pre-M5 计划与验收跟踪](21-pre-m5-transition.md)：本轮顺序、设计合同、设置门禁和实际状态。
3. [技术架构](02-architecture.md)、[规划语义](03-planner-semantics.md)、[配置](04-compatibility-and-config.md)：模块边界与当前/未来分层。
4. [M4 行为规格](14-m4-spec.md)、[客户端规划](20-m4-filtering-and-client-planning.md)：已关闭 M4 的行为基线；历史预算不自动成为新设置的验收证据。
5. [测试索引](../tests/README.md)：唯一运行入口/分组/人工边界；不在本页复制命令或另建测试框架。

M4 于 2026-10-06 正式结束。Pre-M5 已实现默认 128/范围 1–256 的有效世界步数、个人草稿设置及世界只读快照；2026-10-13 授权新增 P5a 直接部分准备规则，新增协议为 11，不与此前 Pre-M5 协议 10 混用。各批次自动回归结果和构建身份见 [验收跟踪](21-pre-m5-transition.md#7-验收跟踪)，辅助功能列表入口的补验与此前主选项页入口的证据分开。7.4–7.7 保留历史身份；7.8 记录两处控件刷新修复的 114 项单元、146 项服务端及受控全量通过，也保留随后自然 stage7 phase1 权威预算 unknown 的失败。后续服务端优化不能抹去该失败或将两种原因混为一项。新增规则构建由 7.10 独立登记，不继承 7.9 的通过。第 8 节基础设置八项已由用户于 10 月 13 日人工通过，日志复核见 7.11；8.1 新名单及后续中文名/UI 尚待复测，Pre-M5 尚未收口，M5 未开始。M6 配方书体验和第二阶段 R1 仅冻结设计，不写成当前支持；M4 历史协议 9 与 128 步证据保持原语境。

2026-10-12 / 13 服务端见证审阅最终优化构建 SHA-256 为 `441620B31B2CAC7D930D6AB2FD1F3332F2C5E215B5EA703740EAC9C8894E1021`：114 项 JUnit、149 项 GameTest 实际执行通过，同一构建的两个独立 JVM 无诊断/无注入/无过滤全量客户端通过，确认问题暂视解决。第一轮局部优化仍失败及计时样本保留在 7.9，不冒称最终版本或唯一历史根因已获性能证明。证据在 `run/verification/20261012-pre-m5-witness-review/`，仅本地归档、未上传，不是独立 JAR 人工联机验收；后来第 8 节用户人工结果另见 7.11，8.1 增补仍待。

P5a 功能验证构建见 7.10：125 项 JUnit、156 项 GameTest，以及全量 planning/inventory 客户端通过；同一生产 JAR SHA-256 为 `EFDAC6992DDFBA383BA018F921A0C375F35025D42AA5B50A060D1B5BFE82EDE5`。目录状态与上下文失效的注入显示测试不替代自然数据重载或真实 LAN；第 8 节用户人工通过见 7.11，8.1 仍待执行。随后中文命名构建单独登记在 7.12，不继承功能构建的 GUI/服务端执行结论；尚未正式收口 Pre-M5。

## 2. 代码入口与不可破坏的边界

| 职责 | 当前入口 | 必须保留的契约 |
|---|---|---|
| 发现与授权 | [`EnvironmentScanner`](../src/main/java/org/berusted/craftable/environment/EnvironmentScanner.java)、[`CraftingSessions`](../src/main/java/org/berusted/craftable/execution/CraftingSessions.java) | 已加载/可使用/有权限；不透明来源绑定真实端点；内容版本不等于扫描号；失权撤销旧引用 |
| 配方知识 | [`CraftingRecipes`](../src/main/java/org/berusted/craftable/recipe/CraftingRecipes.java) | 一份规范化/索引代码；静态知识不含玩家资源与授权；组件与真实组装/返还物验证 |
| 同核规划 | [`CraftSearch`](../src/main/java/org/berusted/craftable/planner/CraftSearch.java)、[`ResourceLedger`](../src/main/java/org/berusted/craftable/planner/ResourceLedger.java)、[`SearchBudget`](../src/main/java/org/berusted/craftable/planner/SearchBudget.java) | 完整/充分否定/未知分离；截断不授权部分；每份来源/生成物精确守恒；片额与累计/结构限制分离 |
| 客户端调度 | [`ClientBrowsePlanner`](../src/main/java/org/berusted/craftable/client/recipebook/ClientBrowsePlanner.java) | 同一核心和动态结果库；前后台共享预算；详情/显示见证/MAX 作用域精确且有界；缓存不是执行授权 |
| 权威入口与见证 | [`CraftingService`](../src/main/java/org/berusted/craftable/execution/CraftingService.java)、[`CraftWitnessValidator`](../src/main/java/org/berusted/craftable/execution/CraftWitnessValidator.java) | 完整见证只提示路线；实际配方/来源/贡献/余料/容量新鲜重建；部分与客户端否定仍由服务端裁定 |
| 唯一提交 | [`CraftingTransaction`](../src/main/java/org/berusted/craftable/execution/CraftingTransaction.java)、[`MainInventoryInsertion`](../src/main/java/org/berusted/craftable/execution/MainInventoryInsertion.java) | 新鲜复验、联合容量、同步提取/发布；失败补偿输入和本次实体；通知/统计在提交后，不逐步提交 |
| 配方书目标 | [`RecipeBookProjection`](../src/main/java/org/berusted/craftable/client/recipebook/RecipeBookProjection.java)、[`RecipeButtonTargetResolver`](../src/main/java/org/berusted/craftable/client/recipebook/RecipeButtonTargetResolver.java) | 分类/搜索/显示不授予解锁；同输出择优与不同输出变体分开；页码稳定；创造/旁观禁能力 |
| 设置与同步 | [`CraftableServerConfig`](../src/main/java/org/berusted/craftable/config/CraftableServerConfig.java)、[`CraftableClientConfig`](../src/main/java/org/berusted/craftable/config/CraftableClientConfig.java)、[`CraftableSettingsScreen`](../src/main/java/org/berusted/craftable/client/CraftableSettingsScreen.java)、[`SettingsPayloads`](../src/main/java/org/berusted/craftable/network/SettingsPayloads.java) | 主页六项个人草稿只在完成保存，控件复用已注册 ConfigValue/元数据；单人未开放 LAN 且 SERVER 已加载才可编辑；远端值仅来自当前连接/世界/序号快照，不授予制作权限 |
| 部分准备许可 | [`DirectPreparationPolicy`](../src/main/java/org/berusted/craftable/config/DirectPreparationPolicy.java)、[`CraftingRecipes`](../src/main/java/org/berusted/craftable/recipe/CraftingRecipes.java)、[`PlanningInput`](../src/main/java/org/berusted/craftable/recipe/PlanningInput.java) | 服务端冻结规则，默认结构基线及配方 ID 覆盖只决定免额外确认；每个实际步骤同义，静态知识/fingerprint 不掺玩家许可，不新增 recipe support |
| 网络边界 | [`CraftingWire`](../src/main/java/org/berusted/craftable/network/CraftingWire.java)、[`CraftablePayloadHandlers`](../src/main/java/org/berusted/craftable/network/CraftablePayloadHandlers.java) | 有界长度/数量/字节/算术；序号/玩家/菜单/世代隔离；世界读写主线程；专服不加载客户端类 |

`PlanView` 和图控件只是有界显示投影：合并显示不得复制消耗、改变需求路径或替代真实计划；不得把布局修复顺带变成求解器/资源账本/事务重构。第二阶段的载体资源设计要先处理根写集与冲突，不能仅向扫描器加一层内容递归。

## 3. 代码注释规范及本轮目标

注释说明意图、所有权、不变量和不明显的安全/预算取舍，不逐行复述 Java。名称不充分的单位、作用域、生命周期及回退原因需要说明；普通 getter、明显的集合循环不要求“凑覆盖率”。只改本轮涉及位置，保留已有有价值说明，不靠大规模改名、提取通用框架或改核心算法来完成注释任务。

Pre-M5 已在本轮涉及位置补充以下关键说明；这些也是后续维护注释的范围，不等于所有历史类已逐行整理或验证通过：

- 配置定义：默认/范围/单位/所有权、即时生效或重启、旧键诊断用途与迁移行为。主页六项个人选项、世界基础规则与直接部分准备子页的标题、Tooltip、默认值、范围与枚举集合来自已注册 `ConfigValue`/元数据；原版控件只保留有界草稿，不复制一套定义或持久化模型。150 像素控件用短标题/短值，完整规则留在共享 Tooltip，多选策略按选项逐项换行，普通/专业页共用翻译定义；世界当前值只取权威回复，不能由本地 SERVER 值补齐。
- 部分准备许可：`DirectPreparationPolicy` 只表达免额外确认的用户风险选择。CUSTOM 沿 `Entry.safePreparation` 原输入/产量/组件结构基线，两张完整配方 ID 覆盖表中需确认优先；ALL_SUPPORTED/REQUIRE_CONFIRMATION 切换不删除名单。每表 128 项、每 ID 128 字符，规范化排序去重；未知合法 ID 只保留、不新增执行器。`CraftingRules`/`PlanningInput`/`CraftingRecipes` 冻结同一不可变值，搜索与真实完整见证按实际步骤统一判定；不改静态 Entry 的结构默认、知识缓存/fingerprint，不按输出物品模糊放行。模式/名单变化失效动态授权和 token，不能放宽单次 C、NEVER/CONFIRM、未知、来源/组件/容量/实际钩子及原预算；M5 后按真实执行契约接入，不另造通用策略框架。
- 规则捕获/快照身份：一次读取有效规则避免重载拆分；步数改变为何使旧证据、MAX、显示见证及确认失效。
- 搜索/见证/编码边界：有效世界步数与绝对表示上限不同，不能增大时间预算或忽略 64 KiB；截断为何不能证明缺料/部分权限。
- 服务端见证审阅优化：来源绑定只读取一次防御副本；真实组装直接构造带已验证 provenance 的步骤，避免第二次包装；路径/余料分组、安全准备标记、可用栈与容量端点集合用等价循环。投影按步骤/操作缓存副本、共享不可变路径正则且 matcher 独立、空候选路径不构建多余上下文，顺序/身份/边界保持原义。仍执行 fresh、实际 `matches/assemble/getRemainingItems`、来源/贡献/祖先/逆配方/整批余料/容量验证，不新增缓存、替代搜索或自动重试，不放宽原 8 ms/16 ms 合作额度。`PreM5WitnessReviewGameTests` 三项回归：原期限内精确 fingerprint/收支且零重搜；验证中耗尽后未知/无 token/背包无损；真实操作/非空返还物副本隔离与显示路径/空候选边界。逻辑检查点时钟与同一 remaining supplier 不补额，不用不受支持计数子类或冻结 fresh 权限，亦不冒充墙钟性能。
- 设置 UI：主页直接显示六项个人选项，不保留个人二级页；单人未开放 LAN 且 SERVER 配置已加载才打开基础规则及直接部分准备入口的世界草稿页，远程/LAN 仅打开只读查看页，未进世界的普通入口不可用。直接部分准备子页复用三个注册 ConfigValue，按产出物品 ID/配方 ID 排序，可搜索名字/物品 ID/配方 ID；每个配方提供默认/允许/需确认三态及结构默认/成本 Tooltip，不另造折叠组或主配方浏览器。合法未知 ID 可手动加入并明确暂不生效。NeoForge 专业配置同样使用实际注册元数据与原生 UNDO，名单采用有界 ID 编辑器，因为 NeoForge 235 的普通列表元素校验不能独立证明总项数边界；两边均使用整表校验，不另建 TOML 或绕过限制。完成校验并只保存已改项，取消/Escape 不保存；编辑期间上下文变化或开放 LAN 立即禁用保存。Mods 专业配置保留原生权限，专业 SERVER 配置未加载时同样不能编辑，不提供未来世界模板编辑；专业页更改不由普通页取消撤销，返回新主页时未改项刷新同 spec 的新值、已改项保留未保存意图。未同步不能伪装远端真值；返回已初始化父页面不会重跑 `init()`，规则失效后需单次重新读取；标题宽度不能冻结旧语言。默认值使用新草稿页，避免原生保留布局累积重复控件；枚举按钮由原版添加标题，不能再重复拼接。
- 设置入口：“配方书”（英文 Craftable）作为 150 像素宽的导航按钮加入原版辅助功能设置的可滚动列表，中文主页为“配方书设置”；内部 `craftable` ID 与英文名称不变。末行只有一个半宽控件时补右侧，满行或全宽条目后新开左侧，不依赖固定原版项目或拆行。窄 `OptionsListEntryAccessor` 只改 children，保持原 `OptionEntry` 与原控件身份及 options map，不能破坏 `findOption`/`applyUnsavedChanges`；原生 children 同时驱动渲染、事件、旁白。打开独立设置页，返回辅助功能页；不向主 `OptionsScreen` 插入按钮或调整 Done/footer，也不把个人选项或世界规则内联为原版辅助功能条目。重建或缩放时不能重复追加入口，不接管其他原生控件。
- footer 与世界页显示：个人页、editable 世界页及直接部分准备编辑页从左至右均为“取消 → 默认值 → 完成”，分别为放弃草稿、重置草稿与校验保存，位置一致不改变默认值/保存语义；专业页维持 NeoForge 自身 `UNDO → RESET → Done`，不将原生 UNDO 当作普通草稿取消。editable 不放常驻来源或重读控件；read-only 为“重新读取规则 → 完成”并排半宽 footer，列表不放来源文字，读取状态、来源和错误通过重读 Tooltip 与旁白提供。pending 禁止重复请求，超时/换上下文仍不得偷用本地 SERVER 值或接受旧序号；精简显示不能削弱所有权、请求限流或新鲜权限校验。中文共享多选策略 Tooltip 避免句号独占行，不另外改字体换行器或复制语言定义；三个旧注册枚举的历史排版通过不替代新增模式/名单的验证。
- 生命周期清理：重连、切世界、重载、资源/容量/策略变化分别清什么；不能把上一作用域的完整见证当成下一作用域的授权。

有效世界步数与固定表示上限分开：搜索冻结 `maxPlanSteps`，续算/诊断不重设，负搜索证据只在相同限制复用；`HARD_MAX_STEPS=256` 只保护计划/解码表示，不授权世界执行。见证、64 KiB 编码、4 MiB 搜索保留状态估算上限及原 CPU/状态预算独立，调整世界步数不放宽它们。MAX 的真实成功结果在后续前台轮次投影/编码，该轮计入公平调度，换意图/关闭/作用域改变释放待投影槽。

`previewCacheTicks` 只保留为有界诊断旧键，0 禁用命令快照复用，普通设置页隐藏；不能为让旧键生效把常规 `fresh` 改成缓存授权。配置 section 缺失/非法值沿注册 spec 校正，远程规则发现只读且不建立第二套配置来源。

阶段计时开关 `-PwitnessTiming` 默认关闭，沿既有客户端测试入口使用（命令见测试索引）。仅局部 long 记录扫描、准备、投影/候选及返回时点，不创建诊断对象、不预初始化业务、不读取预算/opaque supplier 或添加 alive/enter 检查；结果决定后输出，但仍可能消耗同 tick 后续请求的共享 admission，不是无扰动性能证据。早期完整 `witnessTrace` 的接口/回调/observer 预初始化路径已从最终代码移除，样本及对应构建保持历史身份。优化前 profile-2 首次三步 replay 4.2136 ms / 总调用 8.0815 ms，第一轮优化 profile 为 3.0876 ms / 6.5245 ms；这些是局部取证，不是统计分布或最终构建/多机保证。合作式预算不能抢占单次钩子、最后检查后的 offer 或返回工作，不能将 8 ms 写成整调用的硬墙钟上限。最终自然回归必须关闭诊断及受控租约注入，独立登记结果。

未来 R1 的注释必须贴近实际载体绑定/写回/恢复代码，解释根身份、内部路径、钥匙准入、载体与内容互斥、环/上限和父子写回顺序；现在只冻结设计，不提前创建空 API 或实现。

## 4. 文档职责与更新规则

| 文档类型 | 负责内容 | 更新方式 |
|---|---|---|
| 玩家入口 | 可用能力、操作、玩家可理解的限制 | 只写当前结果，未来功能带明确阶段/未实现标签 |
| 规格/架构/配置 | 行为与安全合同、当前真值/后续方向 | 原位修正对应小节，不在顶部累积覆盖正文的日期补丁 |
| 实施计划 | 顺序、预算、范围、验收门禁 | 未验证保持待实施/待验证，不预填成功 |
| 进展/验收 | 实际构建、失败、修复、测试和人工证据 | 证据来自真实执行；失败保留；不同构建/加载器/自动人工不混记 |
| ADR/历史 | 当时决策、迁移和批次取证 | 不重写旧事实；新决策明确替代范围并链接当前合同 |
| 测试索引 | 分组、少量运行入口、命令、边界 | 不把所有测试类硬搬进一个目录，不复制第二份命令集合 |

历史 M4 的 128 步、协议和测试数字保留其原构建语境；新规则不反向篡改历史。只读本地日志路径不是仓库下载附件，不造哈希/数字或上传未经授权私有日志。当前说明冲突时先读代码和当前验收来源，再原位修正；不能用旧计划承诺覆盖新实现。

## 5. 修改与验证原则

- 按 Pre-M5 顺序一次完成一个有界职责，保持工作树中他人的改动；死代码清理只删除有引用/入口证据的退役内容。
- 新语义沿同核心/同配置/同事务和既有测试组验证，不暴露私有生产入口以方便测试。
- 步数配置需同时验证服务端、客户端、见证、投影、解码与内存限制；“能编译”不等于 256 步已获批准。
- 检查专用服务端类加载隔离；界面只用值快照，不持有活容器或执行能力。
- 调度/预算优化登记真实首次冷路径与已知结果复用，不以冻结时钟、提高预算或隐去未知冒充速度改善。
- 验证及人工结果回写 Pre-M5 跟踪表，M5 必须在本轮前置门禁关闭后另行开始。

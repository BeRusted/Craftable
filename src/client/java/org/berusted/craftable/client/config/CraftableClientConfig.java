package org.berusted.craftable.client.config;

/**
 * 客户端配置访问入口。
 *
 * <p>本类必须保持零外部模组依赖：RecipeBookProjection、ClientPayloadHandler 等
 * 核心逻辑在 malilib 未安装时也会加载并调用这里的方法。
 * malilib 提供的图形化配置见 {@code client/compat/malilib/} 包。
 */
public final class CraftableClientConfig {

    private CraftableClientConfig() {
    }

    public static boolean recipeBookEnhancementsEnabled() {
        return true;
    }

    public static boolean detailedFailureFeedbackEnabled() {
        return true;
    }

    public static boolean unlockedOnly() {
        return false;
    }
}

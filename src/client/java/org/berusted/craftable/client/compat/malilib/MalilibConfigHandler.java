package org.berusted.craftable.client.compat.malilib;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import org.berusted.craftable.client.config.CraftableConfigHandler;

/**
 * malilib 界面与 {@link CraftableConfigHandler} 配置核心之间的同步桥：
 * 配置值的唯一权威来源是配置核心，malilib 选项只是它们在界面里的投影。
 * 仅在 malilib 安装时由 {@link MalilibCompat} 加载，严禁被其他类直接引用。
 */
public class MalilibConfigHandler implements IConfigHandler {

    private static final String GENERIC_KEY = "config.generic";

    /**
     * 把配置核心的当前值投影到 malilib 选项上（打开界面前显示的是真实生效值）。
     */
    @Override
    public void load() {
        Generic.recipeBookEnhancements.setBooleanValue(CraftableConfigHandler.recipeBookEnhancementsEnabled());
        Generic.detailedFailureFeedback.setBooleanValue(CraftableConfigHandler.detailedFailureFeedbackEnabled());
        Generic.unlockedOnly.setBooleanValue(CraftableConfigHandler.unlockedOnly());
    }

    /**
     * 把 malilib 界面上修改后的值逐项写回配置核心并落盘。
     */
    @Override
    public void save() {
        CraftableConfigHandler.update(CraftableConfigHandler.Option.RECIPE_BOOK_ENHANCEMENTS,
                Generic.recipeBookEnhancements.getBooleanValue());
        CraftableConfigHandler.update(CraftableConfigHandler.Option.DETAILED_FAILURE_FEEDBACK,
                Generic.detailedFailureFeedback.getBooleanValue());
        CraftableConfigHandler.update(CraftableConfigHandler.Option.UNLOCKED_ONLY,
                Generic.unlockedOnly.getBooleanValue());
    }

    public static class Generic {
        public static final ConfigBoolean recipeBookEnhancements = new ConfigBoolean("recipe_book_enhancements", true, "config.generic.comment.recipe_book_enhancements").apply(GENERIC_KEY);
        public static final ConfigBoolean detailedFailureFeedback = new ConfigBoolean("detailed_failure_feedback", true, "config.generic.comment.detailed_failure_feedback").apply(GENERIC_KEY);
        public static final ConfigBoolean unlockedOnly = new ConfigBoolean("unlocked_only", false, "config.generic.comment.unlocked_only").apply(GENERIC_KEY);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                recipeBookEnhancements,
                detailedFailureFeedback,
                unlockedOnly
        );
    }

}

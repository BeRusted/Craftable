package org.berusted.craftable.client.compat.malilib;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import org.berusted.craftable.planner.CraftRequest;
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
        Generic.allowSurplusDrops.setBooleanValue(CraftableConfigHandler.allowSurplusDrops());
        Generic.doublePressMillis.setIntegerValue(CraftableConfigHandler.doublePressMillis());
        Generic.partialPolicy.setOptionListValue(PartialPolicyOption.valueOf(CraftableConfigHandler.partialPolicy().name()));
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
        CraftableConfigHandler.update(CraftableConfigHandler.Option.ALLOW_SURPLUS_DROPS, Generic.allowSurplusDrops.getBooleanValue());
        CraftableConfigHandler.update(CraftableConfigHandler.Option.DOUBLE_PRESS_MILLIS, Generic.doublePressMillis.getIntegerValue());
        CraftableConfigHandler.update(CraftableConfigHandler.Option.PARTIAL_POLICY,
                CraftRequest.PartialPolicy.valueOf(Generic.partialPolicy.getOptionListValue().getStringValue()));
    }

    public static class Generic {
        public static final ConfigBoolean recipeBookEnhancements = new ConfigBoolean("recipe_book_enhancements", true, "config.generic.comment.recipe_book_enhancements").apply(GENERIC_KEY);
        public static final ConfigBoolean detailedFailureFeedback = new ConfigBoolean("detailed_failure_feedback", true, "config.generic.comment.detailed_failure_feedback").apply(GENERIC_KEY);
        public static final ConfigBoolean unlockedOnly = new ConfigBoolean("unlocked_only", false, "config.generic.comment.unlocked_only").apply(GENERIC_KEY);

        public static final ConfigBoolean allowSurplusDrops = new ConfigBoolean("allow_surplus_drops", true,
                "config.generic.comment.allow_surplus_drops").apply(GENERIC_KEY);
        public static final ConfigInteger doublePressMillis = new ConfigInteger("double_press_millis", 350, 150, 800,
                "config.generic.comment.double_press_millis").apply(GENERIC_KEY);
        public static final ConfigOptionList partialPolicy = new ConfigOptionList("partial_policy", PartialPolicyOption.EXPLICIT_SAFE,
                "config.generic.comment.partial_policy").apply(GENERIC_KEY);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                recipeBookEnhancements,
                detailedFailureFeedback,
                unlockedOnly,
                allowSurplusDrops,
                doublePressMillis,
                partialPolicy
        );
    }

    private enum PartialPolicyOption implements IConfigOptionListEntry {
        NEVER, CONFIRM, EXPLICIT_SAFE;
        @Override public String getStringValue() { return name(); }
        @Override public String getDisplayName() {
            return fi.dy.masa.malilib.util.StringUtils.translate("config.craftable.partial_policy." + name().toLowerCase(java.util.Locale.ROOT));
        }
        @Override public IConfigOptionListEntry cycle(boolean forward) {
            return values()[Math.floorMod(ordinal() + (forward ? 1 : -1), values().length)];
        }
        @Override public IConfigOptionListEntry fromString(String value) {
            try { return valueOf(value); }
            catch (IllegalArgumentException ignored) { return EXPLICIT_SAFE; }
        }
    }
}

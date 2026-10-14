package org.berusted.craftable.client;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.berusted.craftable.config.ConfigSettings;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.DirectPreparationPolicy;
import org.berusted.craftable.planner.CraftRequest;

/** A presentation adapter, not another config definition. All domains and
 * defaults come from the same registered values used by NeoForge's editor. */
final class NativeConfigOption<T> {
    private final ModConfigSpec.ConfigValue<T> configValue;
    private final OptionInstance<T> option;

    private NativeConfigOption(ModConfigSpec.ConfigValue<T> value, OptionInstance<T> option) {
        this.configValue = value;
        this.option = option;
    }

    ModConfigSpec.ConfigValue<T> configValue() { return configValue; }
    OptionInstance<T> option() { return option; }

    static List<ModConfigSpec.ConfigValue<?>> definitions(ModConfigSpec spec) {
        // Only legacy diagnostic TTL is intentionally professional-only.
        // Newly registered settings otherwise appear in both editors.
        return ConfigSettings.values(spec).stream()
                .filter(value -> !value.getPath().equals(List.of("environment", "previewCacheTicks")))
                .sorted(java.util.Comparator.<ModConfigSpec.ConfigValue<?>>comparingInt(NativeConfigOption::order)
                        .thenComparing(value -> String.join(".", value.getPath())))
                .toList();
    }

    private static int order(ModConfigSpec.ConfigValue<?> value) {
        var order = List.of("recipeBookEnhancements", "unlockedOnly", "detailedFailureFeedback", "partialPolicy",
                "allowSurplusDrops", "doublePressMillis", "horizontalRadius", "verticalRadius", "includeEnderChest",
                "partialExecution", "surplusDelivery", "maxCraftBatch", "maxPlanSteps", "directPreparationMode",
                "directPreparationAllowedRecipes", "directPreparationConfirmationRecipes");
        int index = order.indexOf(value.getPath().getLast());
        return index < 0 ? order.size() : index;
    }

    /** List values use the bounded recipe editor, with the same registered identities. */
    static boolean scalar(ModConfigSpec.ConfigValue<?> value) { return !List.class.isAssignableFrom(ConfigSettings.type(value)); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static NativeConfigOption<?> create(ModConfigSpec.ConfigValue<?> value, ConfigSettings.Draft draft) {
        var spec = value.getSpec();
        Class<?> type = ConfigSettings.type(value);
        String key = java.util.Objects.requireNonNull(spec.getTranslationKey(), "Config title is required");
        if (type == Boolean.class) {
            var typed = (ModConfigSpec.ConfigValue<Boolean>) value;
            return bind(typed, OptionInstance.createBoolean(key,
                    OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                    typed.getDefault(), v -> draft.set(value, v)), draft);
        } else if (type == Integer.class && spec.getRange() != null) {
            var typed = (ModConfigSpec.ConfigValue<Integer>) value;
            var range = spec.<Integer>getRange();
            return bind(typed, new OptionInstance<Integer>(key,
                    OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                    (caption, v) -> CommonComponents.optionNameValue(caption, display(value, v)),
                    new OptionInstance.IntRange(range.getMin(), range.getMax()),
                    typed.getDefault(), v -> draft.set(value, v)), draft);
        } else if (type.isEnum()) {
            return createEnum((ModConfigSpec.ConfigValue) value, draft, key);
        } else {
            throw new IllegalArgumentException("No native control for registered config " + value.getPath());
        }
    }

    private static <E extends Enum<E>> NativeConfigOption<E> createEnum(ModConfigSpec.ConfigValue<E> value,
            ConfigSettings.Draft draft, String key) {
        List<E> candidates = Arrays.stream(value.getDefault().getDeclaringClass().getEnumConstants())
                .filter(value.getSpec()::test).toList();
        Codec<E> codec = Codec.STRING.xmap(name -> candidates.stream().filter(v -> v.name().equals(name))
                .findFirst().orElseThrow(), Enum::name);
        return bind(value, new OptionInstance<>(key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                (caption, v) -> display(value, v), new OptionInstance.Enum<>(candidates, codec),
                value.getDefault(), v -> draft.set(value, v)), draft);
    }

    private static <T> NativeConfigOption<T> bind(ModConfigSpec.ConfigValue<T> value,
            OptionInstance<T> option, ConfigSettings.Draft draft) {
        option.set(draft.get(value));
        return new NativeConfigOption<>(value, option);
    }

    static Component display(ModConfigSpec.ConfigValue<?> value, Object current) {
        if (current instanceof Boolean enabled) return CommonComponents.optionStatus(enabled);
        if (current instanceof CraftRequest.PartialPolicy policy)
            return text("policy." + policy.name().toLowerCase(Locale.ROOT));
        if (current instanceof CraftableServerConfig.SurplusDelivery surplus)
            return text("surplus." + surplus.name().toLowerCase(Locale.ROOT));
        if (current instanceof DirectPreparationPolicy.Mode mode)
            return text("direct_mode." + mode.name().toLowerCase(Locale.ROOT));
        if (current instanceof Enum<?> enumeration) return Component.literal(enumeration.name());
        if (value.getPath().getLast().equals("doublePressMillis")) return text("millis", current);
        return Component.literal(String.valueOf(current));
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.craftable.settings." + key, args);
    }
}

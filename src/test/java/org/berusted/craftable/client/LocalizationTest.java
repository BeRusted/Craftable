package org.berusted.craftable.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.berusted.craftable.config.ConfigSettings;
import org.berusted.craftable.config.CraftableClientConfig;
import org.berusted.craftable.config.CraftableServerConfig;
import org.berusted.craftable.config.DirectPreparationPolicy;
import org.berusted.craftable.planner.CraftRequest;
import org.junit.jupiter.api.Test;

/** Copy safeguards use the shipped resources, not a second translation table. */
class LocalizationTest {
    @Test
    void localizedSettingsTitlesAreSeparateFromTheModIdentity() throws Exception {
        var chinese = language("zh_cn");
        var english = language("en_us");
        assertEquals("craftable", org.berusted.craftable.Craftable.MOD_ID);
        assertEquals("配方书", chinese.get("mod.craftable.name").getAsString());
        assertEquals("Craftable", english.get("mod.craftable.name").getAsString());
        assertEquals("配方书", chinese.get("key.categories.craftable").getAsString());
        assertEquals("Craftable", english.get("key.categories.craftable").getAsString());
        for (var key : List.of("screen.craftable.settings.title", "craftable.configuration.title")) {
            assertEquals("配方书设置", chinese.get(key).getAsString(), key);
            assertEquals("Recipe Book Settings", english.get(key).getAsString(), key);
        }
        assertEquals("配方书个人设置", chinese.get("craftable.configuration.section.craftable.client.toml.title").getAsString());
        assertEquals("配方书世界规则", chinese.get("craftable.configuration.section.craftable.server.toml.title").getAsString());
        assertEquals("Recipe Book Personal Settings", english.get("craftable.configuration.section.craftable.client.toml.title").getAsString());
        assertEquals("Recipe Book World Rules", english.get("craftable.configuration.section.craftable.server.toml.title").getAsString());
    }

    @Test
    void languagesHaveMatchingKeysAndArguments() throws Exception {
        var chinese = language("zh_cn");
        var english = language("en_us");
        assertEquals(chinese.keySet(), english.keySet());
        for (var key : chinese.keySet()) {
            assertEquals(arguments(chinese.get(key).getAsString()), arguments(english.get(key).getAsString()), key);
        }
    }

    @Test
    void nativeConfigSectionButtonsHaveExplicitTranslations() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            for (var section : List.of("presentation", "environment", "crafting")) {
                String key = "craftable.configuration." + section + ".button";
                assertTrue(language.has(key), locale + ": " + key);
                assertFalse(language.get(key).getAsString().isBlank(), locale + ": " + key);
            }
        }
    }

    @Test
    void messagesAreNonemptyAndOnlyOptionTooltipsHaveLineBreaks() throws Exception {
        var multilineKeys = enumConfigValues().stream()
                .map(value -> value.getSpec().getTranslationKey() + ".tooltip").collect(Collectors.toSet());
        multilineKeys.add("screen.craftable.settings.direct.choices.tooltip");
        for (var locale : List.of("zh_cn", "en_us")) {
            for (var entry : language(locale).entrySet()) {
                assertTrue(entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString(), entry.getKey());
                var value = entry.getValue().getAsString();
                assertFalse(value.isBlank(), entry.getKey());
                assertFalse(value.contains("\r"), entry.getKey());
                // Native and NeoForge config tooltips both use Tooltip's font.split,
                // which honors LF. Other messages still use separate Components.
                if (value.contains("\n")) assertTrue(multilineKeys.contains(entry.getKey()), entry.getKey());
            }
        }
    }

    @Test
    void everyAcceptedConfigEnumChoiceStartsItsOwnTooltipLine() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            for (var value : enumConfigValues()) {
                String key = value.getSpec().getTranslationKey() + ".tooltip";
                var lines = List.of(language.get(key).getAsString().split("\n", -1));
                assertTrue(lines.size() > 1, locale + ": " + key);
                assertTrue(lines.stream().noneMatch(String::isBlank), locale + ": " + key);
                var type = ConfigSettings.type(value);
                String prefix;
                if (type == CraftRequest.PartialPolicy.class) prefix = "screen.craftable.settings.policy.";
                else if (type == CraftableServerConfig.SurplusDelivery.class) prefix = "screen.craftable.settings.surplus.";
                else if (type == DirectPreparationPolicy.Mode.class) prefix = "screen.craftable.settings.direct_mode.";
                else throw new AssertionError("Add tooltip choice labels for registered enum " + type);
                for (var candidate : type.getEnumConstants()) {
                    if (!value.getSpec().test(candidate)) continue;
                    String name = ((Enum<?>) candidate).name().toLowerCase(Locale.ROOT);
                    String label = language.get(prefix + name).getAsString();
                    String start = label + (locale.equals("zh_cn") ? "：" : ": ");
                    assertEquals(1, lines.stream().filter(line -> line.startsWith(start)).count(),
                            locale + ": " + key + ": " + label);
                }
            }
        }
    }

    @Test
    void preparationOverrideChoicesEachHaveOneExplicitTooltipLine() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            var lines = language.get("screen.craftable.settings.direct.choices.tooltip").getAsString().lines().toList();
            for (var choice : List.of("inherit", "allow", "confirm")) {
                String label = language.get("screen.craftable.settings.direct." + choice).getAsString();
                String start = label + (locale.equals("zh_cn") ? "：" : ": ");
                assertEquals(1, lines.stream().filter(line -> line.startsWith(start)).count(), locale + ": " + choice);
            }
            assertTrue(lines.stream().noneMatch(String::isBlank), locale);
        }
    }

    @Test
    void preferenceHintRefersToTheControlsAboveIt() throws Exception {
        String key = "screen.craftable.settings.preferences_only";
        assertTrue(language("zh_cn").get(key).getAsString().startsWith("以上"));
        assertTrue(language("en_us").get(key).getAsString().contains("above"));
    }

    @Test
    void partialCraftingOffExplanationIsSharedAndPreservesFullCrafting() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            String prefix = language.get("screen.craftable.settings.policy.never").getAsString()
                    + (locale.equals("zh_cn") ? "：" : ": ");
            String client = language.get("config.craftable.client.partial_policy.tooltip").getAsString()
                    .lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow();
            String server = language.get("config.craftable.server.partial_execution.tooltip").getAsString()
                    .lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow();
            assertEquals(client, server, locale);
            assertTrue(client.contains(locale.equals("zh_cn") ? "不做部分准备" : "disables partial preparation"), locale);
            assertTrue(client.contains(locale.equals("zh_cn") ? "仍可完整合成" : "not full crafting"), locale);
        }
    }

    @Test
    void playerMessagesDoNotUseRetiredImplementationTerms() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var terms = locale.equals("zh_cn")
                    ? List.of("交付", "配方输入", "配方产出", "事务", "回滚", "非默认组件", "根配方批", "下游", "需求位置")
                    : List.of("delivery", "recipe inputs", "recipe outputs", "transaction", "non-default components", "root batches", "demand positions");
            for (var entry : language(locale).entrySet()) {
                // /craftable environment is an explicit diagnostic command;
                // its generation/source terminology remains useful to developers.
                if (entry.getKey().startsWith("command.")) continue;
                var value = entry.getValue().getAsString().toLowerCase(Locale.ROOT);
                for (var term : terms) assertFalse(value.contains(term), entry.getKey() + ": " + term);
            }
        }
    }

    private static List<String> arguments(String text) {
        return java.util.regex.Pattern.compile("%(?:\\d+\\$)?s").matcher(text).results().map(m -> m.group()).toList();
    }

    private static List<ModConfigSpec.ConfigValue<?>> enumConfigValues() {
        return List.of(CraftableClientConfig.SPEC, CraftableServerConfig.SPEC).stream()
                .flatMap(spec -> ConfigSettings.values(spec).stream())
                .filter(value -> ConfigSettings.type(value).isEnum()).toList();
    }

    private static JsonObject language(String locale) throws Exception {
        var stream = LocalizationTest.class.getResourceAsStream("/assets/craftable/lang/" + locale + ".json");
        assertNotNull(stream, locale);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}

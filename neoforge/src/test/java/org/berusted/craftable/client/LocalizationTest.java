package org.berusted.craftable.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Copy safeguards use the shipped resources, not a second translation table. */
class LocalizationTest {
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
    void messagesAreNonemptySingleLines() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            for (var entry : language(locale).entrySet()) {
                assertTrue(entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString(), entry.getKey());
                var value = entry.getValue().getAsString();
                assertFalse(value.isBlank(), entry.getKey());
                // Tooltip rows are separate Components; embedded LF must not
                // become visible glyphs when vanilla does not need word wrapping.
                assertFalse(value.contains("\n") || value.contains("\r"), entry.getKey());
            }
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

    private static JsonObject language(String locale) throws Exception {
        var stream = LocalizationTest.class.getResourceAsStream("/assets/craftable/lang/" + locale + ".json");
        assertNotNull(stream, locale);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}

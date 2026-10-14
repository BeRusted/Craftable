package org.berusted.craftable.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforgespi.language.IModInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSettingsTest {
    private enum Mode { STRICT, REVIEW, UNSAFE }

    @Test void enumerationKeepsRegisteredIdentitiesAndMetadata() throws Exception {
        var fixture = new Fixture();
        var values = ConfigSettings.values(fixture.spec);
        assertEquals(List.of(List.of("a", "mode"), List.of("a", "radius"),
                List.of("nested", "inner", "label"), List.of("z", "enabled")),
                values.stream().map(ModConfigSpec.ConfigValue::getPath).toList());
        assertSame(fixture.mode, values.getFirst());
        assertSame(fixture.radius, values.get(1));
        assertSame(fixture.label, values.get(2));
        assertSame(fixture.enabled, values.getLast());
        assertThrows(UnsupportedOperationException.class, () -> values.clear());
        assertEquals(Mode.STRICT, fixture.mode.getDefault());
        assertEquals(Mode.class, fixture.mode.getSpec().getClazz());
        assertEquals(Mode.class, ConfigSettings.type(fixture.mode));
        assertEquals(Integer.class, ConfigSettings.type(fixture.radius));
        assertEquals(Boolean.class, ConfigSettings.type(fixture.enabled));
        assertEquals(String.class, ConfigSettings.type(fixture.label));
        assertEquals("config.test.mode", fixture.mode.getSpec().getTranslationKey());
        assertEquals(1, fixture.radius.getSpec().<Integer>getRange().getMin());
        assertEquals(8, fixture.radius.getSpec().<Integer>getRange().getMax());
        assertFalse(fixture.mode.getSpec().test(Mode.UNSAFE));
    }

    @Test void realRegisteredClientAndServerTypesDefaultsAndRangesDriveControls() {
        for (var spec : List.of(CraftableClientConfig.SPEC, CraftableServerConfig.SPEC)) {
            var values = ConfigSettings.values(spec);
            assertFalse(values.isEmpty());
            for (var value : values) {
                Object fallback = value.getDefault();
                Class<?> expected = fallback instanceof List<?> ? List.class : fallback instanceof Enum<?> enumeration
                        ? enumeration.getDeclaringClass() : fallback.getClass();
                assertEquals(expected, ConfigSettings.type(value), value.getPath().toString());
                assertTrue(ConfigSettings.type(value).isInstance(fallback), value.getPath().toString());
                assertTrue(value.getSpec().test(fallback), value.getPath().toString());
                assertNotNull(value.getSpec().getTranslationKey(), value.getPath().toString());
                var range = value.getSpec().getRange();
                if (value instanceof ModConfigSpec.IntValue) {
                    assertNotNull(range, value.getPath().toString());
                    assertEquals(Integer.class, ConfigSettings.type(value), value.getPath().toString());
                    assertEquals(range.getClazz(), ConfigSettings.type(value), value.getPath().toString());
                    assertTrue(range.test(fallback), value.getPath().toString());
                    assertTrue(value.getSpec().test(range.getMin()), value.getPath().toString());
                    assertTrue(value.getSpec().test(range.getMax()), value.getPath().toString());
                }
            }
        }
    }

    @Test void draftsUseSpecDefaultsRangesEnumCandidatesAndCustomValidator() throws Exception {
        var fixture = new Fixture();
        var draft = new ConfigSettings.Draft(fixture.spec);
        assertTrue(draft.get(fixture.enabled));
        assertEquals(4, draft.get(fixture.radius));
        assertEquals(Mode.STRICT, draft.get(fixture.mode));
        assertEquals("safe", draft.get(fixture.label));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.radius, 0));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.radius, 9));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.radius, 4L));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.enabled, "true"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.enabled, null));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.mode, Mode.UNSAFE));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.mode, "REVIEW"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.label, "invalid"));
        assertFalse(draft.dirty());
        draft.set(fixture.radius, 8);
        draft.set(fixture.mode, Mode.REVIEW);
        draft.validateAll();
        assertEquals(4, fixture.radius.getRaw());
        assertEquals(Mode.STRICT, fixture.mode.getRaw()); // Discarding a draft is Cancel.
        assertEquals(0, fixture.reloadDispatches.get());
    }

    @Test void refreshAndDirtyOnlySaveDoNotOverwriteProfessionalOrReloadChanges() throws Exception {
        var fixture = new Fixture();
        var draft = new ConfigSettings.Draft(fixture.spec);
        draft.set(fixture.enabled, false);
        fixture.radius.set(7);
        assertTrue(draft.refreshUnchanged());
        assertEquals(7, draft.get(fixture.radius));
        assertFalse(draft.dirty(fixture.radius));
        assertTrue(draft.dirty(fixture.enabled));
        assertFalse(draft.refreshUnchanged());
        fixture.mode.set(Mode.REVIEW);
        draft.save();
        assertFalse(fixture.enabled.getRaw());
        assertEquals(7, fixture.radius.getRaw());
        assertEquals(Mode.REVIEW, fixture.mode.getRaw());
        assertEquals(Mode.REVIEW, draft.get(fixture.mode));
        assertFalse(draft.dirty());
        assertEquals(1, fixture.reloadDispatches.get());
        draft.save();
        assertEquals(1, fixture.reloadDispatches.get());
    }

    @Test void selectedSubsetResetAndSaveCannotTouchExcludedValues() throws Exception {
        var fixture = new Fixture();
        fixture.enabled.set(false);
        fixture.radius.set(7);
        fixture.mode.set(Mode.REVIEW);
        var draft = new ConfigSettings.Draft(fixture.spec, List.of(fixture.enabled, fixture.radius));
        draft.resetDefaults();
        assertTrue(draft.get(fixture.enabled));
        assertEquals(4, draft.get(fixture.radius));
        assertFalse(fixture.enabled.getRaw());
        assertEquals(7, fixture.radius.getRaw());
        assertEquals(Mode.REVIEW, fixture.mode.getRaw());
        assertEquals(2, draft.snapshot().size());
        assertThrows(UnsupportedOperationException.class, () -> draft.snapshot().clear());
        assertThrows(IllegalArgumentException.class, () -> draft.get(fixture.mode));
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.mode, Mode.STRICT));
        draft.save();
        assertTrue(fixture.enabled.getRaw());
        assertEquals(4, fixture.radius.getRaw());
        assertEquals(Mode.REVIEW, fixture.mode.getRaw());
        assertEquals(1, fixture.reloadDispatches.get());
    }

    @Test void invalidCandidateCannotPartiallyWriteAnOtherwiseValidDraft() throws Exception {
        var fixture = new Fixture();
        var draft = new ConfigSettings.Draft(fixture.spec);
        draft.set(fixture.enabled, false);
        draft.set(fixture.radius, 8);
        draft.set(fixture.label, "custom");
        fixture.acceptCustom.set(false); // A validator can change after an edit.
        assertThrows(IllegalArgumentException.class, draft::validateAll);
        assertThrows(IllegalArgumentException.class, draft::save);
        assertTrue(fixture.enabled.getRaw());
        assertEquals(4, fixture.radius.getRaw());
        assertEquals("safe", fixture.label.getRaw());
        assertEquals(0, fixture.reloadDispatches.get());
    }

    @Test void foreignIdentitiesDuplicatesAndUnloadedSpecsAreRejected() throws Exception {
        var fixture = new Fixture();
        var other = new Fixture();
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSettings.Draft(fixture.spec, List.of(other.enabled)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSettings.Draft(fixture.spec, List.of(fixture.enabled, fixture.enabled)));
        var draft = new ConfigSettings.Draft(fixture.spec);
        assertThrows(IllegalArgumentException.class, () -> draft.set(other.enabled, false));
        draft.set(fixture.enabled, false);
        fixture.spec.acceptConfig(null);
        assertThrows(IllegalStateException.class, () -> new ConfigSettings.Draft(fixture.spec));
        assertThrows(IllegalStateException.class, draft::refreshUnchanged);
        assertThrows(IllegalStateException.class, draft::save);
        assertTrue(fixture.config.<Boolean>get("z.enabled"));
        assertEquals(0, fixture.reloadDispatches.get());
        assertSame(fixture.enabled, ConfigSettings.values(fixture.spec).getLast());
    }

    @Test void listDraftsFreezeBaselineCandidatesDefaultsAndProfessionalReloads() throws Exception {
        var fixture = new Fixture(true);
        var raw = new java.util.ArrayList<>(List.of("minecraft:stick"));
        fixture.recipes.set(raw);
        var draft = new ConfigSettings.Draft(fixture.spec);
        raw.add("minecraft:torch");
        assertEquals(List.of("minecraft:stick"), draft.get(fixture.recipes));
        assertThrows(UnsupportedOperationException.class, () -> draft.get(fixture.recipes).add("minecraft:chest"));
        assertTrue(draft.refreshUnchanged());
        assertEquals(List.of("minecraft:stick", "minecraft:torch"), draft.get(fixture.recipes));
        var edit = new java.util.ArrayList<>(List.of("minecraft:oak_planks"));
        draft.set(fixture.recipes, edit);
        edit.add("minecraft:chest");
        assertEquals(List.of("minecraft:oak_planks"), draft.get(fixture.recipes));
        assertEquals(List.of("minecraft:stick", "minecraft:torch"), fixture.recipes.getRaw());
        var oversized = java.util.Collections.nCopies(129, "minecraft:stick");
        assertThrows(IllegalArgumentException.class, () -> draft.set(fixture.recipes, oversized));
        draft.save();
        assertEquals(List.of("minecraft:oak_planks"), fixture.recipes.getRaw());
        draft.resetDefaults();
        assertEquals(List.of(), draft.get(fixture.recipes));
        assertEquals(List.of("minecraft:oak_planks"), fixture.recipes.getRaw());
    }

    /** FML's loaded-config interface is sealed. Build its actual in-memory implementation
     * only in this fixture; the production helper needs no reflective/test-only API. */
    private static final class Fixture {
        final AtomicBoolean acceptCustom = new AtomicBoolean(true);
        final AtomicInteger reloadDispatches = new AtomicInteger();
        final ModConfigSpec spec;
        final ModConfigSpec.BooleanValue enabled;
        final ModConfigSpec.IntValue radius;
        final ModConfigSpec.EnumValue<Mode> mode;
        final ModConfigSpec.ConfigValue<String> label;
        final ModConfigSpec.ConfigValue<List<String>> recipes;
        final CommentedConfig config = CommentedConfig.inMemory();

        Fixture() throws Exception {
            this(false);
        }
        Fixture(boolean withRecipes) throws Exception {
            var builder = new ModConfigSpec.Builder();
            enabled = builder.define("z.enabled", true);
            radius = builder.defineInRange("a.radius", 4, 1, 8);
            mode = builder.translation("config.test.mode")
                    .defineEnum("a.mode", Mode.STRICT, List.of(Mode.STRICT, Mode.REVIEW));
            label = builder.define("nested.inner.label", "safe",
                    candidate -> "safe".equals(candidate) || acceptCustom.get() && "custom".equals(candidate));
            recipes = withRecipes ? builder.define(List.of("a", "recipes"), () -> List.<String>of(),
                    DirectPreparationPolicy::validRecipeList, List.class) : null;
            spec = builder.build();
            spec.correct(config);
            var info = (IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(), new Class<?>[] { IModInfo.class },
                    (proxy, method, args) -> method.getName().equals("getModId") ? "config_settings_test" : null);
            var container = new ModContainer(info) {
                @Override public IEventBus getEventBus() {
                    reloadDispatches.incrementAndGet();
                    return null;
                }
            };
            var modConstructor = ModConfig.class.getDeclaredConstructor(ModConfig.Type.class, IConfigSpec.class,
                    ModContainer.class, String.class, ReentrantLock.class);
            modConstructor.setAccessible(true);
            var modConfig = modConstructor.newInstance(ModConfig.Type.CLIENT, spec, container,
                    "config-settings-test.toml", new ReentrantLock());
            var loadedConstructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                    .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
            loadedConstructor.setAccessible(true);
            spec.acceptConfig((IConfigSpec.ILoadedConfig) loadedConstructor.newInstance(config, null, modConfig));
        }
    }
}

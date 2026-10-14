package org.berusted.craftable.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/** Registered config identities and unsaved UI values, not another settings definition.
 * World ownership and connection permissions must still be checked by the caller. */
public final class ConfigSettings {
    private ConfigSettings() {}

    /** Enumerates the registered values without reading or loading a config. */
    public static List<ConfigValue<?>> values(ModConfigSpec spec) {
        var result = new ArrayList<ConfigValue<?>>();
        collect(Objects.requireNonNull(spec, "spec").getValues(), result);
        result.sort((left, right) -> {
            var a = left.getPath();
            var b = right.getPath();
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                int comparison = a.get(i).compareTo(b.get(i));
                if (comparison != 0) return comparison;
            }
            return Integer.compare(a.size(), b.size());
        });
        return List.copyOf(result);
    }

    private static void collect(UnmodifiableConfig config, List<ConfigValue<?>> result) {
        for (Object value : config.valueMap().values()) {
            if (value instanceof ConfigValue<?> registered) result.add(registered);
            else if (value instanceof UnmodifiableConfig section) collect(section, result);
        }
    }

    /** NeoForge can expose Object.class for a bounded numeric value. Resolve
     * its registered range before falling back to the default's runtime type. */
    public static Class<?> type(ConfigValue<?> value) {
        var metadata = Objects.requireNonNull(value, "value").getSpec();
        Class<?> declared = metadata.getClazz();
        if (declared != null && declared != Object.class) return declared;
        var range = metadata.getRange();
        if (range != null) return range.getClazz();
        Object fallback = Objects.requireNonNull(value.getDefault(), "Config default");
        return fallback instanceof Enum<?> enumeration ? enumeration.getDeclaringClass() : fallback.getClass();
    }

    public static final class Draft {
        private final ModConfigSpec spec;
        private final List<ConfigValue<?>> selected;
        private final Map<ConfigValue<?>, Object> baseline = new IdentityHashMap<>();
        private final Map<ConfigValue<?>, Object> proposed = new IdentityHashMap<>();

        public Draft(ModConfigSpec spec) {
            this(spec, values(spec));
        }

        /** Only selected registered identities may be read, reset or saved. */
        public Draft(ModConfigSpec spec, List<ConfigValue<?>> selected) {
            this.spec = Objects.requireNonNull(spec, "spec");
            requireLoaded();
            var registered = new IdentityHashMap<ConfigValue<?>, Boolean>();
            values(spec).forEach(value -> registered.put(value, Boolean.TRUE));
            this.selected = List.copyOf(selected);
            for (var value : this.selected) {
                if (!registered.containsKey(value) || baseline.containsKey(value))
                    throw new IllegalArgumentException("Selected value is foreign or duplicated: " + value.getPath());
                Object current = frozen(value.getRaw());
                validate(value, current);
                baseline.put(value, current);
                proposed.put(value, current);
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T get(ConfigValue<T> value) {
            requireSelected(value);
            return (T) proposed.get(value);
        }

        /** Validate before changing even the draft; ConfigValue.set itself does not validate. */
        public void set(ConfigValue<?> value, Object candidate) {
            requireSelected(value);
            validate(value, candidate);
            proposed.put(value, frozen(candidate));
        }

        public boolean dirty(ConfigValue<?> value) {
            requireSelected(value);
            return !Objects.equals(baseline.get(value), proposed.get(value));
        }

        public boolean dirty() {
            return selected.stream().anyMatch(this::dirty);
        }

        /** Reset only this draft's selected values; no registered config is written. */
        public void resetDefaults() {
            var defaults = new IdentityHashMap<ConfigValue<?>, Object>();
            for (var value : selected) {
                Object candidate = frozen(value.getDefault());
                validate(value, candidate);
                defaults.put(value, candidate);
            }
            proposed.putAll(defaults);
        }

        /** Absorb professional-editor/reload changes only where the user has no local edit. */
        public boolean refreshUnchanged() {
            requireLoaded();
            var refreshed = new IdentityHashMap<ConfigValue<?>, Object>();
            for (var value : selected) if (!dirty(value)) {
                Object current = frozen(value.getRaw());
                validate(value, current);
                refreshed.put(value, current);
            }
            boolean changed = refreshed.entrySet().stream()
                    .anyMatch(entry -> !Objects.equals(proposed.get(entry.getKey()), entry.getValue()));
            baseline.putAll(refreshed);
            proposed.putAll(refreshed);
            return changed;
        }

        public void validateAll() {
            requireLoaded();
            for (var value : selected) validate(value, proposed.get(value));
        }

        /** All candidates are checked before the first mutation. Persistence and reload
         * events use the registered spec's normal lifecycle, never a parallel file. */
        public void save() {
            requireLoaded();
            refreshUnchanged();
            validateAll();
            var changed = selected.stream().filter(this::dirty).toList();
            if (changed.isEmpty()) return;
            for (var value : changed) write(value, proposed.get(value));
            spec.save();
            for (var value : changed) baseline.put(value, proposed.get(value));
        }

        /** An immutable map snapshot of only the selected values, in selection order. */
        public Map<ConfigValue<?>, Object> snapshot() {
            var copy = new LinkedHashMap<ConfigValue<?>, Object>();
            for (var value : selected) copy.put(value, proposed.get(value));
            return Collections.unmodifiableMap(copy);
        }

        private void requireLoaded() {
            if (!spec.isLoaded()) throw new IllegalStateException("Cannot edit an unloaded config");
        }

        private void requireSelected(ConfigValue<?> value) {
            if (!proposed.containsKey(value)) throw new IllegalArgumentException("Value is not selected by this draft");
        }

        private static void validate(ConfigValue<?> value, Object candidate) {
            var metadata = value.getSpec();
            if (candidate == null || !type(value).isInstance(candidate) || !metadata.test(candidate))
                throw new IllegalArgumentException("Invalid config value: " + value.getPath());
        }

        /** Lists are now registered consent values. Do not let a mutable
         * config/list-editor alias change the baseline or an unsaved draft. */
        private static Object frozen(Object value) {
            return value instanceof List<?> list ? List.copyOf(list) : value;
        }

        @SuppressWarnings("unchecked")
        private static <T> void write(ConfigValue<T> value, Object candidate) {
            value.set((T) candidate);
        }
    }
}

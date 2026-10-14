package org.berusted.craftable.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;

/** Server-owned consent policy, separate from static recipe knowledge and
 * execution support. It only decides whether an explicit partial preparation
 * may omit an additional confirmation; it never authorizes a recipe or input. */
public final class DirectPreparationPolicy {
    public static final int MAX_RECIPES = 128;
    public static final int MAX_RECIPE_ID_LENGTH = 128;
    public enum Mode { CUSTOM, ALL_SUPPORTED, REQUIRE_CONFIRMATION }
    public static final DirectPreparationPolicy DEFAULT = of(Mode.CUSTOM, List.of(), List.of());

    private final Mode mode;
    private final List<String> allowedRecipes, confirmationRecipes;
    private final Set<ResourceLocation> allowed, confirmation;

    private DirectPreparationPolicy(Mode mode, List<String> allowedRecipes, List<String> confirmationRecipes) {
        this.mode = mode;
        this.allowedRecipes = allowedRecipes;
        this.confirmationRecipes = confirmationRecipes;
        this.allowed = locations(allowedRecipes);
        this.confirmation = locations(confirmationRecipes);
    }

    public static DirectPreparationPolicy of(Mode mode, List<String> allowedRecipes, List<String> confirmationRecipes) {
        return new DirectPreparationPolicy(Objects.requireNonNull(mode, "mode"),
                normalize(allowedRecipes), normalize(confirmationRecipes));
    }

    public Mode mode() { return mode; }
    public List<String> allowedRecipes() { return allowedRecipes; }
    public List<String> confirmationRecipes() { return confirmationRecipes; }

    /** Call only after support and authoritative validation have succeeded.
     * Unknown but well-formed configured IDs remain dormant, not new recipes. */
    public boolean allowsWithoutConfirmation(ResourceLocation recipe, boolean defaultEligible) {
        Objects.requireNonNull(recipe, "recipe");
        return switch (mode) {
            case ALL_SUPPORTED -> true;
            case REQUIRE_CONFIRMATION -> false;
            case CUSTOM -> !confirmation.contains(recipe) && (allowed.contains(recipe) || defaultEligible);
        };
    }

    /** Shared by the registered setting metadata and both UI presentations. */
    public static boolean validRecipeList(Object value) {
        if (!(value instanceof List<?> recipes) || recipes.size() > MAX_RECIPES) return false;
        for (var recipe : recipes) if (!validRecipeId(recipe)) return false;
        return true;
    }

    public static boolean validRecipeId(Object value) {
        if (!(value instanceof String text) || text.length() > MAX_RECIPE_ID_LENGTH) return false;
        String id = text.trim();
        int separator = id.indexOf(':');
        // Do not silently interpret an omitted namespace as "minecraft".
        return separator > 0 && separator < id.length() - 1 && ResourceLocation.tryParse(id) != null;
    }

    private static List<String> normalize(List<String> recipes) {
        if (!validRecipeList(recipes)) throw new IllegalArgumentException("Expected at most 128 full recipe IDs of at most 128 characters");
        var sorted = new TreeSet<String>();
        for (String recipe : recipes) sorted.add(ResourceLocation.parse(recipe.trim()).toString());
        return List.copyOf(sorted);
    }

    private static Set<ResourceLocation> locations(List<String> recipes) {
        var locations = new java.util.HashSet<ResourceLocation>();
        for (String recipe : recipes) locations.add(ResourceLocation.parse(recipe));
        return Set.copyOf(locations);
    }

    @Override public boolean equals(Object other) {
        return other instanceof DirectPreparationPolicy policy && mode == policy.mode
                && allowedRecipes.equals(policy.allowedRecipes) && confirmationRecipes.equals(policy.confirmationRecipes);
    }

    @Override public int hashCode() { return Objects.hash(mode, allowedRecipes, confirmationRecipes); }
    @Override public String toString() { return "DirectPreparationPolicy[" + mode + ", allowed=" + allowedRecipes
            + ", confirmation=" + confirmationRecipes + "]"; }
}

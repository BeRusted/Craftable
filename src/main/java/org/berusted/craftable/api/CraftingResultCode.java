package org.berusted.craftable.api;

/** Stable result codes shared by server execution and client feedback. */
public enum CraftingResultCode {
    CREATED,
    RECIPE_NOT_FOUND,
    UNSUPPORTED_RECIPE,
    MISSING_WORKSTATION,
    MISSING_INGREDIENTS,
    NO_OUTPUT_SPACE,
    ENVIRONMENT_CHANGED,
    INVALID_CONTEXT,
    REQUEST_THROTTLED,
    INTERNAL_ERROR
}

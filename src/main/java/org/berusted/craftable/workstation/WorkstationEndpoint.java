package org.berusted.craftable.workstation;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record WorkstationEndpoint(String id, WorkstationCapability capability, @Nullable BlockPos position) {
    public WorkstationEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capability, "capability");
        position = position == null ? null : position.immutable();
    }
}

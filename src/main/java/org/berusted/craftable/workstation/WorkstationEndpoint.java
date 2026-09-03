package org.berusted.craftable.workstation;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/** A stable source of one workstation capability in a single snapshot. */
public record WorkstationEndpoint(
        String id,
        WorkstationCapability capability,
        @Nullable BlockPos position) {
    public WorkstationEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capability, "capability");
        position = position == null ? null : position.immutable();
    }
}

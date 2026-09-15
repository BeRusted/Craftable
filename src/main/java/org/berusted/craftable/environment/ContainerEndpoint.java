package org.berusted.craftable.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record ContainerEndpoint(String id,
                                EndpointKind kind,
                                @Nullable BlockPos position,
                                Container container,
                                int firstSlot,
                                int slotCount) {
    public ContainerEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(container, "container");
        position = position == null ? null : position.immutable();
        if (firstSlot < 0 || slotCount < 0 || firstSlot + slotCount > container.getContainerSize()) {
            throw new IllegalArgumentException(
                    "Endpoint slot range is outside its container"
            );
        }
    }

    public boolean containsSlot(int slot) {
        return slot >= firstSlot && slot < firstSlot + slotCount;
    }

    public boolean isStillValid(ServerPlayer player) {
        return kind != EndpointKind.BLOCK || container.stillValid(player);
    }

}

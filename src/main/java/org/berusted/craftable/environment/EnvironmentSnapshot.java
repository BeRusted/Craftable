package org.berusted.craftable.environment;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** A server-authoritative, single-scan view of the resources around one player. */
public record EnvironmentSnapshot(
        BlockPos origin,
        long generation,
        boolean craftingTableAvailable,
        List<ContainerEndpoint> endpoints) {

    public EnvironmentSnapshot {
        origin = origin.immutable();
        endpoints = List.copyOf(endpoints);
    }

    public Optional<ContainerEndpoint> endpoint(String id) {
        return endpoints.stream().filter(endpoint -> endpoint.id().equals(id)).findFirst();
    }
}

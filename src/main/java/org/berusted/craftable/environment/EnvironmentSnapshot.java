package org.berusted.craftable.environment;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.berusted.craftable.config.EnvironmentScanSettings;
import org.berusted.craftable.workstation.WorkstationCapability;
import org.berusted.craftable.workstation.WorkstationEndpoint;

/**
 * A server-authoritative, single-scan view of one player's work environment.
 * Container endpoints are live handles, so snapshots are main-thread-only and
 * must never be persisted or sent to a client.
 */
public record EnvironmentSnapshot(
        ResourceKey<Level> dimension,
        BlockPos origin,
        EnvironmentScanSettings scanSettings,
        long capturedGameTime,
        long generation,
        List<ContainerEndpoint> endpoints,
        List<WorkstationEndpoint> workstations) {

    public EnvironmentSnapshot {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(scanSettings, "scanSettings");
        origin = origin.immutable();
        endpoints = List.copyOf(endpoints);
        workstations = List.copyOf(workstations);
        requireUniqueIds(endpoints.stream().map(ContainerEndpoint::id).toList(), "resource endpoint");
        requireUniqueIds(workstations.stream().map(WorkstationEndpoint::id).toList(), "workstation endpoint");
    }

    public Optional<ContainerEndpoint> endpoint(String id) {
        return endpoints.stream().filter(endpoint -> endpoint.id().equals(id)).findFirst();
    }

    public boolean supports(WorkstationCapability capability) {
        return workstations.stream().anyMatch(endpoint -> endpoint.capability() == capability);
    }

    private static void requireUniqueIds(List<String> ids, String label) {
        HashSet<String> unique = new HashSet<>();
        for (String id : ids) {
            if (!unique.add(id)) {
                throw new IllegalArgumentException("Duplicate " + label + " id: " + id);
            }
        }
    }
}

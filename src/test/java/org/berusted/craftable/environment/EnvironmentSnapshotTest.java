package org.berusted.craftable.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.berusted.craftable.testsupport.SizedContainerStub;
import org.junit.jupiter.api.Test;

class EnvironmentSnapshotTest {
    @Test
    void defensivelyCopiesItsEndpointList() {
        List<ContainerEndpoint> endpoints = new ArrayList<>();
        endpoints.add(new ContainerEndpoint(
                "one", EndpointKind.BLOCK, BlockPos.ZERO, new SizedContainerStub(1), 0, 1));

        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(BlockPos.ZERO, 42, true, endpoints);
        endpoints.clear();

        assertEquals(1, snapshot.endpoints().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.endpoints().clear());
    }
}

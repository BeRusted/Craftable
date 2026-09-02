package org.berusted.craftable.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.berusted.craftable.testsupport.SizedContainerStub;
import org.junit.jupiter.api.Test;

class ContainerEndpointTest {
    @Test
    void acceptsAnExplicitSubrange() {
        ContainerEndpoint endpoint = new ContainerEndpoint(
                "player:test:inventory", EndpointKind.PLAYER, null, new SizedContainerStub(41), 0, 36);

        assertEquals(36, endpoint.slotCount());
    }

    @Test
    void rejectsRangesOutsideTheContainer() {
        SizedContainerStub container = new SizedContainerStub(9);

        assertThrows(IllegalArgumentException.class, () -> new ContainerEndpoint(
                "bad", EndpointKind.BLOCK, null, container, 3, 7));
    }
}

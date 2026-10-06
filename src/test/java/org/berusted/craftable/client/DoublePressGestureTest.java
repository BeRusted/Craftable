package org.berusted.craftable.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoublePressGestureTest {
    @Test void repeatNeedsReleaseAndPairDoesNotExtendIntoTriplePress() {
        var gesture = new DoublePressGesture();
        var menu = new Object();
        assertEquals(-1, gesture.press(menu, "pick", 1, 0, 350).orElseThrow());
        assertTrue(gesture.press(menu, "pick", 2, 100, 350).isEmpty());
        gesture.release();
        assertEquals(1, gesture.press(menu, "pick", 3, 300, 350).orElseThrow());
        gesture.release();
        assertEquals(-1, gesture.press(menu, "pick", 4, 310, 350).orElseThrow());
    }

    @Test void menuTargetTimeoutAndCloseBreakPairing() {
        var gesture = new DoublePressGesture();
        var menu = new Object();
        gesture.press(menu, "pick", 1, 0, 350); gesture.release();
        assertEquals(-1, gesture.press(menu, "sword", 2, 100, 350).orElseThrow()); gesture.release();
        assertEquals(-1, gesture.press(new Object(), "sword", 3, 200, 350).orElseThrow()); gesture.release();
        assertEquals(-1, gesture.press(menu, "sword", 4, 900, 350).orElseThrow());
        gesture.clear();
        assertEquals(-1, gesture.press(menu, "sword", 5, 910, 350).orElseThrow());
    }
}

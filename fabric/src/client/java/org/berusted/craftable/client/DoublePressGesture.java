package org.berusted.craftable.client;

import java.util.OptionalLong;

/** Key edges and pairing only; server outcomes decide whether preparation is permitted. */
public final class DoublePressGesture {
    private boolean down;
    private Object menu;
    private Object target;
    private long previous = -1;
    private long pressedAt;

    public OptionalLong press(Object menu, Object target, long sequence, long now, long windowNanos) {
        if (down) return OptionalLong.empty(); // OS repeat is not a second press.
        down = true;
        boolean paired = this.menu == menu && java.util.Objects.equals(this.target, target)
                && previous >= 0 && now >= pressedAt && now - pressedAt <= windowNanos;
        long linked = paired ? previous : -1;
        this.menu = menu;
        this.target = target;
        this.previous = paired ? -1 : sequence;
        this.pressedAt = now;
        return OptionalLong.of(linked);
    }

    public void release() { down = false; }
    public void clear() { down = false; menu = null; target = null; previous = -1; }
}

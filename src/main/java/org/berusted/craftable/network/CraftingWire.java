package org.berusted.craftable.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.berusted.craftable.planner.CraftPlan;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.PlanView;

/** Explicit bounds shared by detail and action messages; no generic object graph codec. */
public final class CraftingWire {
    static final int MAX_STACKS = 1280;
    static final int MAX_MISSING = 576;
    static final int MAX_DETAIL_BYTES = 65536;
    private CraftingWire() {}

    public static final int SNAPSHOT_BYTES = 4 * 1024 * 1024;
    public static final int SNAPSHOT_CHUNK_BYTES = 31 * 1024;
    public static final int SNAPSHOT_CHUNKS = (SNAPSHOT_BYTES + SNAPSHOT_CHUNK_BYTES - 1) / SNAPSHOT_CHUNK_BYTES;

    /** Transport identity, not an inventory version or an execution lease.
     * Header validation happens before allocating the receive buffer. */
    public record SnapshotHeader(java.util.UUID session, java.util.UUID transfer,
            long recipes, long resources, int bytes, int chunks, byte[] digest) {
        public SnapshotHeader {
            java.util.Objects.requireNonNull(session);
            java.util.Objects.requireNonNull(transfer);
            if (recipes < 0 || resources < 0 || bytes < 0 || bytes > SNAPSHOT_BYTES
                    || chunks != Math.max(1, (bytes + SNAPSHOT_CHUNK_BYTES - 1) / SNAPSHOT_CHUNK_BYTES)
                    || digest.length != 32) throw new IllegalArgumentException("Invalid snapshot header");
            digest = digest.clone();
        }
        @Override public byte[] digest() { return digest.clone(); }
    }

    /** One bounded receive operation, measured only with monotonic nanoseconds.
     * TCP/NeoForge preserves message order; duplicate or out-of-order chunks
     * do not allocate, change the digest, or renew the no-progress deadline. */
    public static final class SnapshotReceiver {
        private static final long TICK_NANOS = 50_000_000L;
        private static final long STALL_NANOS = 100 * TICK_NANOS;
        private final SnapshotHeader header;
        private final long started, duration;
        private final int retries;
        private final java.security.MessageDigest digest;
        private byte[] buffer;
        private int next;
        private long progressed;
        private boolean complete, closed, retryable, retryIssued;

        public SnapshotReceiver(SnapshotHeader header, long now) {
            this(header, now, now, 0);
        }

        private SnapshotReceiver(SnapshotHeader header, long started, long now, int retries) {
            this.header = java.util.Objects.requireNonNull(header);
            this.started = started;
            this.duration = (100L + 4L * header.chunks()) * TICK_NANOS;
            this.progressed = now;
            this.retries = retries;
            this.digest = sha256();
            this.buffer = new byte[header.bytes()];
        }

        public boolean accept(java.util.UUID session, java.util.UUID transfer, int index, byte[] data, long now) {
            if (expired(now) || complete || !header.session().equals(session) || !header.transfer().equals(transfer)
                    || index != next || data.length != Math.min(SNAPSHOT_CHUNK_BYTES,
                            header.bytes() - next * SNAPSHOT_CHUNK_BYTES)) return false;
            // Chunk-sized hashing avoids a whole 4 MiB hash on the last frame.
            System.arraycopy(data, 0, buffer, next * SNAPSHOT_CHUNK_BYTES, data.length);
            digest.update(data);
            next++;
            progressed = now;
            if (next == header.chunks()) {
                if (!java.security.MessageDigest.isEqual(digest.digest(), header.digest())) {
                    close();
                    return false;
                }
                complete = true;
            }
            return true;
        }

        public boolean expired(long now) {
            if (closed) return true;
            // Subtraction remains correct across nanoTime's signed wraparound;
            // a backwards/non-monotonic test clock fails closed as well.
            long age = now - started, idle = now - progressed;
            if (age < 0 || idle < 0 || age >= duration || idle >= STALL_NANOS) {
                close();
                retryable = age >= 0 && age < duration && idle >= STALL_NANOS;
            }
            return closed;
        }

        /** Exactly one re-sync may reuse this deadline, never grant a new one.
         * It must refer to the same content; a replacement resource version is
         * a separately invalidated operation, not a retry of these bytes. */
        public SnapshotReceiver retry(SnapshotHeader replacement, long now) {
            if (retries != 0 || retryIssued || complete || closed && !retryable
                    || now - started < 0 || now - started >= duration
                    || !header.session().equals(replacement.session())
                    || header.recipes() != replacement.recipes() || header.resources() != replacement.resources()
                    || header.bytes() != replacement.bytes()
                    || !java.security.MessageDigest.isEqual(header.digest(), replacement.digest()))
                throw new IllegalStateException("Snapshot retry is not allowed");
            retryIssued = true;
            close();
            return new SnapshotReceiver(replacement, started, now, 1);
        }

        public boolean complete() { return complete && !closed; }
        public int receivedChunks() { return next; }
        public int bufferedBytes() { return buffer == null ? 0 : buffer.length; }

        /** Transfers sole ownership only after integrity checks. The caller
         * still has to decode, handshake and check the independent lease. */
        public byte[] take(long now) {
            if (expired(now) || !complete) throw new IllegalStateException("Snapshot is incomplete");
            var bytes = buffer;
            buffer = null;
            closed = true;
            return bytes;
        }

        public void close() { buffer = null; closed = true; retryable = false; }
    }

    static java.security.MessageDigest sha256() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Bounded value encoding. No endpoints or permission callbacks are read
     * here; only the owning server session may choose authorized input values. */
    public static byte[] snapshot(org.berusted.craftable.environment.BrowsingSnapshot value,
            net.minecraft.core.RegistryAccess registries) {
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(1024, SNAPSHOT_BYTES), registries);
        try {
            buffer.writeUUID(value.session());
            buffer.writeVarLong(value.recipes());
            buffer.writeVarLong(value.resources());
            buffer.writeBoolean(value.workbench());
            buffer.writeBoolean(value.limitedCrafting());
            buffer.writeVarInt(value.unlocked().size());
            for (var recipe : value.unlocked().stream().sorted(java.util.Comparator.comparing(Object::toString)).toList())
                buffer.writeUtf(recipe.toString(), 512);
            buffer.writeBoolean(value.allowDrops());
            buffer.writeEnum(value.partialPolicy());
            buffer.writeVarInt(value.maxBatches());
            buffer.writeVarInt(value.inventoryMaximum());
            for (var stack : value.inventory()) ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
            buffer.writeVarInt(value.inputs().size());
            for (var source : value.inputs()) {
                buffer.writeUUID(source.reference());
                buffer.writeByte(source.inventorySlot());
                ItemStack.STREAM_CODEC.encode(buffer, source.stack());
            }
            var result = new byte[buffer.readableBytes()];
            buffer.readBytes(result);
            return result;
        } finally { buffer.release(); }
    }

    public static org.berusted.craftable.environment.BrowsingSnapshot snapshot(byte[] data,
            net.minecraft.core.RegistryAccess registries) {
        try (var decoder = new SnapshotDecoder(data, registries)) {
            while (!decoder.advance(Long.MAX_VALUE / 2)) { }
            return decoder.result();
        }
    }

    /** The synchronous codec and client tick decoder use the same cursor.
     * One item codec call is atomic; a completed transfer never decodes all
     * 4096 sources from inside the last-chunk network callback. */
    public static final class SnapshotDecoder implements AutoCloseable {
        private final RegistryFriendlyByteBuf buffer;
        private final java.util.Set<net.minecraft.resources.ResourceLocation> unlocked = new java.util.HashSet<>();
        private final List<ItemStack> inventory = new ArrayList<>(36);
        private final List<org.berusted.craftable.environment.BrowsingSnapshot.Input> inputs = new ArrayList<>();
        private java.util.UUID session;
        private long recipes, resources;
        private boolean workbench, limited, drops, closed;
        private CraftRequest.PartialPolicy policy;
        private int phase, unlockCount, sourceCount, batches, maximum;
        private org.berusted.craftable.environment.BrowsingSnapshot result;

        public SnapshotDecoder(byte[] data, net.minecraft.core.RegistryAccess registries) {
            if (data.length > SNAPSHOT_BYTES) throw new IllegalArgumentException("Oversized browsing snapshot");
            buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data), registries);
        }

        public boolean advance(long nanos) {
            if (result != null) return true;
            if (closed || nanos <= 0) throw new IllegalStateException("Invalid decoder slice");
            long start = System.nanoTime();
            try {
                do {
                    switch (phase) {
                        case 0 -> {
                            session = buffer.readUUID(); recipes = buffer.readVarLong(); resources = buffer.readVarLong();
                            if (recipes < 0 || resources < 0) throw new IllegalArgumentException("Negative snapshot version");
                            workbench = buffer.readBoolean(); limited = buffer.readBoolean();
                            unlockCount = count(buffer, org.berusted.craftable.environment.BrowsingSnapshot.MAX_UNLOCKED);
                            phase = 1;
                        }
                        case 1 -> {
                            if (unlocked.size() < unlockCount) {
                                if (!unlocked.add(net.minecraft.resources.ResourceLocation.parse(buffer.readUtf(512))))
                                    throw new IllegalArgumentException("Duplicate recipe permission");
                            } else phase = 2;
                        }
                        case 2 -> {
                            drops = buffer.readBoolean(); policy = buffer.readEnum(CraftRequest.PartialPolicy.class);
                            batches = count(buffer, CraftRequest.MAX_BATCHES); maximum = count(buffer, 99); phase = 3;
                        }
                        case 3 -> {
                            if (inventory.size() < 36) inventory.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
                            else { sourceCount = count(buffer, org.berusted.craftable.environment.BrowsingSnapshot.MAX_INPUTS); phase = 4; }
                        }
                        case 4 -> {
                            if (inputs.size() < sourceCount) inputs.add(new org.berusted.craftable.environment.BrowsingSnapshot.Input(
                                    buffer.readUUID(), buffer.readByte(), ItemStack.STREAM_CODEC.decode(buffer)));
                            else {
                                if (buffer.isReadable()) throw new IllegalArgumentException("Trailing snapshot bytes");
                                result = new org.berusted.craftable.environment.BrowsingSnapshot(session, recipes, resources,
                                        workbench, limited, unlocked, drops, policy, batches, maximum, inventory, inputs);
                                close(); return true;
                            }
                        }
                        default -> throw new IllegalStateException("Invalid decoder cursor");
                    }
                } while (System.nanoTime() - start < nanos);
                return false;
            } catch (RuntimeException failure) { close(); throw failure; }
        }
        public org.berusted.craftable.environment.BrowsingSnapshot result() {
            if (result == null) throw new IllegalStateException("Incomplete snapshot"); return result;
        }
        @Override public void close() { if (!closed) { closed = true; buffer.release(); } }
    }

    static int count(RegistryFriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Oversized crafting payload");
        return count;
    }

    static void stacks(RegistryFriendlyByteBuf buffer, List<ItemStack> stacks) {
        if (stacks.size() > MAX_STACKS) throw new IllegalArgumentException("Too many stacks");
        buffer.writeVarInt(stacks.size());
        for (var stack : stacks) ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
    }

    static List<ItemStack> stacks(RegistryFriendlyByteBuf buffer) {
        int count = count(buffer, MAX_STACKS);
        var stacks = new ArrayList<ItemStack>(count);
        for (int i = 0; i < count; i++) stacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
        return stacks;
    }

    static void missing(RegistryFriendlyByteBuf buffer, List<CraftPlan.Missing> missing) {
        if (missing.size() > MAX_MISSING) throw new IllegalArgumentException("Too many deficits");
        buffer.writeVarInt(missing.size());
        for (var item : missing) {
            buffer.writeUtf(item.path(), 32);
            buffer.writeVarInt(item.count());
            stacks(buffer, item.alternatives());
        }
    }

    static List<CraftPlan.Missing> missing(RegistryFriendlyByteBuf buffer) {
        int count = count(buffer, MAX_MISSING);
        var result = new ArrayList<CraftPlan.Missing>(count);
        for (int i = 0; i < count; i++) {
            String path = buffer.readUtf(32);
            int amount = count(buffer, 65536);
            var options = stacks(buffer);
            if (options.size() > 16 || amount < 1) throw new IllegalArgumentException("Invalid deficit");
            result.add(new CraftPlan.Missing(path, options, amount));
        }
        return result;
    }

    static void request(RegistryFriendlyByteBuf buffer, CraftRequest request) {
        buffer.writeResourceLocation(request.recipe());
        buffer.writeVarInt(request.batches());
        buffer.writeBoolean(request.partial());
        buffer.writeBoolean(request.allowDrops());
        buffer.writeEnum(request.policy());
        buffer.writeVarInt(request.selections().size());
        request.selections().forEach((path, recipe) -> { buffer.writeUtf(path, 32); buffer.writeResourceLocation(recipe); });
    }

    static CraftRequest request(RegistryFriendlyByteBuf buffer) {
        var recipe = buffer.readResourceLocation();
        int batches = count(buffer, CraftRequest.MAX_BATCHES);
        boolean partial = buffer.readBoolean(), drops = buffer.readBoolean();
        var policy = buffer.readEnum(CraftRequest.PartialPolicy.class);
        int count = count(buffer, CraftRequest.MAX_SELECTIONS);
        var selections = new java.util.TreeMap<String, net.minecraft.resources.ResourceLocation>();
        for (int i = 0; i < count; i++) {
            if (selections.put(buffer.readUtf(32), buffer.readResourceLocation()) != null)
                throw new IllegalArgumentException("Duplicate selection");
        }
        return new CraftRequest(recipe, batches, partial, drops, policy, selections);
    }

    static void view(RegistryFriendlyByteBuf buffer, PlanView view) {
        buffer.writeEnum(view.code());
        buffer.writeBoolean(view.workbench());
        buffer.writeBoolean(view.complete());
        buffer.writeVarInt(view.completedBatches());
        buffer.writeVarInt(view.nodes().size());
        for (var node : view.nodes()) {
            buffer.writeUtf(node.path(), 32);
            stacks(buffer, node.needs());
            stacks(buffer, node.made());
            buffer.writeVarInt(node.recipes().size());
            node.recipes().forEach(buffer::writeResourceLocation);
            buffer.writeBoolean(node.explanation());
            buffer.writeBoolean(node.alternatives());
            buffer.writeUtf(node.reference(), 32);
        }
        buffer.writeVarInt(view.operations().size());
        for (var step : view.operations()) {
            buffer.writeResourceLocation(step.recipe());
            buffer.writeUtf(step.path(), 32);
            stacks(buffer, step.inputs());
            ItemStack.STREAM_CODEC.encode(buffer, step.output());
            stacks(buffer, step.remainders());
            step.inputOrigins().forEach(buffer::writeVarInt);
        }
        stacks(buffer, view.consumed());
        stacks(buffer, view.primary());
        stacks(buffer, view.surplus());
        stacks(buffer, view.drops());
        missing(buffer, view.missing());
    }

    static PlanView view(RegistryFriendlyByteBuf buffer) {
        var code = buffer.readEnum(org.berusted.craftable.api.CraftingResultCode.class);
        boolean workbench = buffer.readBoolean(), complete = buffer.readBoolean();
        int completed = count(buffer, CraftRequest.MAX_BATCHES);
        int size = count(buffer, PlanView.MAX_NODES);
        var nodes = new ArrayList<PlanView.Node>(size);
        for (int i = 0; i < size; i++) {
            String path = buffer.readUtf(32);
            var needs = stacks(buffer);
            var made = stacks(buffer);
            int recipes = count(buffer, 16);
            var ids = new ArrayList<net.minecraft.resources.ResourceLocation>(recipes);
            for (int j = 0; j < recipes; j++) ids.add(buffer.readResourceLocation());
            nodes.add(new PlanView.Node(path, needs, made, ids, buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(32)));
        }
        size = count(buffer, org.berusted.craftable.planner.SearchBudget.MAX_STEPS);
        var operations = new ArrayList<PlanView.Operation>(size);
        for (int i = 0; i < size; i++) {
            var recipe = buffer.readResourceLocation();
            String path = buffer.readUtf(32);
            var inputs = stacks(buffer);
            if (inputs.size() > 9) throw new IllegalArgumentException("Oversized display grid");
            var output = ItemStack.STREAM_CODEC.decode(buffer);
            var remainders = stacks(buffer);
            var origins = new ArrayList<Integer>(inputs.size());
            for (int j = 0; j < inputs.size(); j++) origins.add(buffer.readVarInt());
            operations.add(new PlanView.Operation(recipe, path, inputs, output, remainders, origins));
        }
        return new PlanView(code, workbench, complete, completed, nodes, operations,
                stacks(buffer), stacks(buffer), stacks(buffer), stacks(buffer), missing(buffer));
    }

    static void choices(RegistryFriendlyByteBuf buffer, PlanView.Choices choices) {
        buffer.writeBoolean(choices.truncated());
        buffer.writeVarInt(choices.candidates().size());
        for (var candidate : choices.candidates()) {
            buffer.writeResourceLocation(candidate.recipe());
            ItemStack.STREAM_CODEC.encode(buffer, candidate.output());
            buffer.writeVarInt(candidate.gridSize());
            buffer.writeBoolean(candidate.rejection() != null);
            if (candidate.rejection() != null) buffer.writeEnum(candidate.rejection());
        }
    }

    static PlanView.Choices choices(RegistryFriendlyByteBuf buffer) {
        boolean truncated = buffer.readBoolean();
        int count = count(buffer, 16);
        var choices = new ArrayList<PlanView.Candidate>(count);
        for (int i = 0; i < count; i++) choices.add(new PlanView.Candidate(buffer.readResourceLocation(),
                ItemStack.STREAM_CODEC.decode(buffer), buffer.readVarInt(), buffer.readBoolean()
                        ? buffer.readEnum(org.berusted.craftable.api.CraftingResultCode.class) : null));
        return new PlanView.Choices(choices, truncated);
    }
}

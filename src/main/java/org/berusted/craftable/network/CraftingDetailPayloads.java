package org.berusted.craftable.network;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.execution.CraftingService;
import org.berusted.craftable.planner.CraftRequest;
import org.berusted.craftable.planner.PlanView;

/** On-demand detail messages. Passive recipe-book packets never carry a graph. */
public final class CraftingDetailPayloads {
    private CraftingDetailPayloads() {}
    public static final UUID NO_TOKEN = new UUID(0, 0);

    /** One view subscription replaces per-recipe passive queries. A client
     * acknowledgement is transport state only, never a resource authority. */
    public record BrowseRequest(int menuId, long revision, boolean open, UUID session, long resources)
            implements CustomPacketPayload {
        public BrowseRequest { identity(menuId, revision); if (resources < -1) throw new IllegalArgumentException("resource version"); }
        public static final Type<BrowseRequest> TYPE = new Type<>(Craftable.id("browse"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseRequest> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); b.writeBoolean(p.open); b.writeUUID(p.session); b.writeVarLong(p.resources); },
                b -> new BrowseRequest(b.readVarInt(), b.readVarLong(), b.readBoolean(), b.readUUID(), b.readVarLong()));
        @Override public Type<BrowseRequest> type() { return TYPE; }
    }

    public record BrowseLease(int menuId, long revision, UUID session, long recipes, long resources,
            long expires, String fingerprint, CraftingWire.SnapshotHeader header,
            org.berusted.craftable.config.EnvironmentScanSettings settings, boolean workbench) implements CustomPacketPayload {
        public BrowseLease {
            identity(menuId, revision);
            if (recipes < 0 || resources < 0 || fingerprint.length() != 64
                    || header != null && (!header.session().equals(session) || header.recipes() != recipes
                    || header.resources() != resources)) throw new IllegalArgumentException("Invalid browse grant");
        }
        public static final Type<BrowseLease> TYPE = new Type<>(Craftable.id("browse_lease"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseLease> CODEC = CustomPacketPayload.codec(
                (p,b) -> {
                    b.writeVarInt(p.menuId); b.writeVarLong(p.revision); b.writeUUID(p.session);
                    b.writeVarLong(p.recipes); b.writeVarLong(p.resources); b.writeLong(p.expires); b.writeUtf(p.fingerprint,64);
                    b.writeVarInt(p.settings.horizontalRadius()); b.writeVarInt(p.settings.verticalRadius());
                    b.writeVarInt(p.settings.previewCacheTicks()); b.writeBoolean(p.settings.includeEnderChest()); b.writeBoolean(p.workbench);
                    b.writeBoolean(p.header != null);
                    if (p.header != null) { b.writeUUID(p.header.transfer()); b.writeVarInt(p.header.bytes()); b.writeVarInt(p.header.chunks()); b.writeBytes(p.header.digest()); }
                }, b -> {
                    int menu = b.readVarInt(); long revision = b.readVarLong(); UUID session = b.readUUID();
                    long recipes = b.readVarLong(), resources = b.readVarLong(), expires = b.readLong(); String fingerprint = b.readUtf(64);
                    var settings = new org.berusted.craftable.config.EnvironmentScanSettings(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean());
                    boolean workbench = b.readBoolean();
                    CraftingWire.SnapshotHeader header = null;
                    if (b.readBoolean()) { UUID transfer = b.readUUID(); int bytes = b.readVarInt(), chunks = b.readVarInt(); byte[] digest = new byte[32]; b.readBytes(digest);
                        header = new CraftingWire.SnapshotHeader(session, transfer, recipes, resources, bytes, chunks, digest); }
                    return new BrowseLease(menu, revision, session, recipes, resources, expires, fingerprint, header, settings, workbench);
                });
        @Override public Type<BrowseLease> type() { return TYPE; }
    }

    public record BrowseChunk(int menuId, long revision, UUID session, UUID transfer, int index, byte[] data)
            implements CustomPacketPayload {
        public BrowseChunk {
            identity(menuId, revision);
            if (index < 0 || index >= CraftingWire.SNAPSHOT_CHUNKS || data.length > CraftingWire.SNAPSHOT_CHUNK_BYTES)
                throw new IllegalArgumentException("Invalid browse chunk");
            data = data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
        public static final Type<BrowseChunk> TYPE = new Type<>(Craftable.id("browse_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BrowseChunk> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); b.writeUUID(p.session); b.writeUUID(p.transfer); b.writeVarInt(p.index); b.writeByteArray(p.data); },
                b -> new BrowseChunk(b.readVarInt(), b.readVarLong(), b.readUUID(), b.readUUID(), b.readVarInt(), b.readByteArray(CraftingWire.SNAPSHOT_CHUNK_BYTES)));
        @Override public Type<BrowseChunk> type() { return TYPE; }
    }

    public record PreviewRequest(int menuId, long revision, CraftRequest request, String choicePath) implements CustomPacketPayload {
        public PreviewRequest {
            identity(menuId, revision);
            if (!choicePath.isEmpty() && !choicePath.matches("0(?:\\.[0-8]){0,12}"))
                throw new IllegalArgumentException("Invalid choice path");
        }
        public static final Type<PreviewRequest> TYPE = new Type<>(Craftable.id("plan_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewRequest> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); CraftingWire.request(b, p.request); b.writeUtf(p.choicePath, 32); },
                b -> new PreviewRequest(b.readVarInt(), b.readVarLong(), CraftingWire.request(b), b.readUtf(32)));
        @Override public Type<PreviewRequest> type() { return TYPE; }
    }

    public record PreviewResponse(int menuId, long revision, CraftingService.Draft draft) implements CustomPacketPayload {
        public PreviewResponse { identity(menuId, revision); }
        public static final Type<PreviewResponse> TYPE = new Type<>(Craftable.id("plan_preview_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewResponse> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.menuId); b.writeVarLong(p.revision);
                    // Enforce the byte cap before this draft leaves the server.
                    // A too-large display sends no token, never a truncated
                    // graph coupled with permission to execute hidden work.
                    var body = new RegistryFriendlyByteBuf(Unpooled.buffer(1024, CraftingWire.MAX_DETAIL_BYTES), b.registryAccess());
                    try {
                        try { writeDraft(body, p.draft); }
                        catch (IndexOutOfBoundsException | io.netty.handler.codec.EncoderException oversized) {
                            body.clear();
                            writeDraft(body, failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
                        }
                        b.writeByteArray(ByteBufUtil.getBytes(body));
                    } finally { body.release(); }
                }, b -> {
                    int menu = b.readVarInt(); long revision = b.readVarLong();
                    byte[] bytes = b.readByteArray(CraftingWire.MAX_DETAIL_BYTES);
                    var body = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), b.registryAccess());
                    try {
                        var draft = new CraftingService.Draft(body.readUUID(), CraftingWire.view(body), CraftingWire.choices(body));
                        if (body.isReadable()) throw new IllegalArgumentException("Trailing plan bytes");
                        return new PreviewResponse(menu, revision, draft);
                    } finally { body.release(); }
                });
        @Override public Type<PreviewResponse> type() { return TYPE; }
    }

    public record MaximumRequest(int menuId, long revision, CraftRequest request) implements CustomPacketPayload {
        public MaximumRequest { identity(menuId, revision); }
        public static final Type<MaximumRequest> TYPE = new Type<>(Craftable.id("plan_maximum"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MaximumRequest> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); CraftingWire.request(b, p.request); },
                b -> new MaximumRequest(b.readVarInt(), b.readVarLong(), CraftingWire.request(b)));
        @Override public Type<MaximumRequest> type() { return TYPE; }
    }

    public record MaximumResponse(int menuId, long revision, CraftingService.Maximum maximum) implements CustomPacketPayload {
        public MaximumResponse {
            identity(menuId, revision);
            if (maximum.lowerBound() < 0 || maximum.cap() < maximum.lowerBound() || maximum.cap() > 64
                    || maximum.proven() && (maximum.pending() || maximum.limited()))
                throw new IllegalArgumentException("Invalid maximum result");
        }
        public static final Type<MaximumResponse> TYPE = new Type<>(Craftable.id("plan_maximum_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MaximumResponse> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.menuId); b.writeVarLong(p.revision);
                    b.writeVarInt(p.maximum.lowerBound()); b.writeBoolean(p.maximum.proven()); b.writeVarInt(p.maximum.cap());
                    b.writeBoolean(p.maximum.limited()); b.writeBoolean(p.maximum.pending());
                }, b -> new MaximumResponse(b.readVarInt(), b.readVarLong(), new CraftingService.Maximum(
                        b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readBoolean(), b.readBoolean())));
        @Override public Type<MaximumResponse> type() { return TYPE; }
    }

    public record ConfirmRequest(int menuId, long revision, ResourceLocation recipe, UUID token) implements CustomPacketPayload {
        public ConfirmRequest { identity(menuId, revision); }
        public static final Type<ConfirmRequest> TYPE = new Type<>(Craftable.id("plan_confirm"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmRequest> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeVarInt(p.menuId); b.writeVarLong(p.revision); b.writeResourceLocation(p.recipe); b.writeUUID(p.token); },
                b -> new ConfirmRequest(b.readVarInt(), b.readVarLong(), b.readResourceLocation(), b.readUUID()));
        @Override public Type<ConfirmRequest> type() { return TYPE; }
    }

    static CraftingService.Draft failed(CraftingResultCode code) {
        return new CraftingService.Draft(NO_TOKEN, PlanView.failed(code), new PlanView.Choices(List.of(), false));
    }

    /** Local displays have the same byte bound as server drafts, but no token. */
    public static CraftingService.Draft boundedLocal(CraftingService.Draft draft, net.minecraft.core.RegistryAccess registries) {
        var body = new RegistryFriendlyByteBuf(Unpooled.buffer(1024, CraftingWire.MAX_DETAIL_BYTES), registries);
        try {
            writeDraft(body, draft);
            return draft;
        } catch (IndexOutOfBoundsException | io.netty.handler.codec.EncoderException oversized) {
            return failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
        } finally { body.release(); }
    }

    private static void writeDraft(RegistryFriendlyByteBuf buffer, CraftingService.Draft draft) {
        buffer.writeUUID(draft.token());
        CraftingWire.view(buffer, draft.view());
        CraftingWire.choices(buffer, draft.choices());
    }

    private static void identity(int menu, long revision) {
        if (menu < 0 || menu > 100 || revision < 0) throw new IllegalArgumentException("Invalid detail identity");
    }
}

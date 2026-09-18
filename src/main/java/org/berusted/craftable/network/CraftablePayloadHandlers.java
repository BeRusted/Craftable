package org.berusted.craftable.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.berusted.craftable.api.CraftingResultCode;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.execution.CraftingService;

public final class CraftablePayloadHandlers {
    private CraftablePayloadHandlers() {}

    private static final java.util.LinkedHashMap<java.util.UUID, BrowseTransfer> BROWSERS = new java.util.LinkedHashMap<>();
    private static int browseCursor, transferCursor;
    private static volatile long detailRequests, maximumRequests;
    /** Acceptance counters: count the real network entries, not just solver calls. */
    public static long detailRequests() { return detailRequests; }
    public static long maximumRequests() { return maximumRequests; }

    public static void handleBrowse(CraftingDetailPayloads.BrowseRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var existing = BROWSERS.get(player.getUUID());
            if (existing != null && payload.revision() < existing.request.revision()) return;
            if (!payload.open() || player.containerMenu.containerId != payload.menuId() || !CraftingService.validContext(player)) {
                closeBrowse(player); return;
            }
            if (!CraftableRequestLimiter.allowStatus(player.getUUID(), player.level().getGameTime())) return;
            if (existing == null || existing.request.revision() != payload.revision()) {
                // Admission is bounded independently of player count. Refusal
                // leaves the client pending; never reuse another player's view.
                if (existing == null && BROWSERS.size() >= 64) return;
                org.berusted.craftable.execution.CraftingSessions.closeBrowsing(player);
                existing = new BrowseTransfer(payload);
                BROWSERS.put(player.getUUID(), existing);
            }
            existing.request = payload;
            existing.heard = player.level().getGameTime();
        });
    }

    public static void closeBrowse(ServerPlayer player) {
        BROWSERS.remove(player.getUUID());
        org.berusted.craftable.execution.CraftingSessions.closeBrowsing(player);
    }
    static void stopBrowsing() { BROWSERS.clear(); browseCursor = transferCursor = 0; }

    /** Existing admission accounting covers discovery and encoding. Two
     * round-robin cursors separately bound preparation and wire traffic; slow
     * receivers do not get additional per-target searches or larger budgets. */
    public static void browseTick(net.minecraft.server.MinecraftServer server) {
        BROWSERS.entrySet().removeIf(e -> {
            var player = server.getPlayerList().getPlayer(e.getKey());
            boolean invalid = player == null || !CraftingService.validContext(player)
                    || player.containerMenu.containerId != e.getValue().request.menuId()
                    || player.level().getGameTime() - e.getValue().heard > 40;
            if (invalid && player != null) org.berusted.craftable.execution.CraftingSessions.closeBrowsing(player);
            return invalid;
        });
        if (BROWSERS.isEmpty()) return;
        var ids = java.util.List.copyOf(BROWSERS.keySet());
        for (int i = 0; i < Math.min(4, ids.size()); i++) {
            var id = ids.get(Math.floorMod(browseCursor++, ids.size()));
            var transfer = BROWSERS.get(id);
            var player = server.getPlayerList().getPlayer(id);
            long now = player.level().getGameTime();
            if (now < transfer.nextScan) continue;
            // Bound concurrent byte transfers, not the number of authorized
            // viewers. A ninth viewer must not wait for someone to close a UI.
            if (transfer.bytes == null && BROWSERS.values().stream().filter(v -> v.bytes != null).count() >= 8) continue;
            try (var lease = CraftableRequestLimiter.planning(server, id, false)) {
                if (!lease.allowed()) continue;
                var snapshot = org.berusted.craftable.execution.CraftingSessions.refreshBrowsing(player);
                boolean changed = transfer.snapshot != snapshot;
                transfer.snapshot = snapshot;
                transfer.nextScan = now + 8;
                CraftingWire.SnapshotHeader header = null;
                if (changed) {
                    transfer.bytes = CraftingWire.snapshot(snapshot, player.registryAccess());
                    transfer.retained = 2L * transfer.bytes.length + 256L * snapshot.inputs().size() + 36L * 1024;
                    if (BROWSERS.values().stream().mapToLong(v -> v.retained).sum() > 64L * 1024 * 1024)
                        throw new IllegalStateException("Global browsing memory bound");
                    header = new CraftingWire.SnapshotHeader(snapshot.session(), java.util.UUID.randomUUID(),
                            snapshot.recipes(), snapshot.resources(), transfer.bytes.length,
                            Math.max(1, (transfer.bytes.length + CraftingWire.SNAPSHOT_CHUNK_BYTES - 1) / CraftingWire.SNAPSHOT_CHUNK_BYTES),
                            CraftingWire.sha256().digest(transfer.bytes));
                    transfer.header = header;
                    transfer.chunk = 0;
                }
                var fingerprint = new org.berusted.craftable.recipe.CraftingRecipes(player, snapshot.workbench())
                        .planningInput().fingerprint();
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new CraftingDetailPayloads.BrowseLease(transfer.request.menuId(), transfer.request.revision(),
                                snapshot.session(), snapshot.recipes(), snapshot.resources(), now + 10, fingerprint, header,
                                org.berusted.craftable.config.CraftableServerConfig.scanSettings(), snapshot.workbench()));
            } catch (RuntimeException failure) {
                // Release all retained values on failed normalization/capture.
                transfer.bytes = null; transfer.snapshot = null; transfer.retained = 0;
                transfer.nextScan = now + 100;
                org.berusted.craftable.execution.CraftingSessions.closeBrowsing(player);
                org.berusted.craftable.Craftable.LOGGER.warn("Browsing snapshot unavailable ({})", failure.getClass().getSimpleName());
            }
        }
        for (var id : transferTurn(ids, candidate -> BROWSERS.get(candidate).bytes != null)) {
            var transfer = BROWSERS.get(id);
            int offset = transfer.chunk * CraftingWire.SNAPSHOT_CHUNK_BYTES;
            var bytes = java.util.Arrays.copyOfRange(transfer.bytes, offset,
                    Math.min(transfer.bytes.length, offset + CraftingWire.SNAPSHOT_CHUNK_BYTES));
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(server.getPlayerList().getPlayer(id),
                    new CraftingDetailPayloads.BrowseChunk(transfer.request.menuId(), transfer.request.revision(),
                            transfer.header.session(), transfer.header.transfer(), transfer.chunk++, bytes));
            if (transfer.chunk == transfer.header.chunks()) transfer.bytes = null;
        }
    }

    /** One bounded wire turn: at most four chunks globally and one per viewer.
     * Keep selection separate from I/O so the actual cursor policy is exercised
     * together with receiver deadlines, not copied into a simulated scheduler. */
    static java.util.List<java.util.UUID> transferTurn(java.util.List<java.util.UUID> ids,
            java.util.function.Predicate<java.util.UUID> pending) {
        var selected = new java.util.ArrayList<java.util.UUID>(4);
        for (int visited = 0; visited < ids.size() && selected.size() < 4; visited++) {
            var id = ids.get(Math.floorMod(transferCursor++, ids.size()));
            if (pending.test(id)) selected.add(id);
        }
        return selected;
    }

    private static final class BrowseTransfer {
        CraftingDetailPayloads.BrowseRequest request;
        org.berusted.craftable.environment.BrowsingSnapshot snapshot;
        CraftingWire.SnapshotHeader header;
        byte[] bytes;
        int chunk;
        long heard, nextScan, retained;
        BrowseTransfer(CraftingDetailPayloads.BrowseRequest request) { this.request = request; }
    }

    public static void handlePlanPreview(CraftingDetailPayloads.PreviewRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            detailRequests++;
            var result = CraftingDetailPayloads.failed(CraftingResultCode.INVALID_CONTEXT);
            if (player.containerMenu.containerId == payload.menuId() && CraftingService.validContext(player)) {
                result = CraftingDetailPayloads.failed(CraftingResultCode.REQUEST_THROTTLED);
                if (CraftableRequestLimiter.allowDetail(player.getUUID(), player.level().getGameTime()))
                    try (var lease = CraftableRequestLimiter.planning(player.getServer(), player.getUUID(), true)) {
                        if (lease.allowed()) result = CraftingService.preview(player, payload.request(), payload.choicePath());
                    }
            }
            context.reply(new CraftingDetailPayloads.PreviewResponse(payload.menuId(), payload.revision(), result));
        });
    }

    public static void handlePlanMaximum(CraftingDetailPayloads.MaximumRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            maximumRequests++;
            var result = new CraftingService.Maximum(0, false, 0, true, false);
            if (player.containerMenu.containerId == payload.menuId() && CraftingService.validContext(player)) {
                result = new CraftingService.Maximum(0, false, 0, true, true);
                if (CraftableRequestLimiter.allowDetail(player.getUUID(), player.level().getGameTime()))
                    try (var lease = CraftableRequestLimiter.planning(player.getServer(), player.getUUID(), false)) {
                        if (lease.allowed()) result = CraftingService.maximum(player, payload.request());
                    } catch (RuntimeException failure) {
                        org.berusted.craftable.Craftable.LOGGER.error("Maximum preview failed", failure);
                        result = new CraftingService.Maximum(0, false, 0, true, false);
                    }
            }
            context.reply(new CraftingDetailPayloads.MaximumResponse(payload.menuId(), payload.revision(), result));
        });
    }

    public static void handlePlanConfirm(CraftingDetailPayloads.ConfirmRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (player.containerMenu.containerId != payload.menuId() || !CraftingService.validContext(player)) {
                context.reply(new CreateRecipeResultPayload(payload.recipe(), payload.revision(), CraftingResultCode.INVALID_CONTEXT));
                return;
            }
            if (CraftableRequestLimiter.allowCreate(player.getUUID(), player.level().getGameTime()))
                try (var lease = CraftableRequestLimiter.planning(player.getServer(), player.getUUID(), true)) {
                    if (lease.allowed()) {
                        var result = CraftingService.confirm(player, payload.token());
                        var target = result.plan() == null ? payload.recipe() : result.plan().target();
                        context.reply(CreateRecipeResultPayload.from(target, payload.revision(), result));
                        return;
                    }
                }
            context.reply(new CreateRecipeResultPayload(payload.recipe(), payload.revision(), CraftingResultCode.REQUEST_THROTTLED));
        });
    }

    public static void handlePlanPreviewResult(CraftingDetailPayloads.PreviewResponse payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handle(payload));
    }

    public static void handlePlanMaximumResult(CraftingDetailPayloads.MaximumResponse payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handle(payload));
    }

    public static void handleOpenInventory(OpenInventoryRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || payload.requestId() < 0
                    || !org.berusted.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())
                    || !player.isAlive() || player.containerMenu != player.inventoryMenu
                    || !CraftableRequestLimiter.allowOpen(player.getUUID(), player.serverLevel().getGameTime())) return;
            var snapshot = EnvironmentSnapshotService.fresh(player);
            var tables = snapshot.workstations().stream()
                    .filter(w -> w.capability() == org.berusted.craftable.workstation.WorkstationCapability.CRAFTING_3X3)
                    .map(w -> w.position()).filter(java.util.Objects::nonNull).toList();
            if (tables.isEmpty()) return;
            // openMenu does not remove inventoryMenu itself. Return its grid and
            // carried stack first, so opening cannot strand items in the old 2x2.
            player.inventoryMenu.removed(player);
            player.inventoryMenu.broadcastChanges();
            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inventory, owner) -> new org.berusted.craftable.menu.AmbientInventoryMenu(id, inventory, tables),
                    net.minecraft.network.chat.Component.translatable("container.crafting")));
        });
    }


    public static void handleCreateRequest(CreateRecipeRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !org.berusted.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())) {
                return;
            }
            if (payload.requestId() < 0) {
                return;
            }
            if (!CraftableRequestLimiter.allowCreate(player.getUUID(), player.serverLevel().getGameTime())) {
                context.reply(new CreateRecipeResultPayload(
                        payload.recipeId(), payload.requestId(), CraftingResultCode.REQUEST_THROTTLED));
                return;
            }
            try (var lease = CraftableRequestLimiter.planning(player.getServer(), player.getUUID(), true)) {
                if (!lease.allowed()) {
                    context.reply(new CreateRecipeResultPayload(payload.recipeId(), payload.requestId(), CraftingResultCode.REQUEST_THROTTLED));
                    return;
                }
                var result = CraftingService.attempt(player, payload.recipeId(), payload.requestId(),
                        payload.precedingPress(), payload.allowDrops(), payload.partialPolicy());
                context.reply(CreateRecipeResultPayload.from(payload.recipeId(), payload.requestId(), result));
            }
        });
    }

    public static void handleCreateResult(CreateRecipeResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handle(payload));
    }
}

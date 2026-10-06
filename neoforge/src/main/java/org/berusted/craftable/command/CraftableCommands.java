package org.berusted.craftable.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.berusted.craftable.Craftable;
import org.berusted.craftable.environment.ContainerEndpoint;
import org.berusted.craftable.environment.EnvironmentSnapshot;
import org.berusted.craftable.environment.EnvironmentSnapshotService;
import org.berusted.craftable.workstation.WorkstationEndpoint;

/** Read-only diagnostics for validating the server's environment contract. */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class CraftableCommands {
    private static final int MAX_LISTED_ENDPOINTS = 32;

    private CraftableCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("craftable")
                .requires(CraftableCommands::mayInspectEnvironment)
                .then(Commands.literal("environment")
                        .executes(context -> inspectEnvironment(context.getSource()))));
    }

    private static boolean mayInspectEnvironment(CommandSourceStack source) {
        return source.hasPermission(2) || !FMLEnvironment.production;
    }

    private static int inspectEnvironment(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        EnvironmentSnapshot snapshot = EnvironmentSnapshotService.preview(player);
        source.sendSuccess(() -> environmentSummary(snapshot), false);

        int listed = 0;
        for (ContainerEndpoint endpoint : snapshot.endpoints()) {
            if (listed++ >= MAX_LISTED_ENDPOINTS) {
                break;
            }
            EndpointSummary summary = summarize(endpoint);
            source.sendSuccess(() -> Component.translatable(
                    "command.craftable.environment.endpoint",
                    endpoint.id(),
                    endpoint.slotCount(),
                    summary.occupiedSlots(),
                    summary.itemCount()), false);
        }
        for (WorkstationEndpoint workstation : snapshot.workstations()) {
            if (listed++ >= MAX_LISTED_ENDPOINTS) {
                break;
            }
            source.sendSuccess(() -> Component.translatable(
                    "command.craftable.environment.workstation",
                    workstation.id(),
                    workstation.capability().name()), false);
        }
        int total = snapshot.endpoints().size() + snapshot.workstations().size();
        if (total > MAX_LISTED_ENDPOINTS) {
            int omitted = total - MAX_LISTED_ENDPOINTS;
            source.sendSuccess(() -> Component.translatable(
                    "command.craftable.environment.truncated", omitted), false);
        }
        return total;
    }

    static Component environmentSummary(EnvironmentSnapshot snapshot) {
        // ResourceLocation has a useful textual form but is not a legal
        // translatable-component argument. Convert it at this presentation
        // boundary so development and production builds behave identically.
        return Component.translatable(
                "command.craftable.environment.summary",
                snapshot.generation(),
                snapshot.dimension().location().toString(),
                position(snapshot),
                snapshot.scanSettings().horizontalRadius(),
                snapshot.scanSettings().verticalRadius(),
                snapshot.endpoints().size(),
                snapshot.workstations().size());
    }

    private static EndpointSummary summarize(ContainerEndpoint endpoint) {
        Container container = endpoint.container();
        int occupiedSlots = 0;
        long itemCount = 0;
        int lastSlot = endpoint.firstSlot() + endpoint.slotCount();
        for (int slot = endpoint.firstSlot(); slot < lastSlot; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                occupiedSlots++;
                itemCount += stack.getCount();
            }
        }
        // Deliberately omit item identities: diagnostics should prove discovery
        // structure without turning logs or screenshots into an inventory leak.
        return new EndpointSummary(occupiedSlots, itemCount);
    }

    private static String position(EnvironmentSnapshot snapshot) {
        return snapshot.origin().getX() + "," + snapshot.origin().getY() + "," + snapshot.origin().getZ();
    }

    private record EndpointSummary(int occupiedSlots, long itemCount) {}
}

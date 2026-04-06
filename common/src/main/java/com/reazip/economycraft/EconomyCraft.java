package com.reazip.economycraft;

import com.reazip.economycraft.util.ChatCompat;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.text.NumberFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EconomyCraft {
    public static final String MOD_ID = "economycraft";
    private static EconomyManager manager;
    private static MinecraftServer lastServer;
    public static final Logger LOGGER = LoggerFactory.getLogger("EconomyCraft");
    private static final NumberFormat FORMAT = NumberFormat.getInstance(Locale.GERMANY);

    public static void registerEvents() {
        LifecycleEvent.SERVER_STARTING.register(EconomyConfig::load);

        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            EconomyCommands.register(dispatcher);
        });

        LifecycleEvent.SERVER_STARTED.register(EconomyCraft::getManager);

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            if (manager != null && lastServer == server) {
                manager.shutdown();
                manager = null;
                lastServer = null;
            }
        });

        // --- Lore Update Events ---

        PlayerEvent.PLAYER_JOIN.register(EconomyCraft::onPlayerJoin);

        PlayerEvent.PICKUP_ITEM_PRE.register((player, entity, stack) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                MinecraftServer server = serverPlayer.level().getServer();
                if (server != null) {
                    getManager(server).applyPriceLore(stack);
                }
            }
            return dev.architectury.event.EventResult.pass();
        });

        PlayerEvent.CRAFT_ITEM.register((player, stack, inventory) -> {
            if (player instanceof ServerPlayer serverPlayer) {
                MinecraftServer server = serverPlayer.level().getServer();
                if (server != null) {
                    getManager(server).applyPriceLore(stack);
                }
            }
        });
    }

    private static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        EconomyManager eco = getManager(server);
        
        eco.getBestName(player.getUUID()); 
        eco.getBalance(player.getUUID(), true);
        
        eco.refreshPlayerInventory(player);

        if (eco.getOrders().hasDeliveries(player.getUUID()) || eco.getShop().hasDeliveries(player.getUUID())) {
            ClickEvent ev = ChatCompat.runCommandEvent("/eco orders claim");

            if (ev != null) {
                Component msg = Component.literal("Je hebt ongeclaimde items: ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("[Claim]")
                                .withStyle(s -> s.withUnderlined(true).withColor(ChatFormatting.GREEN).withClickEvent(ev)));
                player.sendSystemMessage(msg);
            } else {
                ChatCompat.sendRunCommandTellraw(
                        player,
                        "Je hebt ongeclaimde items: ",
                        "[Claim]",
                        "/eco orders claim"
                );
            }
        }
    }

    public static EconomyManager getManager(MinecraftServer server) {
        if (manager == null || lastServer != server) {
            manager = new EconomyManager(server);
            lastServer = server;
        }
        return manager;
    }

    public static Component createBalanceTitle(String baseTitle, ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return Component.literal(baseTitle);
        
        EconomyManager eco = getManager(server);
        long balance = eco.getBalance(player.getUUID(), true);
        return Component.literal(baseTitle + "Saldo: " + formatMoney(balance));
    }

    public static String formatMoney(long amount) {
        return "$" + FORMAT.format(amount);
    }
}

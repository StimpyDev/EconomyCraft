package com.reazip.economycraft;

import com.reazip.economycraft.util.ChatCompat;
import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.text.NumberFormat;
import java.util.Locale;

public final class EconomyCraft {
    public static final String MOD_ID = "economycraft";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static EconomyManager manager;
    private static MinecraftServer lastServer;
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

        PlayerEvent.PLAYER_JOIN.register(EconomyCraft::onPlayerJoin);

    }

    private static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        EconomyManager eco = getManager(server);
        
        eco.getBestName(player.getUUID()); 
        eco.getBalance(player.getUUID(), true);

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

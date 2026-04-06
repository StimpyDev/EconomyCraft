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
import net.minecraft.world.item.ItemStack;

import java.text.NumberFormat;
import java.util.Locale;

public final class EconomyCraft {
    public static final String MOD_ID = "economycraft";
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

        dev.architectury.event.events.common.MenuEvent.OPEN.register((menu, player) -> {
            if (player instanceof ServerPlayer) {
                for (net.minecraft.world.inventory.Slot slot : menu.slots) {
                    cleanItemLore(slot.getItem());
                }
            }
        });
    }

    private static void cleanItemLore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore == null) return;

        java.util.List<Component> lines = lore.lines();
        boolean changed = false;
        java.util.List<Component> newLines = new java.util.ArrayList<>();

        for (Component line : lines) {
            String text = line.getString();
            if (text.contains("Verkoopprijs:")) {
                changed = true;
                continue; 
            }
            newLines.add(line);
        }

        if (changed) {
            if (newLines.isEmpty()) {
                stack.remove(net.minecraft.core.component.DataComponents.LORE);
            } else {
                stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(newLines));
            }
        }
    }

    private static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        EconomyManager eco = getManager(server);
        eco.getBestName(player.getUUID()); 
        eco.getBalance(player.getUUID(), true);

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            cleanItemLore(player.getInventory().getItem(i));
        }
        
        for (ItemStack armor : player.getArmorSlots()) {
            cleanItemLore(armor);
        }
        cleanItemLore(player.getOffhandItem());

        if (eco.getOrders().hasDeliveries(player.getUUID()) || eco.getShop().hasDeliveries(player.getUUID())) {
            ClickEvent ev = ChatCompat.runCommandEvent("/eco orders claim");
            if (ev != null) {
                Component msg = Component.literal("Je hebt ongeclaimde items: ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("[Claim]")
                                .withStyle(s -> s.withUnderlined(true).withColor(ChatFormatting.GREEN).withClickEvent(ev)));
                player.sendSystemMessage(msg);
            } else {
                ChatCompat.sendRunCommandTellraw(player, "Je hebt ongeclaimde items: ", "[Claim]", "/eco orders claim");
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

    public static String formatMoney(long amount) {
        return "$" + FORMAT.format(amount);
    }
}

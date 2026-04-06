package com.reazip.economycraft;

import com.reazip.economycraft.util.ChatCompat;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
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
                cleanMenuSlots(menu);
            }
        });
    }

    private static void cleanItemLore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return;

        List<Component> lines = lore.lines();
        boolean changed = false;
        List<Component> newLines = new ArrayList<>();

        for (Component line : lines) {
            String text = line.getString();
            if (text.toLowerCase().contains("verkoopprijs:")) {
                changed = true;
                continue; 
            }
            newLines.add(line);
        }

        if (changed) {
            if (newLines.isEmpty()) {
                stack.remove(DataComponents.LORE);
            } else {
                stack.set(DataComponents.LORE, new ItemLore(newLines));
            }
        }
    }

    private static void cleanMenuSlots(AbstractContainerMenu menu) {
        if (menu == null) return;
        for (Slot slot : menu.slots) {
            cleanItemLore(slot.getItem());
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

        if (eco.getOrders().hasDeliveries(player.getUUID()) || eco.getShop().hasDeliveries(player.getUUID())) {
            sendDeliveryNotice(player);
        }
    }

    private static void sendDeliveryNotice(ServerPlayer player) {
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

package com.reazip.economycraft;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.reazip.economycraft.PriceRegistry.ResolvedPrice;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.IdentifierCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class SellCommand {
    private static final Map<UUID, PendingSale> PENDING = new HashMap<>();
    private static final long CONFIRM_EXPIRY_MS = 20_000L;

    private SellCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return literal("sell")
                .then(literal("all")
                        .executes(SellCommand::previewSellAll)
                        .then(literal("confirm").executes(SellCommand::confirmSellAll)))
                .then(argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> sellMainHand(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))
                .executes(ctx -> sellMainHand(ctx, -1));
    }

    private static int sellMainHand(CommandContext<CommandSourceStack> ctx, int amount) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) return 0;

        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            source.sendFailure(Component.literal("Je houdt geen item vast.").withStyle(ChatFormatting.RED));
            return 0;
        }

        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        PriceRegistry prices = manager.getPrices();

        ResolvedPrice resolved = prices.resolve(hand);
        Long unitSell = prices.getUnitSell(hand);
        if (resolved == null || unitSell == null) {
            source.sendFailure(Component.literal("Dit item kan niet worden verkocht.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (prices.isSellBlockedByDamage(hand)) {
            source.sendFailure(Component.literal("Items die beschadigd zijn kan niet worden verkocht.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (prices.isSellBlockedByContents(hand)) {
            source.sendFailure(Component.literal("Items met inhoud mogen niet worden verkocht.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int available = hand.getCount();
        int toSell = amount < 0 ? available : amount;
        if (toSell < 1 || toSell > available) {
            source.sendFailure(Component.literal("Ongeldig bedrag.").withStyle(ChatFormatting.RED));
            return 0;
        }

        String itemName = hand.getHoverName().getString();
        Long total = safeMultiply(unitSell, toSell);
        if (total == null) {
            source.sendFailure(Component.literal("Het verkoopbedrag is te hoog.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (EconomyConfig.get().dailySellLimit > 0 && manager.tryRecordDailySell(player.getUUID(), total)) {
            return handleDailyLimitFailure(manager, player, source);
        }

        hand.shrink(toSell);
        if (hand.isEmpty()) {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } else {
            manager.applyPriceLore(hand);
        }

        manager.addMoney(player.getUUID(), total);
        
        player.sendSystemMessage(Component.literal("Succesvol verkocht ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(toSell + "x " + itemName).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" voor ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(EconomyCraft.formatMoney(total)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(".").withStyle(ChatFormatting.GREEN)));
                
        return toSell;
    }

    private static int previewSellAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) return 0;

        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            source.sendFailure(Component.literal("Je houdt geen item vast.").withStyle(ChatFormatting.RED));
            return 0;
        }

        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        PriceRegistry prices = manager.getPrices();
        ResolvedPrice resolved = prices.resolve(hand);
        Long unitSell = prices.getUnitSell(hand);
        if (resolved == null || unitSell == null) {
            source.sendFailure(Component.literal("Dit item kan niet worden verkocht.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (prices.isSellBlockedByDamage(hand)) {
            source.sendFailure(Component.literal("Items die beschadigd zijn kan niet worden verkocht.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (prices.isSellBlockedByContents(hand)) {
            source.sendFailure(Component.literal("Items met inhoud mogen niet worden verkocht.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int totalCount = countMatchingSellable(player, prices, resolved.key());
        if (totalCount <= 0) {
            source.sendFailure(Component.literal("Geen verkoopbare items gevonden in inventory.").withStyle(ChatFormatting.RED));
            return 0;
        }

        Long total = safeMultiply(unitSell, totalCount);
        if (total == null) {
            source.sendFailure(Component.literal("Het verkoopbedrag is te hoog.").withStyle(ChatFormatting.RED));
            return 0;
        }

        IdentifierCompat.Id heldItemId = IdentifierCompat.wrap(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hand.getItem()));
        PENDING.put(player.getUUID(), new PendingSale(resolved.key(), totalCount, total,
                System.currentTimeMillis() + CONFIRM_EXPIRY_MS, heldItemId));

        String itemName = hand.getHoverName().getString();
        
        MutableComponent base = Component.literal("Dit item kan worden verkocht ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(totalCount + "x " + itemName).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" voor ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(EconomyCraft.formatMoney(total)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(". ").withStyle(ChatFormatting.GREEN));

        ClickEvent ev = ChatCompat.runCommandEvent("/sell all confirm");
        if (ev != null) {
            player.sendSystemMessage(base.append(Component.literal("[KLIK HIER OM TE BEVESTIGEN]")
                    .withStyle(s -> s.withUnderlined(true).withColor(ChatFormatting.GREEN).withClickEvent(ev))));
        } else {
            player.sendSystemMessage(base.append(Component.literal("Typ /sell all confirm om te bevestigen.")));
        }

        return totalCount;
    }

    private static int confirmSellAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) return 0;

        PendingSale pending = PENDING.get(player.getUUID());
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            source.sendFailure(Component.literal("Geen lopende verkoop of sessie verlopen.").withStyle(ChatFormatting.RED));
            PENDING.remove(player.getUUID());
            return 0;
        }

        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        PriceRegistry prices = manager.getPrices();

        ItemStack hand = player.getMainHandItem();
        ResolvedPrice current = prices.resolve(hand);
        
        if (current == null || !pending.key().equals(current.key())) {
            source.sendFailure(Component.literal("Het vastgehouden item is gewijzigd.").withStyle(ChatFormatting.RED));
            PENDING.remove(player.getUUID());
            return 0;
        }

        if (EconomyConfig.get().dailySellLimit > 0 && manager.tryRecordDailySell(player.getUUID(), pending.total())) {
            return handleDailyLimitFailure(manager, player, source);
        }

        String itemName = hand.getHoverName().getString();
        removeMatching(player, prices, pending.key(), pending.count());
        manager.addMoney(player.getUUID(), pending.total());

        player.sendSystemMessage(Component.literal("Succesvol verkocht ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(pending.count() + "x " + itemName).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" voor ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(EconomyCraft.formatMoney(pending.total())).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(".").withStyle(ChatFormatting.GREEN)));

        PENDING.remove(player.getUUID());
        return pending.count();
    }

    private static int countMatchingSellable(ServerPlayer player, PriceRegistry prices, IdentifierCompat.Id key) {
        var inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (isMatchingSellable(prices, stack, key)) {
                total += stack.getCount();
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (isMatchingSellable(prices, offhand, key)) {
            total += offhand.getCount();
        }
        return total;
    }

    private static void removeMatching(ServerPlayer player, PriceRegistry prices, IdentifierCompat.Id key, int toRemove) {
        var inv = player.getInventory();
        int remaining = toRemove;
        EconomyManager manager = EconomyCraft.getManager(player.getServer());

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (isMatchingSellable(prices, stack, key)) {
                int remove = Math.min(remaining, stack.getCount());
                stack.shrink(remove);
                remaining -= remove;
                
                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                } else {
                    manager.applyPriceLore(stack);
                }
            }
            if (remaining <= 0) return;
        }

        ItemStack offhand = player.getOffhandItem();
        if (remaining > 0 && isMatchingSellable(prices, offhand, key)) {
            int remove = Math.min(remaining, offhand.getCount());
            offhand.shrink(remove);
            if (offhand.isEmpty()) {
                player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
            } else {
                manager.applyPriceLore(offhand);
            }
        }
    }

    private static boolean isMatchingSellable(PriceRegistry prices, ItemStack stack, IdentifierCompat.Id key) {
        if (stack == null || stack.isEmpty()) return false;
        if (prices.isSellBlockedByDamage(stack)) return false;
        if (prices.isSellBlockedByContents(stack)) return false;
        ResolvedPrice rp = prices.resolve(stack);
        return rp != null && key.equals(rp.key()) && prices.getUnitSell(stack) != null;
    }

    private static Long safeMultiply(long value, int count) {
        try {
            return Math.multiplyExact(value, count);
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private static ServerPlayer getPlayer(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Alleen spelers kunnen dit commando gebruiken.").withStyle(ChatFormatting.RED));
            return null;
        }
    }

    private record PendingSale(IdentifierCompat.Id key, int count, long total, long expiresAt,
                               IdentifierCompat.Id heldItemId) {}

    private static int handleDailyLimitFailure(EconomyManager manager, ServerPlayer player, CommandSourceStack source) {
        long remaining = manager.getDailySellRemaining(player.getUUID());
        long limit = EconomyConfig.get().dailySellLimit;

        if (remaining <= 0) {
            source.sendFailure(Component.literal("Dagelijkse limiet (" + EconomyCraft.formatMoney(limit) + ") bereikt. Probeer morgen weer.")
                    .withStyle(ChatFormatting.RED));
        } else {
            source.sendFailure(Component.literal("Overschrijdt limiet. Je kunt vandaag nog maximaal " +
                            EconomyCraft.formatMoney(remaining) + " aan spullen verkopen.")
                    .withStyle(ChatFormatting.RED));
        }
        return 0;
    }
}

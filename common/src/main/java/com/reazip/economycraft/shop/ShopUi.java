package com.reazip.economycraft.shop;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.IdentityCompat;
import com.reazip.economycraft.util.ProfileComponentCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import com.mojang.authlib.GameProfile;

import java.util.ArrayList;
import java.util.List;

public final class ShopUi {
    private ShopUi() {}

    private static final ChatFormatting LABEL_PRIMARY_COLOR = ChatFormatting.GOLD;
    private static final ChatFormatting LABEL_SECONDARY_COLOR = ChatFormatting.AQUA;
    private static final ChatFormatting VALUE_COLOR = ChatFormatting.DARK_PURPLE;
    private static final ChatFormatting BALANCE_NAME_COLOR = ChatFormatting.YELLOW;
    private static final ChatFormatting BALANCE_LABEL_COLOR = ChatFormatting.GOLD;
    private static final ChatFormatting BALANCE_VALUE_COLOR = ChatFormatting.DARK_PURPLE;

    public static void open(ServerPlayer player, ShopManager shop) {
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.literal("Veilingshuis"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ShopMenu(id, inv, shop, (ServerPlayer) p);
            }
        });
    }

    static void openConfirm(ServerPlayer player, ShopManager shop, ShopListing listing) {
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.literal("Bevestigen"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ConfirmMenu(id, inv, shop, listing, (ServerPlayer) p);
            }
        });
    }

    private static void openRemove(ServerPlayer player, ShopManager shop, ShopListing listing) {
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.literal("Verwijder"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new RemoveMenu(id, inv, shop, listing, (ServerPlayer) p);
            }
        });
    }

    private static Component createPriceLore(long price, long tax) {
        StringBuilder value = new StringBuilder(EconomyCraft.formatMoney(price));
        if (tax > 0) value.append(" (+").append(EconomyCraft.formatMoney(tax)).append(" tax)");
        return labeledValue("Prijs", value.toString(), LABEL_PRIMARY_COLOR);
    }

    private static ItemStack createBalanceItem(ServerPlayer player) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        GameProfile profile = player.getGameProfile();
        ProfileComponentCompat.tryResolvedOrUnresolved(profile).ifPresent(resolvable ->
                head.set(net.minecraft.core.component.DataComponents.PROFILE, resolvable));
        long balance = EconomyCraft.getManager(player.level().getServer()).getBalance(player.getUUID(), true);
        head.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(IdentityCompat.of(player).name()).withStyle(s -> s.withItalic(false).withColor(BALANCE_NAME_COLOR)));
        head.set(net.minecraft.core.component.DataComponents.LORE, new ItemLore(List.of(balanceLore(balance))));
        return head;
    }

    private static Component balanceLore(long balance) {
        return Component.literal("Saldo: ").withStyle(s -> s.withItalic(false).withColor(BALANCE_LABEL_COLOR))
                .append(Component.literal(EconomyCraft.formatMoney(balance)).withStyle(s -> s.withItalic(false).withColor(BALANCE_VALUE_COLOR)));
    }

    private static Component labeledValue(String label, String value, ChatFormatting labelColor) {
        return Component.literal(label + ": ").withStyle(s -> s.withItalic(false).withColor(labelColor))
                .append(Component.literal(value).withStyle(s -> s.withItalic(false).withColor(VALUE_COLOR)));
    }

    // --- MAIN SHOP MENU ---
    private static class ShopMenu extends AbstractContainerMenu {
        private final ShopManager shop;
        private final ServerPlayer viewer;
        private List<ShopListing> listings = new ArrayList<>();
        private final SimpleContainer container = new SimpleContainer(54);
        private int page = 0;
        private final Runnable listener = this::updatePage;

        ShopMenu(int id, Inventory inv, ShopManager shop, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x6, id);
            this.shop = shop;
            this.viewer = viewer;
            updatePage();
            shop.addListener(listener);
            for (int i = 0; i < 54; i++) {
                this.addSlot(new Slot(container, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                    @Override public boolean mayPickup(Player p) { return false; }
                });
            }
            int y = 140;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) {
                    this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
                }
            }
            for (int c = 0; c < 9; c++) {
                this.addSlot(new Slot(inv, c, 8 + c * 18, y + 58));
            }
        }

        private void updatePage() {
            listings = new ArrayList<>(shop.getListings());
            container.clearContent();
            int start = page * 45;
            for (int i = 0; i < 45; i++) {
                int idx = start + i;
                if (idx >= listings.size()) break;
                ShopListing l = listings.get(idx);
                ItemStack display = l.item.copy();
                String sellerName = l.seller.equals(viewer.getUUID()) ? "Jij" : EconomyCraft.getManager(viewer.level().getServer()).getBestName(l.seller);
                long tax = Math.round(l.price * EconomyConfig.get().taxRate);
                display.set(net.minecraft.core.component.DataComponents.LORE, new ItemLore(List.of(
                        createPriceLore(l.price, tax),
                        labeledValue("Verkoper", sellerName, LABEL_SECONDARY_COLOR))));
                container.setItem(i, display);
            }
            container.setItem(45, createBalanceItem(viewer));
        }

        @Override public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP && slot >= 0 && slot < 45) {
                int idx = (page * 45) + slot;
                if (idx < listings.size()) {
                    ShopListing l = listings.get(idx);
                    if (l.seller.equals(player.getUUID())) openRemove((ServerPlayer) player, shop, l);
                    else openConfirm((ServerPlayer) player, shop, l);
                }
            }
            super.clicked(slot, dragType, type, player);
        }

        @Override public boolean stillValid(Player p) { return true; }
        @Override public void removed(Player p) { super.removed(p); shop.removeListener(listener); }
        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    }

    // --- CONFIRM PURCHASE MENU ---
    private static class ConfirmMenu extends AbstractContainerMenu {
        private final ShopManager shop;
        private final ShopListing listing;
        private final SimpleContainer container = new SimpleContainer(9);

        ConfirmMenu(int id, Inventory inv, ShopManager shop, ShopListing listing, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x1, id);
            this.shop = shop;
            this.listing = listing;

            ItemStack confirm = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            confirm.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Bevestigen").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            container.setItem(2, confirm);

            ItemStack display = listing.item.copy();
            container.setItem(4, display);

            ItemStack cancel = new ItemStack(Items.RED_STAINED_GLASS_PANE);
            cancel.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Annuleren").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            container.setItem(6, cancel);

            for (int i = 0; i < 9; i++) {
                this.addSlot(new Slot(container, i, 8 + i * 18, 20) { @Override public boolean mayPickup(Player p) { return false; }});
            }
        }

        @Override public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP) {
                if (slot == 2) {
                    ShopListing current = shop.getListing(listing.id);
                    ServerPlayer sp = (ServerPlayer) player;
                    if (current == null) {
                        sp.sendSystemMessage(Component.literal("Niet langer beschikbaar.").withStyle(ChatFormatting.RED));
                    } else {
                        EconomyManager eco = EconomyCraft.getManager(sp.server);
                        long total = current.price + Math.round(current.price * EconomyConfig.get().taxRate);
                        if (eco.getBalance(sp.getUUID(), true) < total) {
                            sp.sendSystemMessage(Component.literal("Onvoldoende saldo.").withStyle(ChatFormatting.RED));
                        } else {
                            eco.removeMoney(sp.getUUID(), total);
                            eco.addMoney(current.seller, current.price);
                            shop.removeListing(current.id);
                            ItemStack stack = current.item.copy();
                            if (!sp.getInventory().add(stack)) shop.addDelivery(sp.getUUID(), stack);
                            sp.sendSystemMessage(Component.literal("Item gekocht!").withStyle(ChatFormatting.GREEN));
                        }
                    }
                    ShopUi.open(sp, shop);
                } else if (slot == 6) {
                    ShopUi.open((ServerPlayer) player, shop);
                }
            }
        }
        @Override public boolean stillValid(Player p) { return true; }
        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    }

    // --- REMOVE LISTING MENU ---
    private static class RemoveMenu extends AbstractContainerMenu {
        private final ShopManager shop;
        private final ShopListing listing;
        private final SimpleContainer container = new SimpleContainer(9);

        RemoveMenu(int id, Inventory inv, ShopManager shop, ShopListing listing, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x1, id);
            this.shop = shop;
            this.listing = listing;

            ItemStack confirm = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            confirm.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Terugnemen").withStyle(ChatFormatting.GREEN));
            container.setItem(2, confirm);
            container.setItem(4, listing.item.copy());
            
            ItemStack cancel = new ItemStack(Items.RED_STAINED_GLASS_PANE);
            cancel.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Annuleren").withStyle(ChatFormatting.RED));
            container.setItem(6, cancel);

            for (int i = 0; i < 9; i++) {
                this.addSlot(new Slot(container, i, 8 + i * 18, 20) { @Override public boolean mayPickup(Player p) { return false; }});
            }
        }

        @Override public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP) {
                if (slot == 2) {
                    ShopListing removed = shop.removeListing(listing.id);
                    if (removed != null) {
                        ItemStack stack = removed.item.copy();
                        if (!player.getInventory().add(stack)) shop.addDelivery(player.getUUID(), stack);
                        player.sendSystemMessage(Component.literal("Item verwijderd.").withStyle(ChatFormatting.GREEN));
                    }
                    ShopUi.open((ServerPlayer) player, shop);
                } else if (slot == 6) {
                    ShopUi.open((ServerPlayer) player, shop);
                }
            }
        }
        @Override public boolean stillValid(Player p) { return true; }
        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    }

    private static void sendClaimMessage(ServerPlayer sp) {
        ClickEvent ev = ChatCompat.runCommandEvent("/eco orders claim");
        Component msg = Component.literal("Item opgeslagen: ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal("[Claimen]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withClickEvent(ev)));
        sp.sendSystemMessage(msg);
    }
}

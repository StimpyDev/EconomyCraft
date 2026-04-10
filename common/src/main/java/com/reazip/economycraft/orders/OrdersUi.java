package com.reazip.economycraft.orders;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.IdentityCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OrdersUi {
    private OrdersUi() {}

    private static final ChatFormatting LABEL_PRIMARY_COLOR = ChatFormatting.GOLD;
    private static final ChatFormatting LABEL_SECONDARY_COLOR = ChatFormatting.AQUA;
    private static final ChatFormatting VALUE_COLOR = ChatFormatting.DARK_PURPLE;
    private static final ChatFormatting BALANCE_NAME_COLOR = ChatFormatting.GOLD;
    private static final ChatFormatting BALANCE_VALUE_COLOR = ChatFormatting.GOLD;

public static void open(ServerPlayer player, EconomyManager eco) {
    player.openMenu(new MenuProvider() {
        @Override
        public Component getDisplayName() {
            return Component.literal("ʙᴇꜱᴛᴇʟʟɪɴɢᴇɴ")
                    .withStyle(s -> s.withColor(net.minecraft.network.chat.TextColor.fromRgb(0xe6f51d))
                                     .withItalic(false));
        }

        @Override
        public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
            return new RequestMenu(id, inv, eco.getOrders(), eco, (ServerPlayer) p);
        }
    });
}

    private static Component createRewardLore(long reward, long tax) {
        String value = EconomyCraft.formatMoney(reward);
        if (tax > 0) {
            value += " (-" + EconomyCraft.formatMoney(tax) + " belasting)";
        }
        return labeledValue("Beloning", value, LABEL_PRIMARY_COLOR);
    }

    private static ItemStack createBalanceItem(EconomyManager eco, UUID playerId) {
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        long balance = eco.getBalance(playerId, true);
        
        gold.set(DataComponents.CUSTOM_NAME, Component.literal("Jouw Saldo:").withStyle(s -> s.withItalic(false).withBold(true).withColor(BALANCE_NAME_COLOR)));
        
                gold.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(EconomyCraft.formatMoney(bal)).withStyle(s -> s.withItalic(false).withBold(true).withColor(BALANCE_VALUE_COLOR)))));
        
        return gold;
    }

    private static Component labeledValue(String label, String value, ChatFormatting labelColor) {
        return Component.literal(label + ": ")
                .withStyle(s -> s.withItalic(false).withColor(labelColor))
                .append(Component.literal(value)
                        .withStyle(s -> s.withItalic(false).withColor(VALUE_COLOR)));
    }

    public static void openClaims(ServerPlayer player, EconomyManager eco) {
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() { return Component.literal("Leveringen"); }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ClaimMenu(id, inv, eco, player.getUUID());
            }
        });
    }

    // --- Menu Classes ---

    private static class RequestMenu extends AbstractContainerMenu {
        private final OrderManager orders;
        private final EconomyManager eco;
        private final ServerPlayer viewer;
        private List<OrderRequest> requests = new ArrayList<>();
        private final SimpleContainer container = new SimpleContainer(54);
        private int page = 0;
        private final int navRowStart = 45;
        private final Runnable listener = this::updatePage;

        RequestMenu(int id, Inventory inv, OrderManager orders, EconomyManager eco, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x6, id);
            this.orders = orders;
            this.eco = eco;
            this.viewer = viewer;
            
            orders.addListener(listener);
            
            for (int i = 0; i < 54; i++) {
                this.addSlot(new Slot(container, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                    @Override public boolean mayPickup(Player p) { return false; }
                    @Override public boolean mayPlace(ItemStack s) { return false; }
                });
            }
            addPlayerInventory(inv);
            updatePage();
        }

        private void addPlayerInventory(Inventory inv) {
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) {
                    this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 140 + r * 18));
                }
            }
            for (int c = 0; c < 9; c++) {
                this.addSlot(new Slot(inv, c, 8 + c * 18, 198));
            }
        }

        private void updatePage() {
            requests = new ArrayList<>(orders.getRequests());
            container.clearContent();
            int start = page * 45;
            int totalPages = (int) Math.ceil(requests.size() / 45.0);

            for (int i = 0; i < 45; i++) {
                int index = start + i;
                if (index >= requests.size()) break;

                OrderRequest r = requests.get(index);
                ItemStack display = r.item.copy();
                String reqName = eco.getBestName(r.requester);

                long tax = Math.round(r.price * EconomyConfig.get().taxRate);
                display.set(DataComponents.LORE, new ItemLore(List.of(
                    createRewardLore(r.price, tax),
                    labeledValue("Aantal", String.valueOf(r.amount), LABEL_PRIMARY_COLOR),
                    labeledValue("Aanvrager", reqName, LABEL_SECONDARY_COLOR),
                    Component.empty(),
                    Component.literal(r.requester.equals(viewer.getUUID()) ? "§cKlik om te verwijderen" : "§aKlik om te leveren")
                )));
                display.setCount(1);
                container.setItem(i, display);
            }

            if (page > 0) {
                ItemStack prev = new ItemStack(Items.ARROW);
                prev.set(DataComponents.CUSTOM_NAME, Component.literal("Vorige pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 2, prev);
            }
            if (start + 45 < requests.size()) {
                ItemStack next = new ItemStack(Items.ARROW);
                next.set(DataComponents.CUSTOM_NAME, Component.literal("Volgende pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 6, next);
            }

            container.setItem(navRowStart, createBalanceItem(eco, viewer.getUUID()));
            
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponents.CUSTOM_NAME, Component.literal("Pagina " + (page + 1) + "/" + Math.max(1, totalPages)).withStyle(s -> s.withItalic(false)));
            container.setItem(navRowStart + 4, paper);
        }

        @Override
        public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (slot >= 0 && slot < 54 && type == ClickType.PICKUP) {
                if (slot < 45) {
                    int index = page * 45 + slot;
                    if (index < requests.size()) {
                        OrderRequest req = requests.get(index);
                        if (req.requester.equals(player.getUUID())) {
                            openRemove((ServerPlayer) player, req);
                        } else {
                            openConfirm((ServerPlayer) player, req);
                        }
                    }
                } else if (slot == navRowStart + 2 && page > 0) {
                    page--; updatePage();
                } else if (slot == navRowStart + 6 && (page + 1) * 45 < requests.size()) {
                    page++; updatePage();
                }
                return;
            }
            super.clicked(slot, dragType, type, player);
        }

        private boolean hasItems(ServerPlayer player, ItemStack proto, int amount) {
            int total = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (ItemStack.isSameItemSameComponents(s, proto)) {
                    total += s.getCount();
                }
            }
            return total >= amount;
        }

        private void removeItems(ServerPlayer player, ItemStack proto, int amount) {
            int remaining = amount;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (ItemStack.isSameItemSameComponents(s, proto)) {
                    int take = Math.min(s.getCount(), remaining);
                    s.shrink(take);
                    remaining -= take;
                    if (remaining <= 0) break;
                }
            }
        }

        private void openConfirm(ServerPlayer player, OrderRequest req) {
            player.openMenu(new MenuProvider() {
                @Override public Component getDisplayName() { return Component.literal("Bevestig"); }
                @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                    return new ConfirmMenu(id, inv, req, RequestMenu.this);
                }
            });
        }

        private void openRemove(ServerPlayer player, OrderRequest req) {
            player.openMenu(new MenuProvider() {
                @Override public Component getDisplayName() { return Component.literal("Verwijder"); }
                @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                    return new RemoveMenu(id, inv, req, RequestMenu.this);
                }
            });
        }

        @Override public boolean stillValid(Player p) { return true; }
        @Override public void removed(Player p) { super.removed(p); orders.removeListener(listener); }
        @Override public ItemStack quickMoveStack(Player p, int idx) { return ItemStack.EMPTY; }
    }

    private static class ConfirmMenu extends AbstractContainerMenu {
        private final OrderRequest request;
        private final RequestMenu parent;
        private final SimpleContainer container = new SimpleContainer(9);

        ConfirmMenu(int id, Inventory inv, OrderRequest req, RequestMenu parent) {
            super(MenuType.GENERIC_9x1, id);
            this.request = req;
            this.parent = parent;

            ItemStack confirm = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            confirm.set(DataComponents.CUSTOM_NAME, Component.literal("Bevestig").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            container.setItem(2, confirm);

            ItemStack item = req.item.copy();
            long tax = Math.round(req.price * EconomyConfig.get().taxRate);
            item.set(DataComponents.LORE, new ItemLore(List.of(
                createRewardLore(req.price, tax),
                labeledValue("Aantal", String.valueOf(req.amount), LABEL_PRIMARY_COLOR)
            )));
            container.setItem(4, item);

            ItemStack cancel = new ItemStack(Items.RED_STAINED_GLASS_PANE);
            cancel.set(DataComponents.CUSTOM_NAME, Component.literal("Annuleren").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            container.setItem(6, cancel);

            for (int i = 0; i < 9; i++) {
                this.addSlot(new Slot(container, i, 8 + i * 18, 20) { @Override public boolean mayPickup(Player p) { return false; }});
            }
        }

        @Override
        public void clicked(int slot, int drag, ClickType type, Player player) {
            if (type == ClickType.PICKUP && slot >= 0 && slot < 9) {
                ServerPlayer sp = (ServerPlayer) player;
                if (slot == 2) {
                    OrderRequest current = parent.orders.getRequest(request.id);
                    if (current == null) {
                        sp.sendSystemMessage(Component.literal("Verzoek niet meer beschikbaar.").withStyle(ChatFormatting.RED));
                    } else if (!parent.hasItems(sp, current.item, current.amount)) {
                        sp.sendSystemMessage(Component.literal("Je hebt niet genoeg items.").withStyle(ChatFormatting.RED));
                    } else {
                        long cost = current.price;
                        if (parent.eco.getBalance(current.requester, true) < cost) {
                            sp.sendSystemMessage(Component.literal("Aanvrager heeft onvoldoende saldo.").withStyle(ChatFormatting.RED));
                        } else {
                            parent.removeItems(sp, current.item, current.amount);
                            long tax = Math.round(cost * EconomyConfig.get().taxRate);
                            parent.eco.removeMoney(current.requester, cost);
                            parent.eco.addMoney(sp.getUUID(), cost - tax);
                            parent.orders.removeRequest(current.id);

                            int rem = current.amount;
                            while (rem > 0) {
                                int count = Math.min(current.item.getMaxStackSize(), rem);
                                ItemStack delivery = current.item.copy();
                                delivery.setCount(count);
                                parent.orders.addDelivery(current.requester, delivery);
                                rem -= count;
                            }

                            sp.sendSystemMessage(Component.literal("Verzoek voltooid! Je ontving " + EconomyCraft.formatMoney(cost - tax))
                                    .withStyle(ChatFormatting.GREEN));

                            ServerPlayer requester = sp.level().getServer().getPlayerList().getPlayer(current.requester);
                            if (requester != null) {
                                ClickEvent ev = ChatCompat.runCommandEvent("/eco orders claim");
                                Component msg = Component.literal("Je bestelling (" + current.amount + "x) is geleverd! ")
                                        .withStyle(ChatFormatting.YELLOW)
                                        .append(Component.literal("[Claim hier]")
                                                .withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true).withClickEvent(ev)));
                                requester.sendSystemMessage(msg);
                            }
                        }
                    }
                    OrdersUi.open(sp, parent.eco);
                    return;
                } else if (slot == 6) {
                    OrdersUi.open(sp, parent.eco);
                    return;
                }
            }
            super.clicked(slot, drag, type, player);
        }

        @Override public boolean stillValid(Player p) { return true; }
        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    }

    private static class RemoveMenu extends AbstractContainerMenu {
        private final OrderRequest request;
        private final RequestMenu parent;
        private final SimpleContainer container = new SimpleContainer(9);

        RemoveMenu(int id, Inventory inv, OrderRequest req, RequestMenu parent) {
            super(MenuType.GENERIC_9x1, id);
            this.request = req;
            this.parent = parent;

            ItemStack confirm = new ItemStack(Items.LIME_STAINED_GLASS_PANE);
            confirm.set(DataComponents.CUSTOM_NAME, Component.literal("Verwijderen").withStyle(ChatFormatting.GREEN));
            container.setItem(2, confirm);
            container.setItem(4, req.item.copy());
            ItemStack cancel = new ItemStack(Items.RED_STAINED_GLASS_PANE);
            cancel.set(DataComponents.CUSTOM_NAME, Component.literal("Annuleren").withStyle(ChatFormatting.RED));
            container.setItem(6, cancel);

            for (int i = 0; i < 9; i++) {
                this.addSlot(new Slot(container, i, 8 + i * 18, 20) { @Override public boolean mayPickup(Player p) { return false; }});
            }
        }

        @Override
        public void clicked(int slot, int drag, ClickType type, Player player) {
            if (type == ClickType.PICKUP && slot >= 0 && slot < 9) {
                if (slot == 2) {
                    parent.orders.removeRequest(request.id);
                    ((ServerPlayer)player).sendSystemMessage(Component.literal("Verzoek verwijderd.").withStyle(ChatFormatting.GREEN));
                }
                OrdersUi.open((ServerPlayer) player, parent.eco);
                return;
            }
            super.clicked(slot, drag, type, player);
        }

        @Override public boolean stillValid(Player p) { return true; }
        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    }

    private static class ClaimMenu extends AbstractContainerMenu {
        private final EconomyManager eco;
        private final UUID owner;
        private final List<ItemStack> items = new ArrayList<>();
        private final SimpleContainer container = new SimpleContainer(54);
        private int page = 0;
        private final int navRowStart = 45;

        ClaimMenu(int id, Inventory inv, EconomyManager eco, UUID owner) {
            super(MenuType.GENERIC_9x6, id);
            this.eco = eco;
            this.owner = owner;
            
            for (int i = 0; i < 54; i++) {
                int idx = i;
                this.addSlot(new Slot(container, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                    @Override public boolean mayPlace(ItemStack s) { return false; }
                    @Override public boolean mayPickup(Player p) { return idx < 45; }
                });
            }
            addPlayerInventory(inv);
            updatePage();
        }

        private void addPlayerInventory(Inventory inv) {
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) {
                    this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 140 + r * 18));
                }
            }
            for (int c = 0; c < 9; c++) {
                this.addSlot(new Slot(inv, c, 8 + c * 18, 198));
            }
        }

        private void updatePage() {
            items.clear();
            items.addAll(eco.getOrders().getDeliveries(owner));
            items.addAll(eco.getShop().getDeliveries(owner));
            container.clearContent();
            
            int start = page * 45;
            for (int i = 0; i < 45; i++) {
                int index = start + i;
                if (index < items.size()) {
                    container.setItem(i, items.get(index));
                }
            }

            if (page > 0) {
                ItemStack prev = new ItemStack(Items.ARROW);
                prev.set(DataComponents.CUSTOM_NAME, Component.literal("Vorige pagina"));
                container.setItem(navRowStart + 2, prev);
            }
            if (start + 45 < items.size()) {
                ItemStack next = new ItemStack(Items.ARROW);
                next.set(DataComponents.CUSTOM_NAME, Component.literal("Volgende pagina"));
                container.setItem(navRowStart + 6, next);
            }
            container.setItem(navRowStart, createBalanceItem(eco, owner));
        }

        @Override
        public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP && slot >= 0 && slot < 54) {
                if (slot < 45) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        if (player.getInventory().add(stack.copy())) {
                            eco.getOrders().removeDelivery(owner, stack);
                            eco.getShop().removeDelivery(owner, stack);
                            updatePage();
                        } else {
                            ((ServerPlayer) player).sendSystemMessage(Component.literal("Je inventaris zit vol!").withStyle(ChatFormatting.RED));
                        }
                    }
                    return;
                } else if (slot == navRowStart + 2 && page > 0) {
                    page--; updatePage(); return;
                } else if (slot == navRowStart + 6 && (page + 1) * 45 < items.size()) {
                    page++; updatePage(); return;
                }
            }
            super.clicked(slot, dragType, type, player);
        }

        @Override public boolean stillValid(Player p) { return true; }
        
        @Override
        public ItemStack quickMoveStack(Player player, int idx) {
            if (idx < 45) {
                ItemStack stack = container.getItem(idx);
                if (!stack.isEmpty()) {
                    if (player.getInventory().add(stack.copy())) {
                        eco.getOrders().removeDelivery(owner, stack);
                        eco.getShop().removeDelivery(owner, stack);
                        updatePage();
                    } else {
                        ((ServerPlayer) player).sendSystemMessage(Component.literal("Je inventaris zit vol!").withStyle(ChatFormatting.RED));
                    }
                }
            }
            return ItemStack.EMPTY;
        }
    }
}

package com.reazip.economycraft.shop;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.IdentityCompat;
import com.reazip.economycraft.util.IdentifierCompat;
import com.reazip.economycraft.util.ProfileComponentCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class ServerShopUi {
    private static final Component STORED_MSG = Component.literal("Item stored: ").withStyle(ChatFormatting.YELLOW);
    private static final Map<String, IdentifierCompat.Id> CATEGORY_ICONS = buildCategoryIcons();
    private static final List<Integer> STAR_SLOT_ORDER = buildStarSlotOrder(5);
    private static final Map<UUID, Instant> KIT_COOLDOWNS = new HashMap<>();
    private static final long COOLDOWN_MILLIS = Duration.ofHours(1).toMillis();

    private static final ChatFormatting LABEL_PRIMARY_COLOR = ChatFormatting.GOLD;
    private static final ChatFormatting LABEL_SECONDARY_COLOR = ChatFormatting.AQUA;
    private static final ChatFormatting VALUE_COLOR = ChatFormatting.DARK_PURPLE;
    private static final ChatFormatting BALANCE_NAME_COLOR = ChatFormatting.YELLOW;
    private static final ChatFormatting BALANCE_LABEL_COLOR = ChatFormatting.GOLD;
    private static final ChatFormatting BALANCE_VALUE_COLOR = ChatFormatting.DARK_PURPLE;

    private ServerShopUi() {}

    public static void open(ServerPlayer player, EconomyManager eco) {
        open(player, eco, null);
    }

    public static void open(ServerPlayer player, EconomyManager eco, @Nullable String category) {
        if (category == null || category.isBlank()) {
            openRoot(player, eco);
            return;
        }
        PriceRegistry prices = eco.getPrices();
        String cat = category.trim();
        if (cat.contains(".")) {
            openItems(player, eco, cat);
            return;
        }
        List<String> subs = prices.buySubcategories(cat);
        if (!subs.isEmpty()) {
            openSubcategories(player, eco, cat);
            return;
        }
        openItems(player, eco, cat);
    }

    private static void openRoot(ServerPlayer player, EconomyManager eco) {
        Component title = Component.literal("ᴅɪᴇɢᴏᴛᴊᴜʜ ꜱʜᴏᴘ")
                .withStyle(s -> s.withColor(net.minecraft.network.chat.TextColor.fromRgb(0x81F571)).withItalic(false));
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return title; }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new CategoryMenu(id, inv, eco, player);
            }
        });
    }

    private static void openSubcategories(ServerPlayer player, EconomyManager eco, String topCategory) {
        Component title = Component.literal(formatCategoryTitle(topCategory));
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return title; }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new SubcategoryMenu(id, inv, eco, topCategory, player);
            }
        });
    }

    private static void openItems(ServerPlayer player, EconomyManager eco, String category) {
        openItems(player, eco, category, null);
    }

    private static void openItems(ServerPlayer player, EconomyManager eco, String category, @Nullable String displayTitle) {
        Component title = Component.literal(formatCategoryTitle(displayTitle != null ? displayTitle : category));
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return title; }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ItemMenu(id, inv, eco, category, player);
            }
        });
    }

    private static String formatCategoryTitle(String category) {
        if (category == null || category.isBlank()) return "DIEGOTJUH SHOP";
        String[] parts = category.replace('.', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() == 0 ? category : sb.toString();
    }

    private static class CategoryMenu extends AbstractContainerMenu {
        private final EconomyManager eco;
        private final PriceRegistry prices;
        private final ServerPlayer viewer;
        private List<String> categories = new ArrayList<>();
        private final SimpleContainer container;
        private final int itemsPerPage = 45;
        private final int navRowStart = 45;
        private final int[] slotToIndex = new int[54];
        private int page;

        CategoryMenu(int id, Inventory inv, EconomyManager eco, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x6, id);
            this.eco = eco;
            this.viewer = viewer;
            this.prices = eco.getPrices();
            refreshCategories();
            this.container = new SimpleContainer(54);
            setupSlots(inv);
            updatePage();
        }

        private void refreshCategories() {
            categories = new ArrayList<>();
            for (String cat : prices.buyTopCategories()) {
                if (hasItems(prices, cat)) categories.add(cat);
            }
        }

        private void setupSlots(Inventory inv) {
            for (int i = 0; i < 54; i++) {
                int r = i / 9;
                int c = i % 9;
                this.addSlot(new Slot(container, i, 8 + c * 18, 18 + r * 18) {
                    @Override public boolean mayPickup(Player player) { return false; }
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                });
            }
            int y = 18 + 6 * 18 + 14;
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
            container.clearContent();
            Arrays.fill(slotToIndex, -1);
            int start = page * itemsPerPage;
            int totalPages = (int) Math.ceil(categories.size() / (double) itemsPerPage);
            for (int i = 0; i < itemsPerPage; i++) {
                int idx = start + i;
                if (idx >= categories.size()) break;
                String cat = categories.get(idx);
                ItemStack icon = createCategoryIcon(cat, cat, prices, viewer);
                if (icon.isEmpty()) continue;
                icon.set(DataComponents.CUSTOM_NAME, Component.literal(formatCategoryTitle(cat)).withStyle(s -> s.withItalic(false).withColor(getCategoryColor(cat)).withBold(true)));
                icon.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Klik om artikelen te bekijken").withStyle(s -> s.withItalic(false)))));
                int slot = STAR_SLOT_ORDER.get(i);
                container.setItem(slot, icon);
                slotToIndex[slot] = idx;
            }
            fillEmptyWithPanes(container, itemsPerPage);
            if (page > 0) {
                ItemStack prev = new ItemStack(Items.ARROW);
                prev.set(DataComponents.CUSTOM_NAME, Component.literal("Vorige pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 3, prev);
            }
            if (start + itemsPerPage < categories.size()) {
                ItemStack next = new ItemStack(Items.ARROW);
                next.set(DataComponents.CUSTOM_NAME, Component.literal("Volgende pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 5, next);
            }
            container.setItem(navRowStart, createBalanceItem(viewer));
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponents.CUSTOM_NAME, Component.literal("Pagina " + (page + 1) + "/" + Math.max(1, totalPages)).withStyle(s -> s.withItalic(false)));
            container.setItem(navRowStart + 4, paper);
        }

        @Override
        public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) {
                if (slot < navRowStart) {
                    int index = slotToIndex[slot];
                    if (index >= 0 && index < categories.size()) {
                        String cat = categories.get(index);
                        if (prices.buySubcategories(cat).isEmpty()) openItems(viewer, eco, cat);
                        else openSubcategories(viewer, eco, cat);
                        return;
                    }
                }
                if (slot == navRowStart + 3 && page > 0) { page--; updatePage(); return; }
                if (slot == navRowStart + 5 && (page + 1) * itemsPerPage < categories.size()) { page++; updatePage(); return; }
            }
            super.clicked(slot, dragType, type, player);
        }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    private static class SubcategoryMenu extends AbstractContainerMenu {
        private final EconomyManager eco;
        private final PriceRegistry prices;
        private final ServerPlayer viewer;
        private final String topCategory;
        private List<String> subcategories = new ArrayList<>();
        private final SimpleContainer container;
        private final int rows, itemsPerPage, navRowStart;
        private int page;

        SubcategoryMenu(int id, Inventory inv, EconomyManager eco, String topCategory, ServerPlayer viewer) {
            super(getMenuType(requiredRows(eco.getPrices().buySubcategories(topCategory).size())), id);
            this.eco = eco; this.viewer = viewer; this.topCategory = topCategory; this.prices = eco.getPrices();
            refresh();
            this.rows = requiredRows(subcategories.size());
            this.itemsPerPage = (rows - 1) * 9;
            this.navRowStart = itemsPerPage;
            this.container = new SimpleContainer(rows * 9);
            setupSlots(inv);
            updatePage();
        }

        private void refresh() {
            subcategories = new ArrayList<>();
            for (String sub : prices.buySubcategories(topCategory)) {
                if (hasItems(prices, topCategory + "." + sub)) subcategories.add(sub);
            }
        }

        private void setupSlots(Inventory inv) {
            for (int i = 0; i < rows * 9; i++) {
                int r = i / 9, c = i % 9;
                this.addSlot(new Slot(container, i, 8 + c * 18, 18 + r * 18) {
                    @Override public boolean mayPickup(Player player) { return false; }
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                });
            }
            int y = 18 + rows * 18 + 14;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
            }
            for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c, 8 + c * 18, y + 58));
        }

        private void updatePage() {
            container.clearContent();
            int start = page * itemsPerPage;
            int totalPages = (int) Math.ceil(subcategories.size() / (double) itemsPerPage);
            for (int i = 0; i < itemsPerPage; i++) {
                int idx = start + i;
                if (idx >= subcategories.size()) break;
                String sub = subcategories.get(idx);
                ItemStack icon = createCategoryIcon(sub, topCategory + "." + sub, prices, viewer);
                if (icon.isEmpty()) continue;
                icon.set(DataComponents.CUSTOM_NAME, Component.literal(formatCategoryTitle(sub)).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE).withBold(true)));
                icon.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Klik om artikelen te bekijken").withStyle(s -> s.withItalic(false)))));
                container.setItem(i, icon);
            }
            if (page > 0) {
                ItemStack prev = new ItemStack(Items.ARROW);
                prev.set(DataComponents.CUSTOM_NAME, Component.literal("Vorige pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 3, prev);
            }
            if (start + itemsPerPage < subcategories.size()) {
                ItemStack next = new ItemStack(Items.ARROW);
                next.set(DataComponents.CUSTOM_NAME, Component.literal("Volgende pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 5, next);
            }
            ItemStack back = new ItemStack(Items.BARRIER);
            back.set(DataComponents.CUSTOM_NAME, Component.literal("Terug").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_RED).withBold(true)));
            container.setItem(navRowStart + 8, back);
            container.setItem(navRowStart, createBalanceItem(viewer));
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponents.CUSTOM_NAME, Component.literal("Pagina " + (page + 1) + "/" + Math.max(1, totalPages)).withStyle(s -> s.withItalic(false)));
            container.setItem(navRowStart + 4, paper);
        }

        @Override
        public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) {
                if (slot < navRowStart) {
                    int index = page * itemsPerPage + slot;
                    if (index < subcategories.size()) { openItems(viewer, eco, topCategory + "." + subcategories.get(index), subcategories.get(index)); return; }
                }
                if (slot == navRowStart + 3 && page > 0) { page--; updatePage(); return; }
                if (slot == navRowStart + 5 && (page + 1) * itemsPerPage < subcategories.size()) { page++; updatePage(); return; }
                if (slot == navRowStart + 8) { openRoot(viewer, eco); return; }
            }
            super.clicked(slot, dragType, type, player);
        }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    private static class ItemMenu extends AbstractContainerMenu {
        private final EconomyManager eco;
        private final PriceRegistry prices;
        private final ServerPlayer viewer;
        private final String category;
        private List<PriceRegistry.PriceEntry> entries = new ArrayList<>();
        private final SimpleContainer container;
        private final int rows, itemsPerPage, navRowStart;
        private int page;

        ItemMenu(int id, Inventory inv, EconomyManager eco, String category, ServerPlayer viewer) {
            super(getMenuType(requiredRows(eco.getPrices().buyableByCategory(category).size())), id);
            this.eco = eco; this.viewer = viewer; this.category = category; this.prices = eco.getPrices();
            refreshEntries();
            this.rows = requiredRows(entries.size());
            this.itemsPerPage = (rows - 1) * 9;
            this.navRowStart = itemsPerPage;
            this.container = new SimpleContainer(rows * 9);
            setupSlots(inv);
            updatePage();
        }

        private void refreshEntries() { entries = new ArrayList<>(prices.buyableByCategory(category)); }

        private void setupSlots(Inventory inv) {
            for (int i = 0; i < rows * 9; i++) {
                int r = i / 9, c = i % 9;
                this.addSlot(new Slot(container, i, 8 + c * 18, 18 + r * 18) {
                    @Override public boolean mayPickup(Player player) { return false; }
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                });
            }
            int y = 18 + rows * 18 + 14;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
            }
            for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c, 8 + c * 18, y + 58));
        }

private void updatePage() {
            container.clearContent();
            int start = page * itemsPerPage;
            int totalPages = (int) Math.ceil(entries.size() / (double) itemsPerPage);
            for (int i = 0; i < itemsPerPage; i++) {
                int idx = start + i;
                if (idx >= entries.size()) break;
                PriceRegistry.PriceEntry entry = entries.get(idx);
                ItemStack display = createDisplayStack(entry, viewer);
                if (display.isEmpty()) continue;
                
                int stackSize = Math.max(1, entry.stack());
                List<Component> lore = new ArrayList<>();
                
                if (category.equalsIgnoreCase("kits")) {
                    lore.add(Component.literal("Inhoud:").withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(false)));
                    lore.add(Component.literal(" - Full Netherite Armor (Maxed)").withStyle(ChatFormatting.DARK_AQUA).withStyle(s -> s.withItalic(false)));
                    lore.add(Component.literal(" - Netherite Sword & Axe (Maxed)").withStyle(ChatFormatting.DARK_AQUA).withStyle(s -> s.withItalic(false)));
                    lore.add(Component.literal(" - Netherite Spear").withStyle(ChatFormatting.GOLD).withStyle(s -> s.withItalic(false)));
                    lore.add(Component.literal(""));
                }

                lore.add(labeledValue("Koop", EconomyCraft.formatMoney(entry.unitBuy()), LABEL_PRIMARY_COLOR));
                
                Long stackPrice = safeMultiply(entry.unitBuy(), stackSize);
                if (stackSize > 1 && stackPrice != null) {
                    lore.add(labeledValue("Stack (" + stackSize + ")", EconomyCraft.formatMoney(stackPrice), LABEL_PRIMARY_COLOR));
                }
                
                lore.add(labeledValue("Linker muis", "Koop 1", LABEL_SECONDARY_COLOR));
                if (stackSize > 1) {
                    lore.add(labeledValue("Shift-klik", "Koop " + stackSize, LABEL_SECONDARY_COLOR));
                }

                display.set(DataComponents.LORE, new ItemLore(lore));
                display.setCount(1);
                container.setItem(i, display);
            }

            if (page > 0) {
                ItemStack prev = new ItemStack(Items.ARROW);
                prev.set(DataComponents.CUSTOM_NAME, Component.literal("Vorige pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 3, prev);
            }
            if (start + itemsPerPage < entries.size()) {
                ItemStack next = new ItemStack(Items.ARROW);
                next.set(DataComponents.CUSTOM_NAME, Component.literal("Volgende pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 5, next);
            }
            
            ItemStack back = new ItemStack(Items.BARRIER);
            back.set(DataComponents.CUSTOM_NAME, Component.literal("Terug").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_RED).withBold(true)));
            container.setItem(navRowStart + 8, back);
            
            container.setItem(navRowStart, createBalanceItem(viewer));
            
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponents.CUSTOM_NAME, Component.literal("Pagina " + (page + 1) + "/" + Math.max(1, totalPages)).withStyle(s -> s.withItalic(false)));
            container.setItem(navRowStart + 4, paper);
        }

        @Override
        public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (slot < 0 || slot >= navRowStart + 9) { super.clicked(slot, dragType, type, player); return; }
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) {
                if (slot < navRowStart) {
                    int index = (page * itemsPerPage) + slot;
                    if (index >= 0 && index < entries.size()) handlePurchase(entries.get(index), type);
                    return;
                }
                if (slot == navRowStart + 3 && page > 0) { page--; updatePage(); return; }
                if (slot == navRowStart + 5 && (page + 1) * itemsPerPage < entries.size()) { page++; updatePage(); return; }
                if (slot == navRowStart + 8) {
                    if (category.contains(".")) openSubcategories(viewer, eco, category.substring(0, category.indexOf('.')));
                    else openRoot(viewer, eco);
                    return;
                }
            }
            super.clicked(slot, dragType, type, player);
        }

        private void handlePurchase(PriceRegistry.PriceEntry entry, ClickType clickType) {
            if (category.equalsIgnoreCase("kits")) {
                Instant lastBuy = KIT_COOLDOWNS.get(viewer.getUUID());
                if (lastBuy != null && Instant.now().isBefore(lastBuy.plusMillis(COOLDOWN_MILLIS))) {
                    long remaining = Duration.between(Instant.now(), lastBuy.plusMillis(COOLDOWN_MILLIS)).toMinutes();
                    viewer.sendSystemMessage(Component.literal("Wacht nog " + remaining + " minuten voordat je weer een kit koopt!").withStyle(ChatFormatting.RED));
                    return;
                }
            }

            if (entry.unitBuy() <= 0) { viewer.sendSystemMessage(Component.literal("Dit artikel kan niet worden gekocht.").withStyle(ChatFormatting.RED)); return; }
            ItemStack base = createDisplayStack(entry, viewer);
            if (base.isEmpty()) { viewer.sendSystemMessage(Component.literal("Artikel niet beschikbaar.").withStyle(ChatFormatting.RED)); return; }

            int amount = clickType == ClickType.QUICK_MOVE ? Math.max(1, entry.stack()) : 1;
            Long total = safeMultiply(entry.unitBuy(), amount);
            if (total == null || eco.getBalance(viewer.getUUID(), true) < total) {
                sendPrivateSound(viewer, SoundEvents.VILLAGER_NO);
                viewer.sendSystemMessage(Component.literal("Transactie mislukt of onvoldoende saldo.").withStyle(ChatFormatting.RED));
                return;
            }

            if (eco.removeMoney(viewer.getUUID(), total)) {
                if (category.equalsIgnoreCase("kits")) {
                    giveKitItems(viewer, viewer.registryAccess());
                    KIT_COOLDOWNS.put(viewer.getUUID(), Instant.now());
                } else {
                    giveToPlayer(viewer, base, amount);
                }
                sendPrivateSound(viewer, SoundEvents.EXPERIENCE_ORB_PICKUP);
                viewer.sendSystemMessage(Component.literal("Gekocht voor ").withStyle(ChatFormatting.GREEN).append(Component.literal(EconomyCraft.formatMoney(total)).withStyle(ChatFormatting.GOLD)));
                updatePage();
            }
        }

        private void giveKitItems(ServerPlayer player, HolderLookup.Provider p) {
            Inventory inv = player.getInventory();
            inv.add(createEnchanted(Items.NETHERITE_HELMET, p, Map.of(Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3, Enchantments.AQUA_AFFINITY, 1, Enchantments.RESPIRATION, 3, Enchantments.MENDING, 1)));
            inv.add(createEnchanted(Items.NETHERITE_CHESTPLATE, p, Map.of(Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1)));
            inv.add(createEnchanted(Items.NETHERITE_LEGGINGS, p, Map.of(Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1, Enchantments.SWIFT_SNEAK, 3)));
            inv.add(createEnchanted(Items.NETHERITE_BOOTS, p, Map.of(Enchantments.FEATHER_FALLING, 4, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1, Enchantments.DEPTH_STRIDER, 3, Enchantments.PROTECTION, 4)));
            inv.add(createEnchanted(Items.NETHERITE_SWORD, p, Map.of(Enchantments.SHARPNESS, 5, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1, Enchantments.FIRE_ASPECT, 2, Enchantments.SWEEPING_EDGE, 3, Enchantments.LOOTING, 3)));
            inv.add(createEnchanted(Items.NETHERITE_AXE, p, Map.of(Enchantments.UNBREAKING, 3, Enchantments.SHARPNESS, 5, Enchantments.MENDING, 1, Enchantments.SILK_TOUCH, 1)));
            
            ItemStack spear = createEnchanted(Items.NETHERITE_SPEAR, p, Map.of(Enchantments.LUNGE, 3, Enchantments.SHARPNESS, 5, Enchantments.MENDING, 1, Enchantments.UNBREAKING, 3, Enchantments.KNOCKBACK, 2));
            inv.add(spear);
        }

        private ItemStack createEnchanted(Item item, HolderLookup.Provider p, Map<ResourceKey<Enchantment>, Integer> enchants) {
            ItemStack stack = new ItemStack(item);
            var lookup = p.lookupOrThrow(Registries.ENCHANTMENT);
            ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            enchants.forEach((key, level) -> lookup.get(key).ifPresent(holder -> mutable.set(holder, level)));
            stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
            return stack;
        }

        private void giveToPlayer(ServerPlayer player, ItemStack base, int amount) {
            int remaining = amount;
            while (remaining > 0) {
                int give = Math.min(base.getMaxStackSize(), remaining);
                ItemStack stack = base.copyWithCount(give);
                if (!player.getInventory().add(stack)) eco.getShop().addDelivery(player.getUUID(), stack);
                remaining -= give;
            }
        }

        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    private static void sendPrivateSound(ServerPlayer viewer, net.minecraft.sounds.SoundEvent sound) {
        viewer.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.PLAYERS, viewer.getX(), viewer.getY(), viewer.getZ(), 1.0f, 1.0f, viewer.getRandom().nextLong()));
    }

    private static ItemStack createCategoryIcon(String displayKey, String categoryKey, PriceRegistry prices, ServerPlayer viewer) {
        IdentifierCompat.Id iconId = CATEGORY_ICONS.get(normalizeCategoryKey(displayKey));
        if (iconId == null && categoryKey != null) iconId = CATEGORY_ICONS.get(normalizeCategoryKey(categoryKey));
        if (iconId != null) {
            Optional<?> item = IdentifierCompat.registryGetOptional(BuiltInRegistries.ITEM, iconId);
            if (item.isPresent()) {
                Item resolved = resolveItemValue(item.get(), iconId, "category icon");
                if (resolved != null) return new ItemStack(resolved);
            }
        }
        List<PriceRegistry.PriceEntry> entries = prices.buyableByCategory(categoryKey);
        if (!entries.isEmpty()) {
            ItemStack display = createDisplayStack(entries.get(0), viewer);
            if (!display.isEmpty()) return display;
        }
        return new ItemStack(Items.BOOK);
    }

    private static Map<String, IdentifierCompat.Id> buildCategoryIcons() {
        Map<String, IdentifierCompat.Id> map = new HashMap<>();
        map.put(normalizeCategoryKey("Kits"), IdentifierCompat.withDefaultNamespace("gray_wool"));
        map.put(normalizeCategoryKey("Redstone"), IdentifierCompat.withDefaultNamespace("redstone"));
        map.put(normalizeCategoryKey("Eten"), IdentifierCompat.withDefaultNamespace("cooked_beef"));
        map.put(normalizeCategoryKey("Ores"), IdentifierCompat.withDefaultNamespace("iron_ingot"));
        map.put(normalizeCategoryKey("Blokken"), IdentifierCompat.withDefaultNamespace("grass_block"));
        map.put(normalizeCategoryKey("Stenen"), IdentifierCompat.withDefaultNamespace("cobblestone"));
        map.put(normalizeCategoryKey("Bakstenen"), IdentifierCompat.withDefaultNamespace("bricks"));
        map.put(normalizeCategoryKey("Koper"), IdentifierCompat.withDefaultNamespace("copper_block"));
        map.put(normalizeCategoryKey("Aarde"), IdentifierCompat.withDefaultNamespace("dirt"));
        map.put(normalizeCategoryKey("Zand"), IdentifierCompat.withDefaultNamespace("sand"));
        map.put(normalizeCategoryKey("Hout"), IdentifierCompat.withDefaultNamespace("oak_log"));
        map.put(normalizeCategoryKey("Drops"), IdentifierCompat.withDefaultNamespace("gunpowder"));
        map.put(normalizeCategoryKey("Hulpmiddelen"), IdentifierCompat.withDefaultNamespace("totem_of_undying"));
        map.put(normalizeCategoryKey("Vervoer"), IdentifierCompat.withDefaultNamespace("saddle"));
        map.put(normalizeCategoryKey("Licht"), IdentifierCompat.withDefaultNamespace("lantern"));
        map.put(normalizeCategoryKey("Planten"), IdentifierCompat.withDefaultNamespace("wheat"));
        map.put(normalizeCategoryKey("Gereedschap"), IdentifierCompat.withDefaultNamespace("diamond_pickaxe"));
        map.put(normalizeCategoryKey("Wapens"), IdentifierCompat.withDefaultNamespace("diamond_sword"));
        map.put(normalizeCategoryKey("Armor"), IdentifierCompat.withDefaultNamespace("diamond_chestplate"));
        map.put(normalizeCategoryKey("Enchantments"), IdentifierCompat.withDefaultNamespace("enchanted_book"));
        map.put(normalizeCategoryKey("Brouwen"), IdentifierCompat.withDefaultNamespace("water_bottle"));
        map.put(normalizeCategoryKey("Oceaan"), IdentifierCompat.withDefaultNamespace("tube_coral"));
        map.put(normalizeCategoryKey("Nether"), IdentifierCompat.withDefaultNamespace("netherrack"));
        map.put(normalizeCategoryKey("End"), IdentifierCompat.withDefaultNamespace("end_stone"));
        map.put(normalizeCategoryKey("Diepe duisternis"), IdentifierCompat.withDefaultNamespace("sculk"));
        map.put(normalizeCategoryKey("Archeologie"), IdentifierCompat.withDefaultNamespace("brush"));
        map.put(normalizeCategoryKey("Ijs"), IdentifierCompat.withDefaultNamespace("ice"));
        map.put(normalizeCategoryKey("Geverfd"), IdentifierCompat.withDefaultNamespace("blue_dye"));
        map.put(normalizeCategoryKey("Platen"), IdentifierCompat.withDefaultNamespace("music_disc_strad"));
        return map;
    }

    private static String normalizeCategoryKey(String key) {
        return key == null ? "" : key.replace('.', ' ').replace('-', ' ').replace('_', ' ').trim().toLowerCase(Locale.ROOT);
    }

    private static List<Integer> buildStarSlotOrder(int height) {
        int width = 9;
        int centerX = (width - 1) / 2, centerY = (height - 1) / 2;
        List<int[]> entries = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (y == 0) { entries.add(new int[]{idx, 9999, y, x}); continue; }
                double dx = x - centerX, dy = y - centerY;
                int finalDist = (int) (Math.sqrt(dx * dx + dy * dy) * 1000);
                if (idx == 23) finalDist = -2; if (idx == 24) finalDist = -1;
                entries.add(new int[]{idx, finalDist, y, x});
            }
        }
        entries.sort(Comparator.comparingInt((int[] a) -> a[1]).thenComparingInt(a -> a[2]).thenComparingInt(a -> a[3]));
        List<Integer> order = new ArrayList<>();
        for (int[] e : entries) order.add(e[0]);
        return order;
    }

    private static ChatFormatting getCategoryColor(String key) {
        return switch (normalizeCategoryKey(key)) {
            case "kits" -> ChatFormatting.DARK_GRAY;
            case "redstone" -> ChatFormatting.RED;
            case "eten" -> ChatFormatting.GOLD;
            case "ores" -> ChatFormatting.WHITE;
            case "blokken" -> ChatFormatting.DARK_GREEN;
            case "stenen" -> ChatFormatting.GRAY;
            case "bakstenen" -> ChatFormatting.DARK_GRAY;
            case "koper" -> ChatFormatting.AQUA;
            case "aarde" -> ChatFormatting.GREEN;
            case "zand" -> ChatFormatting.YELLOW;
            case "hout" -> ChatFormatting.DARK_GREEN;
            case "drops" -> ChatFormatting.GRAY;
            case "hulpmiddelen" -> ChatFormatting.LIGHT_PURPLE;
            case "vervoer" -> ChatFormatting.BLUE;
            case "licht" -> ChatFormatting.YELLOW;
            case "planten" -> ChatFormatting.GREEN;
            case "gereedschap" -> ChatFormatting.AQUA;
            case "wapens" -> ChatFormatting.RED;
            case "armor" -> ChatFormatting.BLUE;
            case "enchantments" -> ChatFormatting.LIGHT_PURPLE;
            case "brouwen", "oceaan" -> ChatFormatting.DARK_AQUA;
            case "nether" -> ChatFormatting.RED;
            case "end", "enchanted" -> ChatFormatting.LIGHT_PURPLE;
            case "diepe duisternis" -> ChatFormatting.DARK_BLUE;
            case "archeologie" -> ChatFormatting.GOLD;
            case "ijs" -> ChatFormatting.AQUA;
            case "geverfd" -> ChatFormatting.BLUE;
            case "platen" -> ChatFormatting.DARK_PURPLE;
            default -> ChatFormatting.WHITE;
        };
    }

    private static boolean hasItems(PriceRegistry prices, String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) return false;
        if (!prices.buyableByCategory(categoryKey).isEmpty()) return true;
        if (!categoryKey.contains(".")) {
            for (String sub : prices.buySubcategories(categoryKey)) {
                if (!prices.buyableByCategory(categoryKey + "." + sub).isEmpty()) return true;
            }
        }
        return false;
    }

    private static void fillEmptyWithPanes(SimpleContainer container, int limit) {
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < limit && i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) container.setItem(i, filler.copy());
        }
    }

    private static MenuType<?> getMenuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1; case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3; case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5; default -> MenuType.GENERIC_9x6;
        };
    }

    private static int requiredRows(int itemCount) {
        return Math.min(6, Math.max(2, (int) Math.ceil(Math.max(1, itemCount) / 9.0) + 1));
    }

    private static Component labeledValue(String label, String value, ChatFormatting labelColor) {
        return Component.literal(label + ": ").withStyle(s -> s.withItalic(false).withColor(labelColor))
                .append(Component.literal(value).withStyle(s -> s.withItalic(false).withColor(VALUE_COLOR)));
    }

    private static ItemStack createBalanceItem(ServerPlayer player) {
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        long balance = EconomyCraft.getManager(((ServerLevel) player.level()).getServer()).getBalance(player.getUUID(), true);
        gold.set(DataComponents.CUSTOM_NAME, Component.literal("Saldo").withStyle(s -> s.withItalic(true).withColor(BALANCE_NAME_COLOR)));
        gold.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(EconomyCraft.formatMoney(balance)).withStyle(s -> s.withItalic(true).withColor(BALANCE_VALUE_COLOR)))));
        return gold;
    }

    private static ItemStack createDisplayStack(PriceRegistry.PriceEntry entry, ServerPlayer viewer) {
        try {
            IdentifierCompat.Id id = entry.id();
            Optional<?> item = IdentifierCompat.registryGetOptional(BuiltInRegistries.ITEM, id);
            if (item.isPresent()) {
                Item resolved = resolveItemValue(item.get(), id, "display stack");
                return (resolved != null && resolved != Items.AIR) ? new ItemStack(resolved) : ItemStack.EMPTY;
            }
            if (id.path().startsWith("enchanted_book_")) return createEnchantedBookStack(id, viewer);
            return createPotionStack(id);
        } catch (RuntimeException ex) {
            LogUtils.getLogger().error("[EconomyCraft] Failed to create display stack for {}", entry.id().asString(), ex);
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack createEnchantedBookStack(IdentifierCompat.Id key, ServerPlayer viewer) {
        String path = key.path();
        String suffix = path.substring("enchanted_book_".length());
        int lastUnderscore = suffix.lastIndexOf('_');
        if (lastUnderscore <= 0) return ItemStack.EMPTY;
        int level = Integer.parseInt(suffix.substring(lastUnderscore + 1));
        String enchantPath = suffix.substring(0, lastUnderscore);
        if (enchantPath.equals("curse_of_binding")) enchantPath = "binding_curse";
        else if (enchantPath.equals("curse_of_vanishing")) enchantPath = "vanishing_curse";
        ResourceKey<Enchantment> rk = IdentifierCompat.createResourceKey(Registries.ENCHANTMENT, IdentifierCompat.fromNamespaceAndPath(key.namespace(), enchantPath));
        Optional<Holder.Reference<Enchantment>> holder = viewer.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(rk);
        if (holder.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(holder.get(), level);
        stack.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
        return stack;
    }

    private static ItemStack createPotionStack(IdentifierCompat.Id key) {
        String path = key.path();
        Item baseItem = Items.POTION;
        String working = path;
        if (path.startsWith("splash_")) { baseItem = Items.SPLASH_POTION; working = path.substring(7); }
        else if (path.startsWith("lingering_")) { baseItem = Items.LINGERING_POTION; working = path.substring(10); }
        else if (path.startsWith("arrow_of_")) { baseItem = Items.TIPPED_ARROW; working = path.substring(9); }
        
        String potionPath = working.replace("_potion", "").replace("_splash", "").replace("_lingering", "");
        if (potionPath.endsWith("_extended")) potionPath = "long_" + potionPath.replace("_extended", "");
        else if (potionPath.endsWith("_2")) potionPath = "strong_" + potionPath.replace("_2", "");

        Optional<?> potion = IdentifierCompat.registryGetOptional(BuiltInRegistries.POTION, IdentifierCompat.fromNamespaceAndPath(key.namespace(), potionPath));
        if (potion.isEmpty()) return ItemStack.EMPTY;
        return PotionContents.createItemStack(baseItem, resolvePotionHolder(potion.get(), null));
    }

    private static Item resolveItemValue(Object value, IdentifierCompat.Id id, String context) {
        if (value instanceof Item resolved) return resolved;
        if (value instanceof Holder<?> holder && holder.value() instanceof Item resolved) return resolved;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Potion> resolvePotionHolder(Object value, IdentifierCompat.Id id) {
        if (value instanceof Potion potion) return BuiltInRegistries.POTION.wrapAsHolder(potion);
        if (value instanceof Holder<?> holder && holder.value() instanceof Potion) return (Holder<Potion>) holder;
        return null;
    }

    private static Long safeMultiply(long a, int b) {
        try { return Math.multiplyExact(a, b); } catch (ArithmeticException ex) { return null; }
    }
}

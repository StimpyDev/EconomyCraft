package com.reazip.economycraft.shop;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.IdentityCompat;
import com.reazip.economycraft.util.ProfileComponentCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.ResourceKey;
import com.reazip.economycraft.util.IdentifierCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ServerShopUi {
    private static final Component STORED_MSG = Component.literal("Item stored: ").withStyle(ChatFormatting.YELLOW);
    private static final Map<String, IdentifierCompat.Id> CATEGORY_ICONS = buildCategoryIcons();
    private static final List<Integer> STAR_SLOT_ORDER = buildStarSlotOrder(5);
    private static final Map<String, Long> KIT_COOLDOWNS = new HashMap<>();
    private static final long KIT_COOLDOWN_TIME = 3600000L;
    
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

        String cat = category.trim();
        if (cat.equalsIgnoreCase("kits")) {
            openItems(player, eco, "kits");
            return;
        }

        PriceRegistry prices = eco.getPrices();
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
                .withStyle(s -> s.withColor(net.minecraft.network.chat.TextColor.fromRgb(0x81F571))
                                 .withItalic(false));
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
            categories.add("Kits");
            for (String cat : prices.buyTopCategories()) {
                if (hasItems(prices, cat)) categories.add(cat);
            }
        }

        private void setupSlots(Inventory inv) {
            for (int i = 0; i < 54; i++) {
                int r = i / 9, c = i % 9;
                this.addSlot(new Slot(container, i, 8 + c * 18, 18 + r * 18) {
                    @Override public boolean mayPickup(Player player) { return false; }
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                });
            }
            int y = 18 + 6 * 18 + 14;
            for (int r = 0; r < 3; r++) 
                for (int c = 0; c < 9; c++) 
                    this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
            for (int c = 0; c < 9; c++) 
                this.addSlot(new Slot(inv, c, 8 + c * 18, y + 58));
        }

private void updatePage() {
    container.clearContent();
    java.util.Arrays.fill(slotToIndex, -1);
    int start = page * itemsPerPage;
    int totalPages = (int) Math.ceil(categories.size() / (double) itemsPerPage);
    int filledCount = 0;

    for (int i = 0; i < categories.size(); i++) {
        if (filledCount >= itemsPerPage) break;
        int idx = start + i;
        if (idx >= categories.size()) break;

        String cat = categories.get(idx);
        ItemStack icon = createCategoryIcon(cat, cat, prices, viewer);
        
        if (icon.isEmpty() || icon.is(Items.AIR)) continue;

        icon.set(DataComponents.CUSTOM_NAME, Component.literal(formatCategoryTitle(cat))
            .withStyle(s -> s.withItalic(false).withColor(getCategoryColor(cat)).withBold(true)));
        icon.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Klik om artikelen te bekijken").withStyle(s -> s.withItalic(false)))));
        
        int slot = STAR_SLOT_ORDER.get(filledCount);
        container.setItem(slot, icon);
        slotToIndex[slot] = idx;
        filledCount++;
    }
    
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

    ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
    filler.set(DataComponents.CUSTOM_NAME, Component.literal(""));

    for (int i = 0; i < container.getContainerSize(); i++) {
        if (container.getItem(i).isEmpty()) {
            container.setItem(i, filler);
        }
    }
}

        @Override public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) {
                if (slot < navRowStart) {
                    int index = slotToIndex[slot];
                    if (index >= 0 && index < categories.size()) {
                        open(viewer, eco, categories.get(index));
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
            this.eco = eco;
            this.viewer = viewer;
            this.topCategory = topCategory;
            this.prices = eco.getPrices();
            for (String sub : prices.buySubcategories(topCategory)) {
                if (hasItems(prices, topCategory + "." + sub)) subcategories.add(sub);
            }
            this.rows = requiredRows(subcategories.size());
            this.itemsPerPage = (rows - 1) * 9;
            this.navRowStart = itemsPerPage;
            this.container = new SimpleContainer(rows * 9);
            setupSlots(inv);
            updatePage();
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
            for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) 
                this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
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

        @Override public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) {
                if (slot < navRowStart) {
                    int index = page * itemsPerPage + slot;
                    if (index < subcategories.size()) {
                        openItems(viewer, eco, topCategory + "." + subcategories.get(index), subcategories.get(index));
                        return;
                    }
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
            super(getMenuType(category.equalsIgnoreCase("kits") ? 2 : requiredRows(eco.getPrices().buyableByCategory(category).size())), id);
            this.eco = eco;
            this.viewer = viewer;
            this.category = category;
            this.prices = eco.getPrices();
            if (!category.equalsIgnoreCase("kits")) entries = new ArrayList<>(prices.buyableByCategory(category));
            this.rows = category.equalsIgnoreCase("kits") ? 2 : requiredRows(entries.size());
            this.itemsPerPage = (rows - 1) * 9;
            this.navRowStart = itemsPerPage;
            this.container = new SimpleContainer(rows * 9);
            setupSlots(inv);
            updatePage();
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
            for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) 
                this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, y + r * 18));
            for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c, 8 + c * 18, y + 58));
        }
        
        private void updatePage() {
            container.clearContent();
            if (category.equalsIgnoreCase("kits")) {
                ItemStack starterKit = new ItemStack(Items.DIAMOND_CHESTPLATE);
                starterKit.set(DataComponents.CUSTOM_NAME, Component.literal("Starter Kit").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                starterKit.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);          
                List<Component> starterLore = new ArrayList<>();
                starterLore.add(Component.literal("Prijs: ").withStyle(ChatFormatting.GREEN).append(Component.literal("GRATIS").withStyle(ChatFormatting.GOLD)));
                starterLore.add(Component.literal("Cooldown: Eenmalig Gebruik").withStyle(ChatFormatting.RED));
                starterLore.add(Component.literal("Inhoud:").withStyle(ChatFormatting.GRAY));
                starterLore.add(Component.literal("- Full Diamond Kit (Prot 1)").withStyle(ChatFormatting.DARK_GRAY));
                starterLore.add(Component.literal("- Diamond Tools (Sharp 1 / Eff 1)").withStyle(ChatFormatting.DARK_GRAY));
                starterLore.add(Component.literal("- Shield & 32 Steak").withStyle(ChatFormatting.DARK_GRAY));
                starterKit.set(DataComponents.LORE, new ItemLore(starterLore));
                
                container.setItem(2, starterKit);

               ItemStack trapperKit = new ItemStack(Items.TNT_MINECART);
               trapperKit.set(DataComponents.CUSTOM_NAME, Component.literal("Trapper Kit").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
               trapperKit.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true); 

               List<Component> trapperLore = new ArrayList<>();
               trapperLore.add(Component.literal("Prijs: ").withStyle(ChatFormatting.GREEN).append(Component.literal("€25.000").withStyle(ChatFormatting.GOLD)));
               trapperLore.add(Component.literal("Cooldown: 1 uur").withStyle(ChatFormatting.RED));
               trapperLore.add(Component.literal("Inhoud:").withStyle(ChatFormatting.GRAY));
               trapperLore.add(Component.literal("- Shulker met 32 TNT Minecarts").withStyle(ChatFormatting.DARK_GRAY));
               trapperLore.add(Component.literal("- Redstone, Rails & Observers").withStyle(ChatFormatting.DARK_GRAY));

               trapperKit.set(DataComponents.LORE, new ItemLore(trapperLore));
               container.setItem(6, trapperKit);

                ItemStack kit = new ItemStack(Items.NETHERITE_CHESTPLATE);
                kit.set(DataComponents.CUSTOM_NAME, Component.literal("Full Netherite Kit").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                List<Component> kitLore = new ArrayList<>();
                kitLore.add(Component.literal("Prijs: ").withStyle(ChatFormatting.GREEN).append(Component.literal("€500.000").withStyle(ChatFormatting.GOLD)));
                kitLore.add(Component.literal("Cooldown: 1 uur").withStyle(ChatFormatting.RED));
                kitLore.add(Component.literal("Inhoud:").withStyle(ChatFormatting.GRAY));
                kitLore.add(Component.literal("- Full Netherite Kit").withStyle(ChatFormatting.DARK_GRAY));
                kitLore.add(Component.literal("- Netherite Spear").withStyle(ChatFormatting.DARK_GRAY));
                kitLore.add(Component.literal("- Netherite Zwaard").withStyle(ChatFormatting.DARK_GRAY));
                kitLore.add(Component.literal("- Netherite Axe").withStyle(ChatFormatting.DARK_GRAY));
                kit.set(DataComponents.LORE, new ItemLore(kitLore));
                kit.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                container.setItem(4, kit);
            } else {
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
                    lore.add(labeledValue("Koop", EconomyCraft.formatMoney(entry.unitBuy()), LABEL_PRIMARY_COLOR));
                    Long stackPrice = safeMultiply(entry.unitBuy(), stackSize);
                    if (stackSize > 1 && stackPrice != null) lore.add(labeledValue("Stack (" + stackSize + ")", EconomyCraft.formatMoney(stackPrice), LABEL_PRIMARY_COLOR));
                    lore.add(labeledValue("Linker muis", "Koop 1", LABEL_SECONDARY_COLOR));
                    if (stackSize > 1) lore.add(labeledValue("Shift-klik", "Koop " + stackSize, LABEL_SECONDARY_COLOR));
                    display.set(DataComponents.LORE, new ItemLore(lore));
                    container.setItem(i, display);
                }
                ItemStack paper = new ItemStack(Items.PAPER);
                paper.set(DataComponents.CUSTOM_NAME, Component.literal("Pagina " + (page + 1) + "/" + Math.max(1, totalPages)).withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 4, paper);
            }
            if (page > 0) {
                ItemStack prev = new ItemStack(Items.ARROW);
                prev.set(DataComponents.CUSTOM_NAME, Component.literal("Vorige pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 3, prev);
            }
            if (!category.equalsIgnoreCase("kits") && (page + 1) * itemsPerPage < entries.size()) {
                ItemStack next = new ItemStack(Items.ARROW);
                next.set(DataComponents.CUSTOM_NAME, Component.literal("Volgende pagina").withStyle(s -> s.withItalic(false)));
                container.setItem(navRowStart + 5, next);
            }
            ItemStack back = new ItemStack(Items.BARRIER);
            back.set(DataComponents.CUSTOM_NAME, Component.literal("Terug").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_RED).withBold(true)));
            container.setItem(navRowStart + 8, back);
            container.setItem(navRowStart, createBalanceItem(viewer));
        }

@Override public void clicked(int slot, int dragType, ClickType type, Player player) {
            if (slot < 0 || slot >= navRowStart + 9) { super.clicked(slot, dragType, type, player); return; }
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) {
                
                if (category.equalsIgnoreCase("kits")) {
                    if (slot == 2) { handleStarterKitPurchase(); return; }
                    if (slot == 4) { handleKitPurchase(); return; }
                    if (slot == 6) { handleTrapperKitPurchase(); return; }
                }

                if (slot < navRowStart && !category.equalsIgnoreCase("kits")) {
                    int index = (page * itemsPerPage) + slot;
                    if (index >= 0 && index < entries.size()) handlePurchase(entries.get(index), type);
                    return;
                }
                if (slot == navRowStart + 3 && page > 0) { page--; updatePage(); return; }
                if (slot == navRowStart + 5 && !category.equalsIgnoreCase("kits") && (page + 1) * itemsPerPage < entries.size()) { page++; updatePage(); return; }
                if (slot == navRowStart + 8) {
                    if (category.contains(".")) openSubcategories(viewer, eco, category.substring(0, category.indexOf('.')));
                    else openRoot(viewer, eco);
                    return;
                }
            }
            super.clicked(slot, dragType, type, player);
        }
private void handleTrapperKitPurchase() {
    UUID uuid = viewer.getUUID();
    String kitKey = uuid.toString() + "_trapper";
    long now = System.currentTimeMillis();
    
    if (KIT_COOLDOWNS.getOrDefault(kitKey, 0L) > now) {
        long waitMinutes = (KIT_COOLDOWNS.get(kitKey) - now) / 60000;
        viewer.sendSystemMessage(Component.literal("Wacht nog " + waitMinutes + " minuten voor de Trapper Kit.")
            .withStyle(ChatFormatting.RED));
        return;
    }

    long cost = 1000000L;
    if (eco.getBalance(uuid, true) < cost) {
        viewer.sendSystemMessage(Component.literal("Je hebt geen ")
            .append(Component.literal("€100.000").withStyle(ChatFormatting.GOLD))
            .append("!").withStyle(ChatFormatting.RED));
        return;
    }

    if (eco.removeMoney(uuid, cost)) {
        KIT_COOLDOWNS.put(kitKey, now + 3600000L); 
        giveTrapperItems();
        
        viewer.sendSystemMessage(Component.literal("Trapper Kit gekocht voor ")
            .append(Component.literal("€100.000").withStyle(ChatFormatting.GOLD))
            .append("!").withStyle(ChatFormatting.GREEN));
        sendPrivateSound(SoundEvents.EXPERIENCE_ORB_PICKUP);
    }
}

private void giveTrapperItems() {
    Inventory inv = viewer.getInventory();
    
    List<ItemStack> shulkerItems = new ArrayList<>();
    shulkerItems.add(new ItemStack(Items.TNT_MINECART, 32));

    ItemStack shulker = new ItemStack(Items.RED_SHULKER_BOX);
    
    shulker.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(shulkerItems));
    
    inv.add(shulker);
    
    inv.add(new ItemStack(Items.REDSTONE, 64));
    inv.add(new ItemStack(Items.REPEATER, 12));
    inv.add(new ItemStack(Items.POWERED_RAIL, 64));
    inv.add(new ItemStack(Items.STONE, 64));
    inv.add(new ItemStack(Items.OBSERVER, 12));
    inv.add(new ItemStack(Items.RAIL, 64));
}

private void handleStarterKitPurchase() {
    UUID uuid = viewer.getUUID();
    long now = System.currentTimeMillis();
    String kitKey = uuid.toString() + "_starter";

    if (KIT_COOLDOWNS.getOrDefault(kitKey, 0L) > now) {
        viewer.sendSystemMessage(Component.literal("Je hebt deze kit al geclaimd!").withStyle(ChatFormatting.RED));
        return;
    }

    giveStarterItems();

    long infinite = now + (365L * 24 * 60 * 60 * 1000 * 100);
    KIT_COOLDOWNS.put(kitKey, infinite);

    viewer.sendSystemMessage(Component.literal("Starter Kit ontvangen!").withStyle(ChatFormatting.GREEN));
    sendPrivateSound(SoundEvents.EXPERIENCE_ORB_PICKUP);
}

private void giveStarterItems() {
    HolderLookup.Provider p = viewer.registryAccess();
    Inventory inv = viewer.getInventory();

    inv.add(createEnchanted(Items.DIAMOND_HELMET, p, Map.of(Enchantments.PROTECTION, 1)));
    inv.add(createEnchanted(Items.DIAMOND_CHESTPLATE, p, Map.of(Enchantments.PROTECTION, 1)));
    inv.add(createEnchanted(Items.DIAMOND_LEGGINGS, p, Map.of(Enchantments.PROTECTION, 1)));
    inv.add(createEnchanted(Items.DIAMOND_BOOTS, p, Map.of(Enchantments.PROTECTION, 1)));

    inv.add(createEnchanted(Items.DIAMOND_SWORD, p, Map.of(Enchantments.SHARPNESS, 1)));
    inv.add(createEnchanted(Items.DIAMOND_PICKAXE, p, Map.of(Enchantments.EFFICIENCY, 1)));
    inv.add(createEnchanted(Items.DIAMOND_AXE, p, Map.of(Enchantments.EFFICIENCY, 1)));
    
    inv.add(new ItemStack(Items.SHIELD));
    inv.add(new ItemStack(Items.COOKED_BEEF, 32));
}

private void handleKitPurchase() {
    UUID uuid = viewer.getUUID();
    long now = System.currentTimeMillis();
    String kitKey = uuid.toString() + "_netherite"; 

    if (KIT_COOLDOWNS.getOrDefault(kitKey, 0L) > now) {
        long waitSeconds = (KIT_COOLDOWNS.get(kitKey) - now) / 1000;
        long waitMinutes = waitSeconds / 60;
        viewer.sendSystemMessage(Component.literal("Wacht nog " + waitMinutes + " minuten om deze kit te kopen.").withStyle(ChatFormatting.RED));
        return;
    }

    long cost = 500000L;
    if (eco.getBalance(uuid, true) < cost) {
        viewer.sendSystemMessage(Component.literal("Je hebt geen ")
            .append(Component.literal("€500.000").withStyle(ChatFormatting.GOLD))
            .append("!").withStyle(ChatFormatting.RED));
        return;
    }

    if (eco.removeMoney(uuid, cost)) {
        KIT_COOLDOWNS.put(kitKey, now + KIT_COOLDOWN_TIME);
        giveKitItems();
        
        viewer.sendSystemMessage(Component.literal("Netherite Kit gekocht voor ")
            .append(Component.literal("€500.000").withStyle(ChatFormatting.GOLD))
            .append("!").withStyle(ChatFormatting.GREEN));
        sendPrivateSound(SoundEvents.EXPERIENCE_ORB_PICKUP);
    }
}

        private void giveKitItems() {
            HolderLookup.Provider p = viewer.registryAccess();
            Inventory inv = viewer.getInventory();
            inv.add(createEnchanted(Items.NETHERITE_HELMET, p, Map.of(Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3, Enchantments.AQUA_AFFINITY, 1, Enchantments.RESPIRATION, 3, Enchantments.MENDING, 1)));
            inv.add(createEnchanted(Items.NETHERITE_CHESTPLATE, p, Map.of(Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1)));
            inv.add(createEnchanted(Items.NETHERITE_LEGGINGS, p, Map.of(Enchantments.PROTECTION, 4, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1, Enchantments.SWIFT_SNEAK, 3)));
            inv.add(createEnchanted(Items.NETHERITE_BOOTS, p, Map.of(Enchantments.FEATHER_FALLING, 4, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1, Enchantments.DEPTH_STRIDER, 3, Enchantments.PROTECTION, 4)));
            inv.add(createEnchanted(Items.NETHERITE_SWORD, p, Map.of(Enchantments.SHARPNESS, 5, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1, Enchantments.FIRE_ASPECT, 2, Enchantments.SWEEPING_EDGE, 3, Enchantments.LOOTING, 3)));
            inv.add(createEnchanted(Items.NETHERITE_AXE, p, Map.of(Enchantments.UNBREAKING, 3, Enchantments.SHARPNESS, 5, Enchantments.MENDING, 1, Enchantments.SILK_TOUCH, 1)));
            
            ItemStack spear = createEnchanted(Items.NETHERITE_SPEAR, p, Map.of(
                Enchantments.SHARPNESS, 5, 
                Enchantments.MENDING, 1, 
                Enchantments.UNBREAKING, 3, 
                Enchantments.KNOCKBACK, 2,
                Enchantments.LUNGE, 3
            ));
            inv.add(spear);
        }
        
        private void handlePurchase(PriceRegistry.PriceEntry entry, ClickType clickType) {
            if (entry.unitBuy() <= 0) { viewer.sendSystemMessage(Component.literal("Niet te koop.").withStyle(ChatFormatting.RED)); return; }
            ItemStack base = createDisplayStack(entry, viewer);
            if (base.isEmpty()) { viewer.sendSystemMessage(Component.literal("Niet beschikbaar.").withStyle(ChatFormatting.RED)); return; }
            int amount = clickType == ClickType.QUICK_MOVE ? Math.max(1, entry.stack()) : 1;
            if (viewer.getInventory().getFreeSlot() == -1) { sendPrivateSound(SoundEvents.VILLAGER_NO); viewer.sendSystemMessage(Component.literal("Inventaris vol!").withStyle(ChatFormatting.RED)); return; }
            Long total = safeMultiply(entry.unitBuy(), amount);
            if (total == null || eco.getBalance(viewer.getUUID(), true) < total) { sendPrivateSound(SoundEvents.VILLAGER_NO); viewer.sendSystemMessage(Component.literal("Onvoldoende saldo.").withStyle(ChatFormatting.RED)); return; }
            if (eco.removeMoney(viewer.getUUID(), total)) {
                giveToPlayer(base, amount);
                sendPrivateSound(SoundEvents.EXPERIENCE_ORB_PICKUP);
                viewer.sendSystemMessage(Component.literal("Gekocht: ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(amount + "x " + base.getHoverName().getString()).withStyle(ChatFormatting.YELLOW))
                    .append(" voor ").append(Component.literal(EconomyCraft.formatMoney(total)).withStyle(ChatFormatting.GOLD)));
            }
        }

        private void sendPrivateSound(net.minecraft.sounds.SoundEvent sound) {
            viewer.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.PLAYERS, viewer.getX(), viewer.getY(), viewer.getZ(), 1.0f, 1.0f, viewer.getRandom().nextLong()));
        }

        private void giveToPlayer(ItemStack base, int amount) {
            int remaining = amount;
            while (remaining > 0) {
                int give = Math.min(base.getMaxStackSize(), remaining);
                ItemStack stack = base.copyWithCount(give);
                if (!viewer.getInventory().add(stack)) eco.getShop().addDelivery(viewer.getUUID(), stack);
                remaining -= give;
            }
        }

        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    }

    private static ItemStack createEnchanted(Item item, HolderLookup.Provider p, Map<ResourceKey<Enchantment>, Integer> enchants) {
        ItemStack stack = new ItemStack(item);
        ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        var lookup = p.lookupOrThrow(Registries.ENCHANTMENT);
        enchants.forEach((k, v) -> lookup.get(k).ifPresent(h -> m.set(h, v)));
        stack.set(DataComponents.ENCHANTMENTS, m.toImmutable());
        return stack;
    }

    private static ItemStack createCategoryIcon(String display, String key, PriceRegistry prices, ServerPlayer viewer) {
        if (display.equalsIgnoreCase("Kits")) {
            ItemStack stack = new ItemStack(Items.GRAY_WOOL);
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            return stack;
        }
        IdentifierCompat.Id id = CATEGORY_ICONS.get(normalizeCategoryKey(display));
        if (id == null && key != null) id = CATEGORY_ICONS.get(normalizeCategoryKey(key));
        if (id != null) {
            Optional<?> item = IdentifierCompat.registryGetOptional(BuiltInRegistries.ITEM, id);
            if (item.isPresent()) {
                Item res = resolveItemValue(item.get(), id, "cat");
                if (res != null) return new ItemStack(res);
            }
        }
        List<PriceRegistry.PriceEntry> entries = prices.buyableByCategory(key);
        if (entries.isEmpty() && key != null && !key.contains(".")) {
            for (String sub : prices.buySubcategories(key)) {
                List<PriceRegistry.PriceEntry> subE = prices.buyableByCategory(key + "." + sub);
                if (!subE.isEmpty()) { entries = subE; break; }
            }
        }
        return !entries.isEmpty() ? createDisplayStack(entries.get(0), viewer) : new ItemStack(Items.BOOK);
    }

    private static Map<String, IdentifierCompat.Id> buildCategoryIcons() {
        Map<String, IdentifierCompat.Id> m = new HashMap<>();
        m.put(normalizeCategoryKey("Redstone"), IdentifierCompat.withDefaultNamespace("redstone"));
        m.put(normalizeCategoryKey("Eten"), IdentifierCompat.withDefaultNamespace("cooked_beef"));
        m.put(normalizeCategoryKey("Ores"), IdentifierCompat.withDefaultNamespace("iron_ingot"));
        m.put(normalizeCategoryKey("Blokken"), IdentifierCompat.withDefaultNamespace("grass_block"));
        m.put(normalizeCategoryKey("Stenen"), IdentifierCompat.withDefaultNamespace("cobblestone"));
        m.put(normalizeCategoryKey("Bakstenen"), IdentifierCompat.withDefaultNamespace("bricks"));
        m.put(normalizeCategoryKey("Koper"), IdentifierCompat.withDefaultNamespace("copper_block"));
        m.put(normalizeCategoryKey("Aarde"), IdentifierCompat.withDefaultNamespace("dirt"));
        m.put(normalizeCategoryKey("Zand"), IdentifierCompat.withDefaultNamespace("sand"));
        m.put(normalizeCategoryKey("Hout"), IdentifierCompat.withDefaultNamespace("oak_log"));
        m.put(normalizeCategoryKey("Drops"), IdentifierCompat.withDefaultNamespace("gunpowder"));
        m.put(normalizeCategoryKey("Hulpmiddelen"), IdentifierCompat.withDefaultNamespace("totem_of_undying"));
        m.put(normalizeCategoryKey("Vervoer"), IdentifierCompat.withDefaultNamespace("saddle"));
        m.put(normalizeCategoryKey("Licht"), IdentifierCompat.withDefaultNamespace("lantern"));
        m.put(normalizeCategoryKey("Planten"), IdentifierCompat.withDefaultNamespace("kelp"));
        m.put(normalizeCategoryKey("Gereedschap"), IdentifierCompat.withDefaultNamespace("diamond_pickaxe"));
        m.put(normalizeCategoryKey("Wapens"), IdentifierCompat.withDefaultNamespace("diamond_sword"));
        m.put(normalizeCategoryKey("Armor"), IdentifierCompat.withDefaultNamespace("diamond_chestplate"));
        m.put(normalizeCategoryKey("Enchantments"), IdentifierCompat.withDefaultNamespace("enchanted_book"));
        m.put(normalizeCategoryKey("Brouwen"), IdentifierCompat.withDefaultNamespace("water_bottle"));
        m.put(normalizeCategoryKey("Oceaan"), IdentifierCompat.withDefaultNamespace("tube_coral"));
        m.put(normalizeCategoryKey("Nether"), IdentifierCompat.withDefaultNamespace("netherrack"));
        m.put(normalizeCategoryKey("End"), IdentifierCompat.withDefaultNamespace("end_stone"));
        m.put(normalizeCategoryKey("Diepe duisternis"), IdentifierCompat.withDefaultNamespace("sculk"));
        m.put(normalizeCategoryKey("Archeologie"), IdentifierCompat.withDefaultNamespace("brush"));
        m.put(normalizeCategoryKey("Ijs"), IdentifierCompat.withDefaultNamespace("ice"));
        m.put(normalizeCategoryKey("Verf"), IdentifierCompat.withDefaultNamespace("blue_dye"));
        m.put(normalizeCategoryKey("Platen"), IdentifierCompat.withDefaultNamespace("music_disc_strad"));
        return m;
    }

    private static ItemStack createDisplayStack(PriceRegistry.PriceEntry entry, ServerPlayer viewer) {
        try {
            IdentifierCompat.Id id = entry.id();
            Optional<?> item = IdentifierCompat.registryGetOptional(BuiltInRegistries.ITEM, id);
            if (item.isPresent()) {
                Item res = resolveItemValue(item.get(), id, "disp");
                if (res != null && res != Items.AIR) return new ItemStack(res);
                return ItemStack.EMPTY;
            }
            String path = id.path();
            if (path.startsWith("enchanted_book_")) return createEnchantedBookStack(id, viewer);
            return createPotionStack(id);
        } catch (Exception ex) { return ItemStack.EMPTY; }
    }

    private static ItemStack createEnchantedBookStack(IdentifierCompat.Id key, ServerPlayer viewer) {
        String path = key.path().substring("enchanted_book_".length());
        int lastU = path.lastIndexOf('_');
        if (lastU <= 0) return ItemStack.EMPTY;
        String ep = path.substring(0, lastU);
        int lvl = Integer.parseInt(path.substring(lastU + 1));
        if (ep.equals("curse_of_binding")) ep = "binding_curse";
        else if (ep.equals("curse_of_vanishing")) ep = "vanishing_curse";
        IdentifierCompat.Id eid = IdentifierCompat.fromNamespaceAndPath(key.namespace(), ep);
        var lookup = viewer.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var holder = lookup.get(IdentifierCompat.createResourceKey(Registries.ENCHANTMENT, eid));
        if (holder.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        m.set(holder.get(), lvl);
        stack.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
        return stack;
    }

    private static ItemStack createPotionStack(IdentifierCompat.Id key) {
        String path = key.path();
        Item base = Items.POTION;
        String w = path;
        if (path.startsWith("splash_")) { base = Items.SPLASH_POTION; w = path.substring(7); }
        else if (path.startsWith("lingering_")) { base = Items.LINGERING_POTION; w = path.substring(10); }
        else if (path.startsWith("arrow_of_")) { base = Items.TIPPED_ARROW; w = path.substring(9); }
        else if (path.startsWith("potion_of_")) w = path.substring(10);
        
        if (w.endsWith("_splash_potion")) { base = Items.SPLASH_POTION; w = w.substring(0, w.length()-14); }
        else if (w.endsWith("_lingering_potion")) { base = Items.LINGERING_POTION; w = w.substring(0, w.length()-17); }
        
        String pPath = w;
        if (w.equals("water_bottle") || w.equals("water")) pPath = "water";
        else {
            if (w.endsWith("_extended")) pPath = "long_" + w.substring(0, w.length()-9);
            else if (w.endsWith("_2")) pPath = "strong_" + w.substring(0, w.length()-2);
        }
        if ("the_turtle_master".equals(pPath)) pPath = "turtle_master";
        Optional<?> pot = IdentifierCompat.registryGetOptional(BuiltInRegistries.POTION, IdentifierCompat.fromNamespaceAndPath(key.namespace(), pPath));
        if (pot.isEmpty()) return ItemStack.EMPTY;
        Holder<Potion> h = resolvePotionHolder(pot.get(), null);
        return h != null ? PotionContents.createItemStack(base, h) : ItemStack.EMPTY;
    }

    private static Item resolveItemValue(Object v, IdentifierCompat.Id id, String c) {
        if (v instanceof Item i) return i;
        if (v instanceof Holder<?> h && h.value() instanceof Item i) return i;
        return null;
    }

    private static Holder<Potion> resolvePotionHolder(Object v, IdentifierCompat.Id id) {
        if (v instanceof Potion p) return BuiltInRegistries.POTION.wrapAsHolder(p);
        if (v instanceof Holder<?> h && h.value() instanceof Potion p) return (Holder<Potion>) h;
        return null;
    }

private static ChatFormatting getCategoryColor(String key) {
        String n = normalizeCategoryKey(key);
        return switch (n) {
            case "kits" -> ChatFormatting.DARK_GRAY;
            case "redstone" -> ChatFormatting.RED;
            case "eten" -> ChatFormatting.GOLD;
            case "ores" -> ChatFormatting.AQUA;
            case "blokken" -> ChatFormatting.DARK_GREEN;
            case "armor" -> ChatFormatting.BLUE;
            case "wapens" -> ChatFormatting.DARK_AQUA;
            case "enchantments" -> ChatFormatting.DARK_PURPLE;
            case "drops" -> ChatFormatting.GRAY;
            case "brouwen" -> ChatFormatting.DARK_BLUE;
            case "gereedschap" -> ChatFormatting.DARK_RED;
            default -> ChatFormatting.YELLOW;
        };
    }

    private static String normalizeCategoryKey(String key) { 
        return key == null ? "" : key.replace('.', ' ').replace('-', ' ').replace('_', ' ').trim().toLowerCase(Locale.ROOT); 
    }

    private static void fillEmptyWithPanes(SimpleContainer c, int l) { 
        ItemStack f = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        f.set(DataComponents.CUSTOM_NAME, Component.literal(" ")); 
        for (int i=0; i<l && i<c.getContainerSize(); i++) 
            if (c.getItem(i).isEmpty()) c.setItem(i, f.copy());
    }

    private static MenuType<?> getMenuType(int r) { 
        return switch (r) { 
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2; 
            case 3 -> MenuType.GENERIC_9x3; 
            case 4 -> MenuType.GENERIC_9x4; 
            case 5 -> MenuType.GENERIC_9x5; 
            default -> MenuType.GENERIC_9x6; 
        };
    }

    private static int requiredRows(int count) { 
        return Math.min(6, Math.max(2, (int) Math.ceil(Math.max(1, count) / 9.0) + 1)); 
    }

    private static Component labeledValue(String l, String v, ChatFormatting c) { 
        return Component.literal(l + ": ").withStyle(s -> s.withItalic(false).withColor(c))
                .append(Component.literal(v).withStyle(s -> s.withItalic(false).withColor(VALUE_COLOR)));
    }

    private static ItemStack createBalanceItem(ServerPlayer p) { 
        ItemStack g = new ItemStack(Items.GOLD_INGOT); 
        long bal = EconomyCraft.getManager(((ServerLevel)p.level()).getServer()).getBalance(p.getUUID(), true);
        g.set(DataComponents.CUSTOM_NAME, Component.literal("Saldo").withStyle(s -> s.withItalic(true).withColor(BALANCE_NAME_COLOR))); 
        g.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(EconomyCraft.formatMoney(bal)).withStyle(s -> s.withItalic(true).withColor(BALANCE_VALUE_COLOR))))); 
        return g;
    }

    private static Long safeMultiply(long a, int b) { 
        try { return Math.multiplyExact(a, b); } catch (Exception e) { return null; } 
    }

    private static boolean hasItems(PriceRegistry p, String c) { 
        return !p.buyableByCategory(c).isEmpty() || !p.buySubcategories(c).isEmpty(); 
    }
    
    public static void clearPlayerCooldowns(UUID uuid) {
    KIT_COOLDOWNS.remove(uuid);
}

    private static List<Integer> buildStarSlotOrder(int rows) {
        List<Integer> order = new ArrayList<>();
        int centerX = 4;
        int centerY = 2;
        for (int i = 0; i < 45; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(slot -> {
            int x = slot % 9;
            int y = slot / 9;
            return Math.abs(x - centerX) + Math.abs(y - centerY);
        }));
        return order;
    }
}

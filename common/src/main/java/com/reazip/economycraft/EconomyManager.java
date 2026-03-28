package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.reazip.economycraft.util.IdentityCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class EconomyManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<UUID, Long>>(){}.getType();
    private static final Type DAILY_SELL_TYPE = new TypeToken<Map<UUID, DailySellData>>(){}.getType();
    
    private static final Map<Item, Long> EXTRA_PRICES = Map.of(
        Items.MELON, 90L,
        Items.MELON_SLICE, 90L,
        Items.KELP, 90L,
        Items.BLAZE_ROD, 200L
    );

    private final MinecraftServer server;
    private final Path file, dailySellFile;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, DailySellData> dailySells = new ConcurrentHashMap<>();
    private final Map<UUID, String> userCache = new ConcurrentHashMap<>();

    private List<Map.Entry<UUID, Long>> cachedTopEntries = new ArrayList<>();
    private long lastTopUpdate = 0;

    private final PriceRegistry prices;
    private final com.reazip.economycraft.shop.ShopManager shop;
    private final com.reazip.economycraft.orders.OrderManager orders;

    private Objective objective;
    public static final long MAX = 999_999_999L;
    
    private boolean isDirty = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public EconomyManager(MinecraftServer server) {
        this.server = server;
        
        Path dataDir = server.getFile("config/economycraft/data");
        try { 
            Files.createDirectories(dataDir); 
        } catch (IOException ignored) {}

        this.file = dataDir.resolve("balances.json");
        this.dailySellFile = dataDir.resolve("daily_sells.json");

        loadAll();
        loadUserCache();

        this.prices = new PriceRegistry(server);
        this.shop = new com.reazip.economycraft.shop.ShopManager(server);
        this.orders = new com.reazip.economycraft.orders.OrderManager(server);

        initScoreboard();
        startAutoSave();
    }

    private void startAutoSave() {
        scheduler.scheduleAtFixedRate(() -> {
            if (isDirty) {
                save();
            }
        }, 5, 5, TimeUnit.MINUTES);
    }

    public void markDirty() {
        this.isDirty = true;
    }

    public MinecraftServer getServer() { return server; }

    // --- Core API ---

    public Map<UUID, Long> getBalances() { return balances; }

    public synchronized List<Map.Entry<UUID, Long>> getSortedBalances() {
        long now = System.currentTimeMillis();
        if (cachedTopEntries.isEmpty() || (now - lastTopUpdate) > 60000) {
            cachedTopEntries = balances.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                    .limit(10)
                    .collect(Collectors.toList());
            lastTopUpdate = now;
        }
        return cachedTopEntries;
    }

    @Nullable
    public String getBestName(UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            String name = IdentityCompat.of(online).name();
            userCache.put(id, name);
            return name;
        }
        return userCache.getOrDefault(id, id.toString());
    }

    public UUID tryResolveUuidByName(String name) {
        if (name == null || name.isBlank()) return null;
        
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        for (var e : userCache.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue())) return e.getKey();
        }
        
        try { return UUID.fromString(name); } catch (Exception ignored) {}
        return null;
    }

    public Long getBalance(UUID player, boolean createIfMissing) {
        Long bal = balances.get(player);
        if (bal == null) {
            if (!createIfMissing) return null;
            long start = clamp(EconomyConfig.get().startingBalance);
            balances.put(player, start);
            updateScore(player, start);
            markDirty();
            return start;
        }
        return bal;
    }

    public void setMoney(UUID player, long amount) {
        long newVal = clamp(amount);
        balances.put(player, newVal);
        updateScore(player, newVal);
        markDirty();
    }

    public void addMoney(UUID player, long amount) {
        long newVal = clamp(getBalance(player, true) + amount);
        balances.put(player, newVal);
        updateScore(player, newVal);
        markDirty();
    }

    public boolean removeMoney(UUID player, long amount) {
        long current = getBalance(player, true);
        if (current < amount) return false;
        long newVal = clamp(current - amount);
        balances.put(player, newVal);
        updateScore(player, newVal);
        markDirty();
        return true;
    }

    public boolean pay(UUID from, UUID to, long amount) {
        if (amount <= 0) return false;
        if (removeMoney(from, amount)) {
            addMoney(to, amount);
            return true;
        }
        return false;
    }

    public void removePlayer(UUID player) {
        balances.remove(player);
        dailySells.remove(player);
        markDirty();
    }

    // --- Item Lore Price ---

    public void applyPriceLore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        ItemLore currentLore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> lines = new ArrayList<>(currentLore.lines());
        lines.removeIf(line -> line.getString().contains("Verkoopprijs:"));

        if (stack.is(ItemTags.SHULKER_BOXES)) {
            stack.set(DataComponents.LORE, new ItemLore(lines));
            return;
        }

        Long price = prices.getUnitSell(stack);
        
        if (price == null || price <= 0) {
            price = EXTRA_PRICES.get(stack.getItem());
        }

        MutableComponent priceLine = Component.literal("Verkoopprijs: ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
                .append(Component.literal(EconomyCraft.formatMoney(price))
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        
        lines.add(priceLine);
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    public void refreshPlayerInventory(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            applyPriceLore(player.getInventory().getItem(i));
        }
    }

    // --- File IO ---

    public boolean toggleScoreboard() {
        boolean newState = !EconomyConfig.get().scoreboardEnabled;
        EconomyConfig.get().scoreboardEnabled = newState;
        if (!newState && objective != null) {
            server.getScoreboard().removeObjective(objective);
            objective = null;
        } else if (newState) {
            getOrCreateObjective();
        }
        return newState;
    }

    private void initScoreboard() {
        if (EconomyConfig.get().scoreboardEnabled) getOrCreateObjective();
    }

    private void getOrCreateObjective() {
        Scoreboard board = server.getScoreboard();
        objective = board.getObjective("eco_balance");
        if (objective == null) {
            objective = board.addObjective("eco_balance", ObjectiveCriteria.DUMMY, Component.literal("Balance"), ObjectiveCriteria.RenderType.INTEGER, true, null);
        }
        board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        balances.forEach(this::updateScore);
    }

    private void updateScore(UUID player, long amount) {
        if (!EconomyConfig.get().scoreboardEnabled || objective == null) return;
        String name = getBestName(player);
        server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), objective).set((int) amount);
    }

    private void loadAll() {
        try {
            if (Files.exists(file)) {
                Map<UUID, Long> map = GSON.fromJson(Files.readString(file), TYPE);
                if (map != null) balances.putAll(map);
            }
            if (Files.exists(dailySellFile)) {
                Map<UUID, DailySellData> map = GSON.fromJson(Files.readString(dailySellFile), DAILY_SELL_TYPE);
                if (map != null) dailySells.putAll(map);
            }
        } catch (Exception ignored) {}
    }

    public synchronized void save() {
        try {
            Files.writeString(file, GSON.toJson(balances, TYPE));
            Files.writeString(dailySellFile, GSON.toJson(dailySells, DAILY_SELL_TYPE));
            isDirty = false;
        } catch (IOException ignored) {}
    }

    private void loadUserCache() {
        userCache.clear();
        try {
            Path cachePath = server.getFile("usercache.json");
            if (Files.exists(cachePath)) {
                UserCacheEntry[] entries = GSON.fromJson(Files.readString(cachePath), UserCacheEntry[].class);
                if (entries != null) {
                    for (UserCacheEntry e : entries) {
                        if (e.uuid != null && e.name != null) {
                            userCache.put(UUID.fromString(e.uuid), e.name);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public void handlePvpKill(ServerPlayer victim, ServerPlayer killer) {
        double pct = EconomyConfig.get().pvpBalanceLossPercentage;
        if (pct <= 0 || victim == null || killer == null || victim == killer) return;
        long victimBal = getBalance(victim.getUUID(), true);
        long loss = (long) Math.floor(pct * victimBal);
        if (loss <= 0) return;
        removeMoney(victim.getUUID(), loss);
        addMoney(killer.getUUID(), loss);
    }

    private long clamp(long value) { return Math.max(0, Math.min(MAX, value)); }
    public PriceRegistry getPrices() { return prices; }
    public void shutdown() { scheduler.shutdown(); save(); }
    private static final class UserCacheEntry { String name; String uuid; }
    private record DailySellData(long day, long amount) {}
}

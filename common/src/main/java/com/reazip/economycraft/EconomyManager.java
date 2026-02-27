package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.reazip.economycraft.util.IdentityCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

public class EconomyManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<UUID, Long>>(){}.getType();
    private static final Type DAILY_SELL_TYPE = new TypeToken<Map<UUID, DailySellData>>(){}.getType();

    private final MinecraftServer server;
    private final Path file, dailyFile, dailySellFile;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDaily = new ConcurrentHashMap<>();
    private final Map<UUID, DailySellData> dailySells = new ConcurrentHashMap<>();
    private Map<UUID, String> diskUserCache = null;

    private final PriceRegistry prices;
    private final com.reazip.economycraft.shop.ShopManager shop;
    private final com.reazip.economycraft.orders.OrderManager orders;

    private Objective objective;
    public static final long MAX = 999_999_999L;

    public EconomyManager(MinecraftServer server) {
        this.server = server;
        
        Path dataDir = server.getFile("config/economycraft/data");
        try { 
            Files.createDirectories(dataDir); 
        } catch (IOException ignored) {}

        this.file = dataDir.resolve("balances.json");
        this.dailyFile = dataDir.resolve("daily.json");
        this.dailySellFile = dataDir.resolve("daily_sells.json");

        loadAll();

        this.shop = new com.reazip.economycraft.shop.ShopManager(server);
        this.orders = new com.reazip.economycraft.orders.OrderManager(server);
        this.prices = new PriceRegistry(server);

        initScoreboard();
    }

    public MinecraftServer getServer() {
        return server;
    }

    // --- Core API ---

    public Map<UUID, Long> getBalances() { return balances; }

    @Nullable
    public String getBestName(UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return IdentityCompat.of(online).name();
        ensureDiskUserCacheLoaded();
        String fromCache = diskUserCache.get(id);
        return (fromCache != null) ? fromCache : id.toString();
    }

    public UUID tryResolveUuidByName(String name) {
        if (name == null || name.isBlank()) return null;
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();

        ensureDiskUserCacheLoaded();
        for (var e : diskUserCache.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue())) return e.getKey();
        }
        try { return UUID.fromString(name); } catch (Exception ignored) {}
        return null;
    }

    public Long getBalance(UUID player, boolean createIfMissing) {
        if (!balances.containsKey(player)) {
            if (!createIfMissing) return null;
            long start = clamp(EconomyConfig.get().startingBalance);
            balances.put(player, start);
            updateScore(player, start);
            return start;
        }
        return balances.get(player);
    }

    public void setMoney(UUID player, long amount) {
        long newVal = clamp(amount);
        balances.put(player, newVal);
        updateScore(player, newVal);
        save();
    }

    public void addMoney(UUID player, long amount) {
        long newVal = clamp(getBalance(player, true) + amount);
        balances.put(player, newVal);
        updateScore(player, newVal);
        save();
    }

    public boolean removeMoney(UUID player, long amount) {
        long current = getBalance(player, true);
        if (current < amount) return false;
        long newVal = clamp(current - amount);
        balances.put(player, newVal);
        updateScore(player, newVal);
        save();
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
        lastDaily.remove(player);
        dailySells.remove(player);
        save();
    }

    // --- Daily Reward/Sell Logic ---

        public boolean claimDaily(ServerPlayer player) {
    UUID id = player.getUUID();
    long today = LocalDate.now().toEpochDay();
    
    if (lastDaily.getOrDefault(id, 0L) >= today) {
        return false; // Already claimed
    }

    long amount = EconomyConfig.get().dailyReward;
    
    addMoney(id, amount);
    
    lastDaily.put(id, today);
    
    save(); 
    return true;
}

    public boolean tryRecordDailySell(UUID player, long saleAmount) {
        long limit = EconomyConfig.get().dailySellLimit;
        if (limit <= 0) return false;

        DailySellData data = getOrCreateTodaySellData(player);
        long newTotal = data.amount() + saleAmount;
        if (newTotal > limit) return true;

        dailySells.put(player, new DailySellData(data.day(), newTotal));
        save();
        return false;
    }

    public long getDailySellRemaining(UUID player) {
        long limit = EconomyConfig.get().dailySellLimit;
        if (limit <= 0) return Long.MAX_VALUE;
        return Math.max(0, limit - getOrCreateTodaySellData(player).amount());
    }

    private DailySellData getOrCreateTodaySellData(UUID player) {
        long today = LocalDate.now().toEpochDay();
        DailySellData data = dailySells.get(player);
        if (data == null || data.day() != today) {
            data = new DailySellData(today, 0L);
            dailySells.put(player, data);
        }
        return data;
    }

    // --- Scoreboard ---

    public boolean toggleScoreboard() {
        boolean newState = !EconomyConfig.get().scoreboardEnabled;
        EconomyConfig.get().scoreboardEnabled = newState;
        // EconomyConfig.save();
        
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

    // --- File IO ---

    private void loadAll() {
        try {
            if (Files.exists(file)) {
                Map<UUID, Long> map = GSON.fromJson(Files.readString(file), TYPE);
                if (map != null) balances.putAll(map);
            }
            if (Files.exists(dailyFile)) {
                Map<UUID, Long> map = GSON.fromJson(Files.readString(dailyFile), TYPE);
                if (map != null) lastDaily.putAll(map);
            }
            if (Files.exists(dailySellFile)) {
                Map<UUID, DailySellData> map = GSON.fromJson(Files.readString(dailySellFile), DAILY_SELL_TYPE);
                if (map != null) dailySells.putAll(map);
            }
        } catch (Exception ignored) {}
    }

    public void save() {
        try {
            Files.writeString(file, GSON.toJson(balances, TYPE));
            Files.writeString(dailyFile, GSON.toJson(lastDaily, TYPE));
            Files.writeString(dailySellFile, GSON.toJson(dailySells, DAILY_SELL_TYPE));
        } catch (IOException ignored) {}
    }

    private void ensureDiskUserCacheLoaded() {
        if (diskUserCache != null) return;
        diskUserCache = new HashMap<>();
        try {
            Path cachePath = server.getFile("usercache.json");
            if (Files.exists(cachePath)) {
                UserCacheEntry[] entries = GSON.fromJson(Files.readString(cachePath), UserCacheEntry[].class);
                if (entries != null) {
                    for (UserCacheEntry e : entries) {
                        if (e.uuid != null && e.name != null) diskUserCache.put(UUID.fromString(e.uuid), e.name);
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
        victim.sendSystemMessage(Component.literal("Jij hebt verloren " + EconomyCraft.formatMoney(loss) + " omdat je vermoord bent door " + killer.getName().getString()).withStyle(ChatFormatting.RED));
        killer.sendSystemMessage(Component.literal("Jij hebt ontvangen " + EconomyCraft.formatMoney(loss) + " voor het killen van: " + victim.getName().getString()).withStyle(ChatFormatting.GREEN));
    }

    private long clamp(long value) { return Math.max(0, Math.min(MAX, value)); }
    public com.reazip.economycraft.shop.ShopManager getShop() { return shop; }
    public com.reazip.economycraft.orders.OrderManager getOrders() { return orders; }
    public PriceRegistry getPrices() { return prices; }
    private static final class UserCacheEntry { String name; String uuid; }
    private record DailySellData(long day, long amount) {}
}

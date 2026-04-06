package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.reazip.economycraft.util.IdentityCompat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
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
    private static final String PRICE_TAG = "Verkoopprijs:";

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
    
    private volatile boolean isDirty = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Economy-Save-Thread");
        t.setDaemon(true);
        return t;
    });

    public EconomyManager(MinecraftServer server) {
        this.server = server;
        Path dataDir = server.getFile("config/economycraft/data");
        try { Files.createDirectories(dataDir); } catch (IOException ignored) {}

        this.file = dataDir.resolve("balances.json");
        this.dailySellFile = dataDir.resolve("daily_sells.json");

        loadAll();
        loadUserCache();

        this.shop = new com.reazip.economycraft.shop.ShopManager(server);
        this.orders = new com.reazip.economycraft.orders.OrderManager(server);
        this.prices = new PriceRegistry(server);

        initScoreboard();
        startAutoSave();
    }

    private void startAutoSave() {
        scheduler.scheduleAtFixedRate(() -> {
            if (isDirty) save();
        }, 5, 5, TimeUnit.MINUTES);
    }

    public void markDirty() { this.isDirty = true; }

    // --- Core API ---

    public synchronized List<Map.Entry<UUID, Long>> getSortedBalances() {
        long now = System.currentTimeMillis();
        if (cachedTopEntries.isEmpty() || (now - lastTopUpdate) > 60000) {
            cachedTopEntries = balances.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                    .limit(10)
                    .toList();
            lastTopUpdate = now;
        }
        return cachedTopEntries;
    }

    public Long getBalance(UUID player, boolean createIfMissing) {
        return balances.computeIfAbsent(player, k -> createIfMissing ? clamp(EconomyConfig.get().startingBalance) : null);
    }

    // --- Cleanup ---

    public void globalLoreCleanup() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                cleanItemLore(inv.getItem(i));
            }
            if (player.containerMenu != null) {
                player.containerMenu.slots.forEach(slot -> cleanItemLore(slot.getItem()));
            }
        }
        for (var level : server.getAllLevels()) {
            for (var entity : level.getEntities().getAll()) {
                if (entity instanceof ItemEntity itemEntity) {
                    cleanItemLore(itemEntity.getItem());
                }
            }
        }
    }

    private void cleanItemLore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) return;

        boolean hasTag = false;
        for (Component line : lore.lines()) {
            if (line.getString().contains(PRICE_TAG)) {
                hasTag = true;
                break;
            }
        }

        if (!hasTag) return;

        List<Component> filtered = lore.lines().stream()
                .filter(line -> !line.getString().contains(PRICE_TAG))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            stack.remove(DataComponents.LORE);
        } else {
            stack.set(DataComponents.LORE, new ItemLore(filtered));
        }
    }

    // --- Scoreboard ---

    private void updateScore(UUID player, long amount) {
        if (!EconomyConfig.get().scoreboardEnabled || objective == null) return;
        String name = getBestName(player);
        if (name == null) return;
        
        Scoreboard board = server.getScoreboard();
        board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), objective).set((int) amount);
    }

    // --- File IO ---

    public synchronized void save() {
        try {
            Files.writeString(file, GSON.toJson(balances));
            Files.writeString(dailySellFile, GSON.toJson(dailySells));
            isDirty = false;
        } catch (IOException e) {
            server.sendSystemMessage(Component.literal("§c[Economy] Fout bij opslaan data!"));
        }
    }

    private void loadUserCache() {
        userCache.clear();
        Path cachePath = server.getFile("usercache.json");
        if (!Files.exists(cachePath)) return;

        try (var reader = Files.newBufferedReader(cachePath)) {
            UserCacheEntry[] entries = GSON.fromJson(reader, UserCacheEntry[].class);
            if (entries != null) {
                for (UserCacheEntry e : entries) {
                    if (e.uuid != null && e.name != null) {
                        userCache.put(UUID.fromString(e.uuid), e.name);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private long clamp(long value) { return Math.max(0, Math.min(MAX, value)); }
    public void shutdown() { scheduler.shutdown(); save(); }
    public Map<UUID, Long> getBalances() { return balances; }
    public PriceRegistry getPrices() { return prices; }
    public com.reazip.economycraft.shop.ShopManager getShop() { return shop; }
    public com.reazip.economycraft.orders.OrderManager getOrders() { return orders; }

    private static final class UserCacheEntry { String name; String uuid; }
    private record DailySellData(long day, long amount) {}
}

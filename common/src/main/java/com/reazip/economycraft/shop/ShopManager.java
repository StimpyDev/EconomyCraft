package com.reazip.economycraft.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.util.IdentityCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Manages shop listings and deliveries I/O. */
public class ShopManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final MinecraftServer server;
    private final Path file;
    
    private final Map<Integer, ShopListing> listings = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemStack>> deliveries = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    
    private int nextId = 1;
    private volatile boolean isDirty = false;

    public ShopManager(MinecraftServer server) {
        this.server = server;
        this.file = server.getFile("config/economycraft/data/shop.json");
        
        try { 
            Files.createDirectories(file.getParent()); 
        } catch (IOException ignored) {}
        
        load();
    }

    public Collection<ShopListing> getListings() {
        return listings.values();
    }

    public ShopListing getListing(int id) {
        return listings.get(id);
    }

    public void addListing(ShopListing listing) {
        listing.id = nextId++;
        listings.put(listing.id, listing);
        markDirtyAndNotify();
    }

    public ShopListing removeListing(int id) {
        ShopListing l = listings.remove(id);
        if (l != null) markDirtyAndNotify();
        return l;
    }

    public void addDelivery(UUID player, ItemStack stack) {
        if (stack.isEmpty()) return;
        deliveries.computeIfAbsent(player, k -> new ArrayList<>()).add(stack.copy());
        this.isDirty = true;
    }

    public List<ItemStack> getDeliveries(UUID player) {
        List<ItemStack> list = deliveries.get(player);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public List<ItemStack> claimDeliveries(UUID player) {
        List<ItemStack> list = deliveries.remove(player);
        if (list != null) this.isDirty = true;
        return list;
    }
    
    public void removeDelivery(UUID player, ItemStack stack) {
        List<ItemStack> list = deliveries.get(player);
        if (list != null) {
            if (list.remove(stack)) {
                if (list.isEmpty()) deliveries.remove(player);
                this.isDirty = true;
            }
        }
    }

    public boolean hasDeliveries(UUID player) {
        List<ItemStack> list = deliveries.get(player);
        return list != null && !list.isEmpty();
    }

    private void markDirtyAndNotify() {
        this.isDirty = true;
        notifyListeners();
    }

    public void load() {
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;

            nextId = root.has("nextId") ? root.get("nextId").getAsInt() : 1;
            listings.clear();
            deliveries.clear();

            var registry = server.registryAccess();

            if (root.has("listings")) {
                for (var el : root.getAsJsonArray("listings")) {
                    ShopListing l = ShopListing.load(el.getAsJsonObject(), registry);
                    listings.put(l.id, l);
                }
            }

            if (root.has("deliveries")) {
                JsonObject dObj = root.getAsJsonObject("deliveries");
                var ops = RegistryOps.create(JsonOps.INSTANCE, registry);

                for (String key : dObj.keySet()) {
                    UUID id = UUID.fromString(key);
                    JsonArray arr = dObj.getAsJsonArray(key);
                    List<ItemStack> list = new ArrayList<>(arr.size());
                    
                    for (var sEl : arr) {
                        ItemStack stack = ItemStack.CODEC.parse(ops, sEl.getAsJsonObject().get("stack"))
                                .result()
                                .orElse(ItemStack.EMPTY);
                        
                        if (!stack.isEmpty()) list.add(stack);
                    }
                    deliveries.put(id, list);
                }
            }
        } catch (Exception e) {
            EconomyCraft.LOGGER.error("Fout bij laden shop.json", e);
        } 
    }

    public synchronized void save() {
        if (!isDirty) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("nextId", nextId);

            var registry = server.registryAccess();
            var ops = RegistryOps.create(JsonOps.INSTANCE, registry);

            JsonArray listArr = new JsonArray();
            for (ShopListing l : listings.values()) {
                listArr.add(l.save(registry));
            }
            root.add("listings", listArr);

            JsonObject dObj = new JsonObject();
            for (var entry : deliveries.entrySet()) {
                JsonArray arr = new JsonArray();
                for (ItemStack s : entry.getValue()) {
                    JsonObject o = new JsonObject();
                    o.add("stack", ItemStack.CODEC.encodeStart(ops, s).result().orElse(new JsonObject()));
                    arr.add(o);
                }
                dObj.add(entry.getKey().toString(), arr);
            }
            root.add("deliveries", dObj);

            Files.writeString(file, GSON.toJson(root));
            this.isDirty = false;
        } catch (IOException e) {
            EconomyCraft.LOGGER.error("Fout bij opslaan shop.json", e);
        }
    }

    public void addListener(Runnable run) { listeners.add(run); }
    public void removeListener(Runnable run) { listeners.remove(run); }
    private void notifyListeners() { listeners.forEach(Runnable::run); }

    public void notifySellerSale(ShopListing listing, ServerPlayer buyer) {
        if (listing == null || buyer == null || listing.seller == null) return;
        ServerPlayer seller = server.getPlayerList().getPlayer(listing.seller);
        if (seller == null) return;

        ItemStack stack = listing.item;
        int amount = stack.getCount();
        String itemName = stack.getHoverName().getString();
        String buyerName = IdentityCompat.of(buyer).name();

        seller.sendSystemMessage(Component.literal("§aVerkocht " + amount + "x " + itemName + 
                                  " naar " + buyerName + " voor " + EconomyCraft.formatMoney(listing.price)));
    }
}

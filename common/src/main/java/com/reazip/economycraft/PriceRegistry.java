package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import com.reazip.economycraft.util.IdentifierCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class PriceRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEFAULT_RESOURCE_PATH = "/assets/economycraft/prices.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<IdentifierCompat.Id, PriceEntry> prices = new LinkedHashMap<>();

    public record ResolvedPrice(IdentifierCompat.Id key, PriceEntry entry) {}

    public PriceRegistry(MinecraftServer server) {
        Path dir = server.getFile("config/economycraft");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("[EconomyCraft] Could not create config directory: {}", dir, e);
        }
        this.file = dir.resolve("prices.json");
        if (Files.notExists(this.file)) {
            createFromBundledDefault();
        } else {
            mergeNewDefaultsFromBundledDefault();
        }
        reload();
    }

    public void reload() {
        this.prices.clear();
        if (Files.notExists(file)) return;

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;

            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                IdentifierCompat.Id id = IdentifierCompat.tryParse(e.getKey());
                if (id == null) continue;

                // Check of het item bestaat in de game OF een geldige virtuele ID is (potions/enchantments)
                boolean isRealItem = IdentifierCompat.registryContainsKey(BuiltInRegistries.ITEM, id);
                if (!isRealItem && !isVirtualPriceId(id)) {
                    continue; 
                }

                JsonObject obj = e.getValue().getAsJsonObject();
                prices.put(id, new PriceEntry(
                        id,
                        getString(obj, "category", "misc"),
                        getInt(obj, "stack", 1),
                        getLong(obj, "unit_buy", 0L),
                        getLong(obj, "unit_sell", 0L)
                ));
            }
            LOGGER.info("[EconomyCraft] Loaded {} prices from {}", prices.size(), file.getFileName());
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load prices.json", ex);
        }
    }

    public void load() { reload(); }

    // --- Verkoop & Status methoden (Nodig voor SellCommand & EconomyManager) ---

    public Long getUnitSell(ItemStack stack) {
        PriceEntry p = get(stack);
        return (p != null && p.unitSell() > 0) ? p.unitSell() : null;
    }

    public Long getUnitBuy(ItemStack stack) {
        PriceEntry p = get(stack);
        return (p != null && p.unitBuy() > 0) ? p.unitBuy() : null;
    }

    public boolean isSellBlockedByDamage(ItemStack stack) {
        return stack != null && stack.isDamageableItem() && stack.getDamageValue() > 0;
    }

    public boolean isSellBlockedByContents(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyItems().iterator().hasNext()) return true;
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle != null && !bundle.isEmpty();
    }

    public Integer getStackSize(ItemStack stack) {
        PriceEntry p = get(stack);
        return (p != null) ? p.stack() : null;
    }

    // --- Resolving ---

    public ResolvedPrice resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (IdentifierCompat.Id key : resolvePriceKeys(stack)) {
            PriceEntry p = prices.get(key);
            if (p != null) return new ResolvedPrice(key, p);
        }
        return null;
    }

    public PriceEntry get(ItemStack stack) {
        ResolvedPrice rp = resolve(stack);
        return rp != null ? rp.entry() : null;
    }

    // --- GUI API (Nodig voor ServerShopUi) ---

    public Collection<String> buyCategories() {
        Set<String> out = new LinkedHashSet<>();
        for (PriceEntry p : prices.values()) {
            if (p.unitBuy() > 0 && p.category() != null) out.add(p.category());
        }
        return out;
    }

    public List<String> buyTopCategories() {
        Set<String> out = new LinkedHashSet<>();
        for (PriceEntry p : prices.values()) {
            if (p.unitBuy() <= 0 || p.category() == null) continue;
            int dot = p.category().indexOf('.');
            out.add(dot > 0 ? p.category().substring(0, dot) : p.category());
        }
        return new ArrayList<>(out);
    }

    public List<String> buySubcategories(String topCategory) {
        Set<String> out = new LinkedHashSet<>();
        String root = topCategory.toLowerCase(Locale.ROOT) + ".";
        for (PriceEntry p : prices.values()) {
            if (p.unitBuy() > 0 && p.category() != null && p.category().toLowerCase(Locale.ROOT).startsWith(root)) {
                out.add(p.category().substring(root.length()));
            }
        }
        return new ArrayList<>(out);
    }

    public List<PriceEntry> buyableByCategory(String category) {
        List<PriceEntry> out = new ArrayList<>();
        for (PriceEntry p : prices.values()) {
            if (p.unitBuy() > 0 && category.equalsIgnoreCase(p.category())) out.add(p);
        }
        return out;
    }

    // --- Potion & Book Logica ---

    private static List<IdentifierCompat.Id> resolvePriceKeys(ItemStack stack) {
        List<IdentifierCompat.Id> out = new ArrayList<>();
        IdentifierCompat.Id itemId = IdentifierCompat.wrap(BuiltInRegistries.ITEM.getKey(stack.getItem()));

        if (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.TIPPED_ARROW)) {
            IdentifierCompat.Id potionId = readPotionId(stack);
            out.addAll(buildVirtualPotionKeys(stack, potionId != null ? potionId : IdentifierCompat.withDefaultNamespace("water")));
        }

        if (stack.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            for (Object2IntMap.Entry<Holder<Enchantment>> e : stored.entrySet()) {
                e.getKey().unwrapKey().map(IdentifierCompat::fromResourceKey).ifPresent(enchId -> {
                    out.add(IdentifierCompat.fromNamespaceAndPath(enchId.namespace(), "enchanted_book_" + enchId.path() + "_" + e.getIntValue()));
                });
            }
        }

        out.add(itemId);
        return out;
    }

    private static IdentifierCompat.Id readPotionId(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return null;
        return contents.potion().flatMap(Holder::unwrapKey).map(IdentifierCompat::fromResourceKey).orElse(null);
    }

    private static List<IdentifierCompat.Id> buildVirtualPotionKeys(ItemStack stack, IdentifierCompat.Id potionId) {
        String path = potionId.path();
        String form = stack.is(Items.SPLASH_POTION) ? "splash_potion" : 
                     stack.is(Items.LINGERING_POTION) ? "lingering_potion" : 
                     stack.is(Items.TIPPED_ARROW) ? "arrow" : "potion";

        if (path.equals("water") || path.equals("awkward") || path.equals("mundane") || path.equals("thick")) {
            String key = (path.equals("water") && form.equals("potion")) ? "water_bottle" : form + "_of_" + path + "_1";
            if (path.equals("water") && form.equals("splash_potion")) key = "splash_water_bottle";
            if (path.equals("water") && form.equals("lingering_potion")) key = "lingering_water_bottle";
            return List.of(IdentifierCompat.withDefaultNamespace(key));
        }

        String effect = path.replace("long_", "").replace("strong_", "");
        String suffix = path.startsWith("long_") ? "_extended" : path.startsWith("strong_") ? "_2" : "_1";
        if (effect.equals("turtle_master")) effect = "the_turtle_master";

        String finalKey = form + "_of_" + effect + suffix;
        return suffix.equals("_1") ? 
            List.of(IdentifierCompat.withDefaultNamespace(finalKey), IdentifierCompat.withDefaultNamespace(form + "_of_" + effect)) : 
            List.of(IdentifierCompat.withDefaultNamespace(finalKey));
    }

    private static boolean isVirtualPriceId(IdentifierCompat.Id id) {
        String p = id.path();
        return p.contains("potion_of_") || p.contains("arrow_of_") || 
               p.startsWith("enchanted_book_") || p.contains("water_bottle");
    }

    // --- Helpers ---

    private void createFromBundledDefault() {
        try (InputStream in = PriceRegistry.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in != null) Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {}
    }

    private void mergeNewDefaultsFromBundledDefault() {
        try (InputStream in = PriceRegistry.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) return;
            JsonObject defaults = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject userRoot = GSON.fromJson(json, JsonObject.class);
            boolean changed = false;
            for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
                if (!userRoot.has(e.getKey())) { userRoot.add(e.getKey(), e.getValue()); changed = true; }
            }
            if (changed) Files.writeString(file, GSON.toJson(userRoot), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static String getString(JsonObject obj, String k, String f) { return obj.has(k) ? obj.get(k).getAsString() : f; }
    private static int getInt(JsonObject obj, String k, int f) { return obj.has(k) ? obj.get(k).getAsInt() : f; }
    private static long getLong(JsonObject obj, String k, long f) { return obj.has(k) ? obj.get(k).getAsLong() : f; }

    public record PriceEntry(IdentifierCompat.Id id, String category, int stack, long unitBuy, long unitSell) {}
}

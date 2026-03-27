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
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
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
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
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

        if (Files.notExists(file)) {
            LOGGER.warn("[EconomyCraft] prices.json not found at {} (prices map will be empty).", file);
            return;
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) {
                LOGGER.error("[EconomyCraft] prices.json is empty or invalid JSON: {}", file);
                return;
            }

            int missingItemCount = 0;
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String key = e.getKey();
                IdentifierCompat.Id id = IdentifierCompat.tryParse(key);
                if (id == null) {
                    LOGGER.warn("[EconomyCraft] Invalid item id in prices.json: {}", key);
                    continue;
                }

                boolean isRealItem = IdentifierCompat.registryContainsKey(BuiltInRegistries.ITEM, id);
                boolean isVirtual = isVirtualPriceId(id);

                if (!isRealItem && !isVirtual) {
                    missingItemCount++;
                    continue;
                }

                JsonElement el = e.getValue();
                if (el == null || !el.isJsonObject()) {
                    LOGGER.warn("[EconomyCraft] Invalid entry for {} (expected object).", key);
                    continue;
                }

                JsonObject obj = el.getAsJsonObject();
                String category = getString(obj, "category", "misc");
                int stack = getInt(obj, "stack", 1);
                long unitBuy = getLong(obj, "unit_buy", 0L);
                long unitSell = getLong(obj, "unit_sell", 0L);

                PriceEntry entry = new PriceEntry(id, category, stack, unitBuy, unitSell);
                prices.put(id, entry);
            }

            if (missingItemCount > 0) {
                LOGGER.warn("[EconomyCraft] Skipped {} price entries for items not present in this server version.", missingItemCount);
            }

            LOGGER.info("[EconomyCraft] Loaded {} price entries from {}", prices.size(), file);
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load prices.json from {}", file, ex);
        }
    }

    public PriceEntry get(ItemStack stack) {
        ResolvedPrice rp = resolve(stack);
        return rp != null ? rp.entry() : null;
    }

    public ResolvedPrice resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        List<IdentifierCompat.Id> keys = resolvePriceKeys(stack);
        for (IdentifierCompat.Id key : keys) {
            PriceEntry p = prices.get(key);
            if (p != null) return new ResolvedPrice(key, p);
        }
        return null;
    }

    // --- Price Getters ---
    public Long getUnitBuy(ItemStack stack) {
        PriceEntry p = get(stack);
        return (p != null && p.unitBuy() > 0) ? p.unitBuy() : null;
    }

    public Long getUnitSell(ItemStack stack) {
        PriceEntry p = get(stack);
        return (p != null && p.unitSell() > 0) ? p.unitSell() : null;
    }

    public Integer getStackSize(ItemStack stack) {
        PriceEntry p = get(stack);
        return (p != null && p.stack() > 0) ? p.stack() : null;
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

    // --- Key Resolution Logic ---
    private static List<IdentifierCompat.Id> resolvePriceKeys(ItemStack stack) {
        List<IdentifierCompat.Id> out = new ArrayList<>(4);
        IdentifierCompat.Id itemId = IdentifierCompat.wrap(BuiltInRegistries.ITEM.getKey(stack.getItem()));

        // Check for Potions / Tipped Arrows
        if (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.TIPPED_ARROW)) {
            IdentifierCompat.Id potionId = readPotionId(stack);
            if (potionId != null) {
                out.addAll(buildVirtualPotionKeys(stack, potionId));
            } else {
                out.addAll(buildVirtualPotionKeys(stack, IdentifierCompat.withDefaultNamespace("water")));
            }
        }

        // Check for Enchanted Books
        if (stack.is(Items.ENCHANTED_BOOK)) {
            ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            for (Object2IntMap.Entry<Holder<Enchantment>> e : stored.entrySet()) {
                Holder<Enchantment> holder = e.getKey();
                int level = e.getIntValue();
                if (level <= 0) continue;
                IdentifierCompat.Id enchId = holder.unwrapKey().map(IdentifierCompat::fromResourceKey).orElse(null);
                if (enchId == null) continue;
                
                String base = "enchanted_book_" + enchId.path() + "_" + level;
                out.add(IdentifierCompat.fromNamespaceAndPath(enchId.namespace(), base));

                // Vanilla aliases for Curses
                if ("binding_curse".equals(enchId.path())) {
                    out.add(IdentifierCompat.fromNamespaceAndPath(enchId.namespace(), "enchanted_book_curse_of_binding_" + level));
                } else if ("vanishing_curse".equals(enchId.path())) {
                    out.add(IdentifierCompat.fromNamespaceAndPath(enchId.namespace(), "enchanted_book_curse_of_vanishing_" + level));
                }
            }
        }

        out.add(itemId); // Always fallback to the base item ID
        return out;
    }

    private static IdentifierCompat.Id readPotionId(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return null;

        return contents.potion()
                .flatMap(Holder::unwrapKey)
                .map(IdentifierCompat::fromResourceKey)
                .orElse(null);
    }

    private static List<IdentifierCompat.Id> buildVirtualPotionKeys(ItemStack stack, IdentifierCompat.Id potionId) {
        String path = potionId.path();
        String form = stack.is(Items.SPLASH_POTION) ? "splash_potion" :
                     stack.is(Items.LINGERING_POTION) ? "lingering_potion" :
                     stack.is(Items.TIPPED_ARROW) ? "arrow" : "potion";

        // Special water/awkward bottles
        if (path.equals("water") || path.equals("awkward") || path.equals("mundane") || path.equals("thick")) {
            if (path.equals("water")) {
                if (form.equals("potion")) return List.of(IdentifierCompat.withDefaultNamespace("water_bottle"));
                if (form.equals("splash_potion")) return List.of(IdentifierCompat.withDefaultNamespace("splash_water_bottle"));
                if (form.equals("lingering_potion")) return List.of(IdentifierCompat.withDefaultNamespace("lingering_water_bottle"));
            }
            return List.of(IdentifierCompat.withDefaultNamespace(form + "_of_" + path + "_1"));
        }

        String effect = path;
        String suffix = "_1";

        if (effect.startsWith("long_")) {
            effect = effect.substring(5);
            suffix = "_extended";
        } else if (effect.startsWith("strong_")) {
            effect = effect.substring(7);
            suffix = "_2";
        }

        if (effect.equals("turtle_master")) effect = "the_turtle_master";

        String finalKey = form + "_of_" + effect + suffix;
        if (suffix.equals("_1")) {
            return List.of(
                IdentifierCompat.withDefaultNamespace(finalKey),
                IdentifierCompat.withDefaultNamespace(form + "_of_" + effect)
            );
        }
        return List.of(IdentifierCompat.withDefaultNamespace(finalKey));
    }

    private static boolean isVirtualPriceId(IdentifierCompat.Id id) {
        if (!"minecraft".equals(id.namespace())) return false;
        String p = id.path();
        return p.contains("_potion_of_") || p.contains("arrow_of_") || 
               p.startsWith("enchanted_book_") || p.endsWith("_water_bottle") || p.equals("water_bottle");
    }

    // --- File & JSON Utilities ---
    private void createFromBundledDefault() {
        try (InputStream in = PriceRegistry.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) {
                Files.writeString(file, "{}", StandardCharsets.UTF_8);
                return;
            }
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("[EconomyCraft] Failed to create prices.json", e);
        }
    }

    private void mergeNewDefaultsFromBundledDefault() {
        JsonObject defaults = readBundledDefaultJson();
        if (defaults == null) return;

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject userRoot = GSON.fromJson(json, JsonObject.class);
            if (userRoot == null) userRoot = new JsonObject();

            boolean changed = false;
            for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
                if (!userRoot.has(e.getKey())) {
                    userRoot.add(e.getKey(), e.getValue().deepCopy());
                    changed = true;
                }
            }

            if (changed) Files.writeString(file, GSON.toJson(userRoot), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            backupBrokenConfig();
            createFromBundledDefault();
        }
    }

    private JsonObject readBundledDefaultJson() {
        try (InputStream in = PriceRegistry.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) return null;
            return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) { return null; }
    }

    private void backupBrokenConfig() {
        try {
            if (Files.exists(file)) {
                Path backup = file.resolveSibling("prices.json.broken-" + System.currentTimeMillis());
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {}
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        return (obj.has(key) && obj.get(key).isJsonPrimitive()) ? obj.get(key).getAsString() : fallback;
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        try { return obj.has(key) ? obj.get(key).getAsInt() : fallback; } catch (Exception e) { return fallback; }
    }

    private static long getLong(JsonObject obj, String key, long fallback) {
        try { return obj.has(key) ? obj.get(key).getAsLong() : fallback; } catch (Exception e) { return fallback; }
    }

    public record PriceEntry(IdentifierCompat.Id id, String category, int stack, long unitBuy, long unitSell) { }
}

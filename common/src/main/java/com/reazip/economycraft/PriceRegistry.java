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

    public PriceEntry get(IdentifierCompat.Id id) {
        return prices.get(id);
    }

    public PriceEntry get(ItemStack stack) {
        ResolvedPrice rp = resolve(stack);
        return rp != null ? rp.entry() : null;
    }

    public ResolvedPrice resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        // Try every resolved key (specific -> generic)
        for (IdentifierCompat.Id key : resolvePriceKeys(stack)) {
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

    public boolean canBuyUnit(ItemStack stack) {
        PriceEntry p = get(stack);
        return p != null && p.unitBuy() > 0;
    }

    public boolean canSellUnit(ItemStack stack) {
        PriceEntry p = get(stack);
        return p != null && p.unitSell() > 0;
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

    // --- Category Helpers ---
    public Collection<PriceEntry> all() {
        return Collections.unmodifiableCollection(prices.values());
    }

    public Set<String> categories() {
        Set<String> out = new LinkedHashSet<>();
        for (PriceEntry p : prices.values()) out.add(p.category());
        return out;
    }

    public Set<String> buyCategories() {
        Set<String> out = new LinkedHashSet<>();
        for (PriceEntry p : prices.values()) {
            if (p.unitBuy() > 0) out.add(p.category());
        }
        return out;
    }

    public List<PriceEntry> byCategory(String category) {
        if (category == null) return List.of();
        String c = category.trim().toLowerCase(Locale.ROOT);
        List<PriceEntry> out = new ArrayList<>();
        for (PriceEntry p : prices.values()) {
            if (p.category() != null && p.category().trim().toLowerCase(Locale.ROOT).equals(c)) {
                out.add(p);
            }
        }
        return out;
    }

    public List<PriceEntry> buyableByCategory(String category) {
        if (category == null) return List.of();
        String c = category.trim().toLowerCase(Locale.ROOT);
        List<PriceEntry> out = new ArrayList<>();
        for (PriceEntry p : prices.values()) {
            if (p.unitBuy() <= 0) continue;
            if (p.category() != null && p.category().trim().toLowerCase(Locale.ROOT).equals(c)) {
                out.add(p);
            }
        }
        return out;
    }

    public List<String> buyTopCategories() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (PriceEntry p : prices.values()) {
            if (p.unitBuy() <= 0 || p.category() == null) continue;
            String cat = p.category();
            int dot = cat.indexOf('.');
            out.add(dot > 0 ? cat.substring(0, dot) : cat);
        }
        return new ArrayList<>(out);
    }

    public List<String> buySubcategories(String topCategory) {
        if (topCategory == null || topCategory.isBlank()) return List.of();
        String root = topCategory.trim().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (PriceEntry p : prices.values()) {
            if (p.unitBuy() <= 0 || p.category() == null) continue;
            String cat = p.category().trim();
            int dot = cat.indexOf('.');
            if (dot <= 0 || dot >= cat.length() - 1) continue;
            if (cat.substring(0, dot).toLowerCase(Locale.ROOT).equals(root)) {
                out.add(cat.substring(dot + 1));
            }
        }
        return new ArrayList<>(out);
    }

    // --- File Handling ---
    private void createFromBundledDefault() {
        try (InputStream in = PriceRegistry.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.error("[EconomyCraft] Default prices resource not found. Creating empty {}", file);
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
            int added = 0;
            for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
                if (!userRoot.has(e.getKey())) {
                    userRoot.add(e.getKey(), e.getValue().deepCopy());
                    added++;
                }
            }
            if (added > 0) Files.writeString(file, GSON.toJson(userRoot), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            backupBrokenConfig();
            createFromBundledDefault();
        }
    }

    private JsonObject readBundledDefaultJson() {
        try (InputStream in = PriceRegistry.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) return null;
            return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private void backupBrokenConfig() {
        try {
            if (Files.exists(file)) {
                Path backup = file.resolveSibling("prices.json.broken-" + System.currentTimeMillis());
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {}
    }

    // --- Virtual ID Logic ---

    private static boolean isVirtualPriceId(IdentifierCompat.Id id) {
        if (!"minecraft".equals(id.namespace())) return false;
        String p = id.path();
        return p.contains("_potion") || 
               p.contains("arrow_of_") || 
               p.contains("water_bottle") || 
               p.startsWith("enchanted_book_");
    }

    private static boolean looksLikeEnchantedBookKey(String path) {
        String rest = path.substring("enchanted_book_".length());
        int lastUnderscore = rest.lastIndexOf('_');
        if (lastUnderscore <= 0 || lastUnderscore >= rest.length() - 1) return false;
        try {
            return Integer.parseInt(rest.substring(lastUnderscore + 1)) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

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
                int level = e.getIntValue();
                if (level <= 0) continue;
                e.getKey().unwrapKey().ifPresent(key -> {
                    IdentifierCompat.Id enchId = IdentifierCompat.fromResourceKey(key);
                    String base = "enchanted_book_" + enchId.path() + "_" + level;
                    out.add(IdentifierCompat.fromNamespaceAndPath(enchId.namespace(), base));
                    
                    // Legacy curse names
                    if (enchId.path().equals("binding_curse")) out.add(IdentifierCompat.withDefaultNamespace("enchanted_book_curse_of_binding_" + level));
                    if (enchId.path().equals("vanishing_curse")) out.add(IdentifierCompat.withDefaultNamespace("enchanted_book_curse_of_vanishing_" + level));
                });
            }
        }
        
        out.add(itemId);
        return out;
    }

    private static IdentifierCompat.Id readPotionId(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return null;
        return contents.potion().map(h -> IdentifierCompat.wrap(BuiltInRegistries.POTION.getKey(h.value()))).orElse(null);
    }

    private static List<IdentifierCompat.Id> buildVirtualPotionKeys(ItemStack stack, IdentifierCompat.Id potionId) {
        String potionPath = potionId.path();
        String form = stack.is(Items.SPLASH_POTION) ? "splash" : 
                      stack.is(Items.LINGERING_POTION) ? "lingering" : 
                      stack.is(Items.TIPPED_ARROW) ? "arrow" : "potion";

        if (potionPath.equals("water")) {
            String key = switch (form) {
                case "splash" -> "splash_water_bottle";
                case "lingering" -> "lingering_water_bottle";
                case "arrow" -> "arrow_of_water_1";
                default -> "water_bottle";
            };
            return List.of(IdentifierCompat.withDefaultNamespace(key));
        }

        if (potionPath.equals("awkward") || potionPath.equals("mundane") || potionPath.equals("thick")) {
            return List.of(IdentifierCompat.withDefaultNamespace(potionPath + (form.equals("potion") ? "_potion" : "_" + form + "_potion")));
        }

        String effect = potionPath;
        String suffix = "_1";
        if (effect.startsWith("long_")) { effect = effect.substring(5); suffix = "_extended"; }
        else if (effect.startsWith("strong_")) { effect = effect.substring(7); suffix = "_2"; }

        if (effect.equals("turtle_master")) effect = "the_turtle_master";

        String base = form.equals("potion") ? "potion_of_" + effect : form + "_potion_of_" + effect;
        if (form.equals("arrow")) base = "arrow_of_" + effect;

        // Try specific suffix first, then try the bare base
        return List.of(
            IdentifierCompat.withDefaultNamespace(base + suffix),
            IdentifierCompat.withDefaultNamespace(base)
        );
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

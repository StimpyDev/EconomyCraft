package com.reazip.economycraft.shop;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Listing for one item in the shop. */
public class ShopListing {
    public int id;
    public UUID seller;
    public ItemStack item = ItemStack.EMPTY;
    public long price;

    public JsonObject save(HolderLookup.Provider provider) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        if (seller != null) obj.addProperty("Verkoper", seller.toString());
        obj.addProperty("prijs", price);

        var ops = RegistryOps.create(JsonOps.INSTANCE, provider);
        ItemStack.CODEC.encodeStart(ops, item)
                .result()
                .ifPresent(stackEl -> obj.add("stack", stackEl));
        
        return obj;
    }

    public static ShopListing load(JsonObject obj, HolderLookup.Provider provider) {
        ShopListing l = new ShopListing();
        
        if (obj.has("id")) {
            l.id = obj.get("id").getAsInt();
        }
        
        if (obj.has("Verkoper")) {
            l.seller = UUID.fromString(obj.get("Verkoper").getAsString());
        }
        
        if (obj.has("prijs")) {
            l.price = obj.get("prijs").getAsLong();
        }

        if (obj.has("stack")) {
            var ops = RegistryOps.create(JsonOps.INSTANCE, provider);
            l.item = ItemStack.CODEC.parse(ops, obj.get("stack"))
                    .result()
                    .orElse(ItemStack.EMPTY);
        } else {
            l.item = ItemStack.EMPTY;
        }
        
        return l;
    }
}

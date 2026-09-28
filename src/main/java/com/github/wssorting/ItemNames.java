package com.github.wssorting;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Name matching between "#code" name tags and item types. */
final class ItemNames {

    private final Map<Material, String[]> names = new EnumMap<>(Material.class);

    /**
     * The code of a "#code" name tag, normalized, or null if the item is not a valid tag.
     */
    static String tagCode(ItemStack tag) {
        ItemMeta meta = tag.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }
        Component name = meta.displayName();
        if (name == null) {
            return null;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(name).trim();
        if (!text.startsWith("#")) {
            return null;
        }
        String code = normalize(text.substring(1));
        return code.isEmpty() ? null : code;
    }

    /** True if the code appears in the item's English name or its id (e.g. "oak sapling"). */
    boolean matches(Material type, String code) {
        for (String name : names.computeIfAbsent(type, ItemNames::namesOf)) {
            if (name.contains(code)) {
                return true;
            }
        }
        return false;
    }

    private static String[] namesOf(Material type) {
        String id = normalize(type.getKey().getKey());
        String english = null;
        try {
            String key = type.translationKey();
            String rendered = PlainTextComponentSerializer.plainText().serialize(Component.translatable(key));
            if (!rendered.equals(key)) {
                english = normalize(rendered);
            }
        } catch (RuntimeException ignored) {
            // fall back to the id only
        }
        return english == null || english.equals(id) ? new String[] {id} : new String[] {english, id};
    }

    static String normalize(String s) {
        return s.replace('_', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}

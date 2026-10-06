package com.github.gemssorting;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Name matching between "#code" / ".group" name tags and item types. */
final class ItemNames {

    private final Map<Material, String[]> names = new EnumMap<>(Material.class);

    /**
     * A receiver name tag: "#code" matches item names, ".group" matches a group from the web editor.
     * With a "+" in front ("+.group", "+#code") the chest is stock: it gets items only when the
     * shelves with the same tag are full, and refills them when items are taken out.
     */
    record Tag(Kind kind, String code, boolean stock) {
        /** The same tag on a shelf (stock == false). */
        Tag shelf() {
            return new Tag(kind, code, false);
        }
    }

    enum Kind { NAME, GROUP }

    /**
     * The "#code", ".group", "+#code" or "+.group" of a name tag, normalized, or null if the item
     * is not a valid tag.
     */
    static Tag tag(ItemStack tag) {
        ItemMeta meta = tag.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }
        Component name = meta.displayName();
        if (name == null) {
            return null;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(name).trim();
        boolean stock = text.startsWith("+");
        if (stock) {
            text = text.substring(1).trim();
        }
        Kind kind;
        if (text.startsWith("#")) {
            kind = Kind.NAME;
        } else if (text.startsWith(".")) {
            kind = Kind.GROUP;
        } else {
            return null;
        }
        String code = normalize(text.substring(1));
        return code.isEmpty() ? null : new Tag(kind, code, stock);
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

    /** The English name the server renders for this item, or its id in title case. */
    static String englishName(Material type) {
        try {
            String key = type.translationKey();
            String rendered = PlainTextComponentSerializer.plainText().serialize(Component.translatable(key));
            if (!rendered.equals(key)) {
                return rendered;
            }
        } catch (RuntimeException ignored) {
            // fall back to the id
        }
        StringBuilder name = new StringBuilder();
        for (String word : type.getKey().getKey().split("_")) {
            if (!word.isEmpty()) {
                name.append(name.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return name.toString();
    }

    static String normalize(String s) {
        return s.replace('_', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}

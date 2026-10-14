package com.github.gemssorting;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * Tidies a container like the sort button of Inventory Profiles Next with its default rule
 * ("@creative_menu_order", creative sort order SEARCH_TAB): partial stacks of the same item are
 * merged, then items are packed from the first slot ordered by
 * <ol>
 *   <li>custom name: items without one first, then the renamed ones by name;</li>
 *   <li>position in the creative inventory search tab (items missing from it come first, like IPN);</li>
 *   <li>enchantments, damage, display name and potion type.</li>
 * </ol>
 * Items with different data are never merged.
 */
final class InventorySorter {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** Position of each item in the creative search tab; null until {@link #loadCreativeOrder} works. */
    private static volatile Map<Material, Integer> searchTab;

    private InventorySorter() {}

    /**
     * Reads the creative search tab order from the server (the same list the client builds), via
     * the Mojang-mapped server classes. Without it items are ordered by their internal order.
     */
    static void loadCreativeOrder(Server server, Logger logger) {
        try {
            Object minecraft = server.getClass().getMethod("getServer").invoke(server);
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            Object worldData = serverClass.getMethod("getWorldData").invoke(minecraft);
            Object features = Class.forName("net.minecraft.world.level.storage.WorldData")
                    .getMethod("enabledFeatures").invoke(worldData);
            Object registries = serverClass.getMethod("registryAccess").invoke(minecraft);
            Class<?> tabs = Class.forName("net.minecraft.world.item.CreativeModeTabs");
            tabs.getMethod("tryRebuildTabContents",
                            Class.forName("net.minecraft.world.flag.FeatureFlagSet"), boolean.class,
                            Class.forName("net.minecraft.core.HolderLookup$Provider"))
                    .invoke(null, features, false, registries);
            Object search = tabs.getMethod("searchTab").invoke(null);
            Collection<?> stacks = (Collection<?>) Class.forName("net.minecraft.world.item.CreativeModeTab")
                    .getMethod("getSearchTabDisplayItems").invoke(search);
            Method toBukkit = bukkitConverter(server, Class.forName("net.minecraft.world.item.ItemStack"));
            Map<Material, Integer> order = new EnumMap<>(Material.class);
            int index = 0;
            for (Object stack : stacks) {
                order.putIfAbsent(((ItemStack) toBukkit.invoke(null, stack)).getType(), index++);
            }
            if (order.isEmpty()) {
                throw new IllegalStateException("empty creative search tab");
            }
            searchTab = order;
            logger.info("Chest sorting uses the creative inventory order (" + order.size() + " items)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logger.warning("Could not read the creative inventory order (" + e + "); chests are sorted by item id order");
        }
    }

    /** CraftItemStack.asBukkitMirror/asBukkitCopy, whatever parameter type this server version uses. */
    private static Method bukkitConverter(Server server, Class<?> nmsStack) throws ReflectiveOperationException {
        Class<?> craft = Class.forName(server.getClass().getPackageName() + ".inventory.CraftItemStack");
        for (String name : new String[] {"asBukkitMirror", "asBukkitCopy"}) {
            for (Method method : craft.getMethods()) {
                if (method.getName().equals(name) && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(nmsStack)
                        && ItemStack.class.isAssignableFrom(method.getReturnType())) {
                    return method;
                }
            }
        }
        throw new NoSuchMethodException("CraftItemStack.asBukkitMirror/asBukkitCopy");
    }

    private static int searchIndex(ItemStack stack) {
        Map<Material, Integer> order = searchTab;
        return order != null ? order.getOrDefault(stack.getType(), -1) : stack.getType().ordinal();
    }

    private static String customName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        Component name = meta != null && meta.hasCustomName() ? meta.customName() : null;
        return name == null ? "" : PLAIN.serialize(name);
    }

    private static String enchantments(ItemStack stack) {
        StringBuilder text = new StringBuilder();
        stack.getEnchantments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing((Enchantment e) -> e.getKey().toString())))
                .forEach(entry -> text.append(entry.getKey().getKey()).append(entry.getValue()).append(';'));
        return text.toString();
    }

    private static int damage(ItemStack stack) {
        return stack.getItemMeta() instanceof Damageable damageable ? damageable.getDamage() : 0;
    }

    private static String potion(ItemStack stack) {
        if (stack.getItemMeta() instanceof PotionMeta meta) {
            PotionType type = meta.getBasePotionType();
            return type == null ? "" : type.getKey().toString();
        }
        return "";
    }

    private static final Comparator<ItemStack> ORDER = Comparator
            .comparing(InventorySorter::customName)
            .thenComparingInt(InventorySorter::searchIndex)
            .thenComparing(InventorySorter::enchantments)
            .thenComparingInt(InventorySorter::damage)
            .thenComparing(stack -> stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()
                    ? PLAIN.serialize(stack.getItemMeta().displayName()) : "")
            .thenComparing(InventorySorter::potion)
            .thenComparing(Comparator.comparingInt(ItemStack::getAmount).reversed());

    /** Sorts the inventory unless someone is looking at it. Returns true if anything moved. */
    static boolean sort(Inventory inventory) {
        if (inventory == null || !inventory.getViewers().isEmpty()) {
            return false;
        }
        ItemStack[] contents = inventory.getStorageContents();
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            ItemStack rest = stack.clone();
            for (ItemStack into : merged) {
                if (rest.getAmount() == 0) {
                    break;
                }
                int room = into.getMaxStackSize() - into.getAmount();
                if (room > 0 && into.isSimilar(rest)) {
                    int move = Math.min(room, rest.getAmount());
                    into.setAmount(into.getAmount() + move);
                    rest.setAmount(rest.getAmount() - move);
                }
            }
            if (rest.getAmount() > 0) {
                merged.add(rest);
            }
        }
        merged.sort(ORDER); // stable: identical items keep their relative order
        ItemStack[] sorted = new ItemStack[contents.length];
        for (int i = 0; i < merged.size() && i < sorted.length; i++) {
            sorted[i] = merged.get(i);
        }
        if (Arrays.equals(sorted, contents)) {
            return false;
        }
        inventory.setStorageContents(sorted);
        return true;
    }
}

package com.github.gemssorting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Tidies a container like the "sort" button of Inventory Profiles Next: partial stacks of the same
 * item are merged, then items are packed from the first slot in the game's item order (same type
 * together, full stacks first). Items with different data (enchantments, names, contents) are never
 * merged.
 */
final class InventorySorter {

    private InventorySorter() {}

    private static final Comparator<ItemStack> ORDER = Comparator
            .comparingInt((ItemStack stack) -> stack.getType().ordinal())
            .thenComparingInt(stack -> stack.hasItemMeta() ? 1 : 0)
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
        merged.sort(ORDER); // stable: items with the same type and data keep their relative order
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

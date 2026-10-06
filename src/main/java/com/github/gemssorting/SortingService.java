package com.github.gemssorting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * Input chests: a chest (or copper chest, trapped chest, barrel) with an item frame holding an
 * Eye of Ender attached to it.
 * Receiver chests: a frame holding a name tag renamed "#code" (item name) or ".group" (item group).
 * Overflow chests: a frame holding a Carrot on a Stick.
 *
 * Every item in an input chest goes to the receivers whose code appears in its name
 * (longest code first, nearest first), then to the receivers of its group (nearest first),
 * then to the stock chests ("+#code", "+.group") in the same order, then to the overflow chests
 * (nearest first). Whatever does not fit stays in the input chest.
 *
 * Shelves (the chests with a tag without "+") are refilled from the stock chests with the same tag
 * within the stock radius when a player closes them or a hopper takes items out. Every chest
 * that items are moved into or out of is tidied afterwards (see {@link InventorySorter}).
 */
final class SortingService {

    private static final long INPUT_CACHE_MS = 1_000;
    private static final long NETWORK_CACHE_MS = 3_000;
    private static final int CACHE_PRUNE_SIZE = 4_096;

    record ChestKey(UUID world, int x, int y, int z) {}

    private record Target(ChestKey key, Block block, String code, double distSq) {}

    private record Network(List<Target> named, List<Target> grouped,
                           List<Target> namedStock, List<Target> groupedStock, List<Target> overflow) {
        boolean isEmpty() {
            return named.isEmpty() && grouped.isEmpty() && namedStock.isEmpty() && groupedStock.isEmpty()
                    && overflow.isEmpty();
        }
    }

    private static final long REFILL_DELAY_TICKS = 5;

    private record Cached<T>(T value, long expires) {}

    private record StockKey(ChestKey shelf, ItemNames.Tag tag) {}

    private final Plugin plugin;
    private final int radius;
    private final int stockRadius;
    private final GroupStore groups;
    private final ItemNames itemNames = new ItemNames();
    private final Set<ChestKey> pending = new HashSet<>();
    private final Map<ChestKey, Cached<Boolean>> inputCache = new HashMap<>();
    private final Map<ChestKey, Cached<Network>> networkCache = new HashMap<>();
    private final Set<ChestKey> pendingRefill = new HashSet<>();
    private final Map<ChestKey, Cached<List<ItemNames.Tag>>> shelfCache = new HashMap<>();
    private final Map<StockKey, Cached<List<Target>>> stockCache = new HashMap<>();

    SortingService(Plugin plugin, int radius, int stockRadius, GroupStore groups) {
        this.plugin = plugin;
        this.radius = radius;
        this.stockRadius = stockRadius;
        this.groups = groups;
    }

    /** Sorts the chest behind this inventory on the next tick, if it is an input chest. */
    void schedule(Inventory inventory) {
        if (inventory == null
                || (inventory.getType() != InventoryType.CHEST && inventory.getType() != InventoryType.BARREL)) {
            return;
        }
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        Block block = location.getBlock();
        if (!isStorage(block)) {
            return; // chest minecarts, plugin GUIs, ...
        }
        ChestKey key = keyOf(halves(block));
        if (pending.add(key)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                pending.remove(key);
                sort(block, key);
            });
        }
    }

    private void sort(Block block, ChestKey key) {
        if (!isLoaded(block) || !isStorage(block) || !isInput(key, halves(block))) {
            return;
        }
        Inventory input = inventoryOf(block);
        if (input == null) {
            return;
        }
        Network network = network(key, block);
        if (network.isEmpty()) {
            return;
        }

        Map<ChestKey, Optional<Inventory>> resolved = new HashMap<>();
        Set<Inventory> touched = new HashSet<>();
        ItemStack[] contents = input.getStorageContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            ItemStack rest = deliver(stack, network, resolved, touched);
            if (rest == null || rest.getAmount() != stack.getAmount()) {
                contents[i] = rest;
                changed = true;
            }
        }
        if (changed) {
            input.setStorageContents(contents);
            touched.add(input);
            touched.forEach(InventorySorter::sort);
        }
    }

    /** Returns what could not be stored anywhere, or null if everything was stored. */
    private ItemStack deliver(ItemStack stack, Network network, Map<ChestKey, Optional<Inventory>> resolved,
                              Set<Inventory> touched) {
        List<Target> order = new ArrayList<>();
        String group = groups.groupOf(stack.getType());
        // Shelves first, then stock, each by name then by group.
        for (List<Target> named : List.of(network.named(), network.namedStock())) {
            for (Target target : named) {
                if (itemNames.matches(stack.getType(), target.code())) {
                    order.add(target);
                }
            }
            if (group != null) {
                for (Target target : named == network.named() ? network.grouped() : network.groupedStock()) {
                    if (target.code().equals(group)) {
                        order.add(target);
                    }
                }
            }
        }
        order.addAll(network.overflow());

        ItemStack remaining = stack;
        for (Target target : order) {
            Inventory inventory = resolved
                    .computeIfAbsent(target.key(), k -> Optional.ofNullable(inventoryOf(target.block())))
                    .orElse(null);
            if (inventory == null) {
                continue;
            }
            Map<Integer, ItemStack> left = inventory.addItem(remaining.clone());
            ItemStack rest = left.isEmpty() ? null : left.values().iterator().next();
            if (rest == null || rest.getAmount() != remaining.getAmount()) {
                touched.add(inventory);
            }
            if (rest == null) {
                return null;
            }
            remaining = rest;
        }
        return remaining;
    }

    /**
     * Refills the shelf behind this inventory from its stock chests, a few ticks later (so a hopper
     * taking items one by one causes a single refill).
     */
    void scheduleRefill(Inventory inventory) {
        if (inventory == null
                || (inventory.getType() != InventoryType.CHEST && inventory.getType() != InventoryType.BARREL)) {
            return;
        }
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        Block block = location.getBlock();
        if (!isStorage(block)) {
            return;
        }
        ChestKey key = keyOf(halves(block));
        if (pendingRefill.add(key)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                pendingRefill.remove(key);
                refill(block, key);
            }, REFILL_DELAY_TICKS);
        }
    }

    private void refill(Block block, ChestKey key) {
        if (!isLoaded(block) || !isStorage(block)) {
            return;
        }
        List<ItemNames.Tag> tags = shelfTags(key, halves(block));
        if (tags.isEmpty()) {
            return;
        }
        Inventory shelf = inventoryOf(block);
        if (shelf == null || !shelf.getViewers().isEmpty()) {
            return; // someone is using it: the refill happens when they close it
        }
        Set<Inventory> touched = new HashSet<>();
        for (ItemNames.Tag tag : tags) {
            for (Target target : stockOf(key, block, tag)) {
                Inventory stock = inventoryOf(target.block());
                if (stock == null || stock.equals(shelf)) {
                    continue;
                }
                ItemStack[] contents = stock.getStorageContents();
                boolean moved = false;
                for (int i = 0; i < contents.length; i++) {
                    ItemStack stack = contents[i];
                    if (stack == null || stack.getType().isAir() || !belongs(stack, tag)) {
                        continue;
                    }
                    Map<Integer, ItemStack> left = shelf.addItem(stack.clone());
                    int rest = left.isEmpty() ? 0 : left.values().iterator().next().getAmount();
                    if (rest != stack.getAmount()) {
                        moved = true;
                        if (rest == 0) {
                            contents[i] = null;
                        } else {
                            stack.setAmount(rest);
                        }
                    }
                }
                if (moved) {
                    stock.setStorageContents(contents);
                    touched.add(stock);
                    touched.add(shelf);
                }
                if (shelf.firstEmpty() == -1 && !hasRoomFor(shelf, contents, tag)) {
                    break; // full
                }
            }
        }
        touched.forEach(InventorySorter::sort);
    }

    /** True if the item belongs on a shelf with this tag. */
    private boolean belongs(ItemStack stack, ItemNames.Tag tag) {
        return tag.kind() == ItemNames.Kind.NAME
                ? itemNames.matches(stack.getType(), tag.code())
                : tag.code().equals(groups.groupOf(stack.getType()));
    }

    /** With no empty slot left, true if some stack of the stock could still top up a partial stack. */
    private boolean hasRoomFor(Inventory shelf, ItemStack[] stock, ItemNames.Tag tag) {
        for (ItemStack have : shelf.getStorageContents()) {
            if (have == null || have.getAmount() >= have.getMaxStackSize()) {
                continue;
            }
            for (ItemStack stack : stock) {
                if (stack != null && have.isSimilar(stack) && belongs(stack, tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The shelf tags (without "+") on this chest; empty if it is not a shelf. */
    private List<ItemNames.Tag> shelfTags(ChestKey key, List<Block> halves) {
        long now = System.currentTimeMillis();
        Cached<List<ItemNames.Tag>> cached = shelfCache.get(key);
        if (cached != null && cached.expires() > now) {
            return cached.value();
        }
        List<ItemNames.Tag> tags = new ArrayList<>();
        boolean input = false;
        for (Block half : halves) {
            for (Entity entity : framesOn(half)) {
                ItemStack item = ((ItemFrame) entity).getItem();
                if (item.getType() == Material.ENDER_EYE) {
                    input = true;
                } else if (item.getType() == Material.NAME_TAG) {
                    ItemNames.Tag tag = ItemNames.tag(item);
                    if (tag != null && !tag.stock() && !tags.contains(tag)) {
                        tags.add(tag);
                    }
                }
            }
        }
        List<ItemNames.Tag> result = input ? List.of() : List.copyOf(tags);
        prune(shelfCache, now);
        shelfCache.put(key, new Cached<>(result, now + NETWORK_CACHE_MS));
        return result;
    }

    /** Stock chests ("+" tags) with this tag within the stock radius of the shelf, nearest first. */
    private List<Target> stockOf(ChestKey shelfKey, Block shelf, ItemNames.Tag tag) {
        StockKey cacheKey = new StockKey(shelfKey, tag);
        long now = System.currentTimeMillis();
        Cached<List<Target>> cached = stockCache.get(cacheKey);
        if (cached != null && cached.expires() > now) {
            return cached.value();
        }
        Location center = shelf.getLocation().add(0.5, 0.5, 0.5);
        double maxDistSq = (double) stockRadius * stockRadius;
        List<Target> stock = new ArrayList<>();
        Set<ChestKey> seen = new HashSet<>();
        for (Entity entity : shelf.getWorld().getNearbyEntities(
                BoundingBox.of(center, stockRadius + 1, stockRadius + 1, stockRadius + 1),
                entity -> entity instanceof ItemFrame)) {
            ItemStack item = ((ItemFrame) entity).getItem();
            if (item.getType() != Material.NAME_TAG) {
                continue;
            }
            ItemNames.Tag found = ItemNames.tag(item);
            if (found == null || !found.stock() || !found.shelf().equals(tag)) {
                continue;
            }
            Block chest = attachedBlock((ItemFrame) entity);
            if (!isLoaded(chest) || !isStorage(chest)) {
                continue;
            }
            double distSq = chest.getLocation().add(0.5, 0.5, 0.5).distanceSquared(center);
            ChestKey key = keyOf(halves(chest));
            if (distSq <= maxDistSq && !key.equals(shelfKey) && seen.add(key)) {
                stock.add(new Target(key, chest, tag.code(), distSq));
            }
        }
        stock.sort(Comparator.comparingDouble(Target::distSq));
        List<Target> result = List.copyOf(stock);
        prune(stockCache, now);
        stockCache.put(cacheKey, new Cached<>(result, now + NETWORK_CACHE_MS));
        return result;
    }

    private boolean isInput(ChestKey key, List<Block> halves) {
        long now = System.currentTimeMillis();
        Cached<Boolean> cached = inputCache.get(key);
        if (cached != null && cached.expires() > now) {
            return cached.value();
        }
        boolean input = false;
        for (Block half : halves) {
            for (Entity entity : framesOn(half)) {
                if (((ItemFrame) entity).getItem().getType() == Material.ENDER_EYE) {
                    input = true;
                }
            }
        }
        prune(inputCache, now);
        inputCache.put(key, new Cached<>(input, now + INPUT_CACHE_MS));
        return input;
    }

    private Network network(ChestKey self, Block input) {
        long now = System.currentTimeMillis();
        Cached<Network> cached = networkCache.get(self);
        if (cached != null && cached.expires() > now) {
            return cached.value();
        }

        Location center = input.getLocation().add(0.5, 0.5, 0.5);
        double maxDistSq = (double) radius * radius;
        Collection<Entity> frames = input.getWorld().getNearbyEntities(
                BoundingBox.of(center, radius + 1, radius + 1, radius + 1),
                entity -> entity instanceof ItemFrame);

        Set<ChestKey> inputs = new HashSet<>();
        inputs.add(self);
        List<Target> named = new ArrayList<>();
        List<Target> grouped = new ArrayList<>();
        List<Target> namedStock = new ArrayList<>();
        List<Target> groupedStock = new ArrayList<>();
        List<Target> overflow = new ArrayList<>();
        for (Entity entity : frames) {
            ItemFrame frame = (ItemFrame) entity;
            ItemStack item = frame.getItem();
            Material type = item.getType();
            if (type != Material.ENDER_EYE && type != Material.NAME_TAG && type != Material.CARROT_ON_A_STICK) {
                continue;
            }
            Block chest = attachedBlock(frame);
            if (!isLoaded(chest) || !isStorage(chest)) {
                continue;
            }
            double distSq = chest.getLocation().add(0.5, 0.5, 0.5).distanceSquared(center);
            if (distSq > maxDistSq) {
                continue;
            }
            ChestKey key = keyOf(halves(chest));
            if (type == Material.ENDER_EYE) {
                inputs.add(key);
            } else if (type == Material.NAME_TAG) {
                ItemNames.Tag tag = ItemNames.tag(item);
                if (tag != null) {
                    List<Target> list = tag.kind() == ItemNames.Kind.NAME
                            ? (tag.stock() ? namedStock : named)
                            : (tag.stock() ? groupedStock : grouped);
                    list.add(new Target(key, chest, tag.code(), distSq));
                }
            } else {
                overflow.add(new Target(key, chest, null, distSq));
            }
        }

        // Never send items into an input chest (including this one).
        for (List<Target> list : List.of(named, grouped, namedStock, groupedStock, overflow)) {
            list.removeIf(target -> inputs.contains(target.key()));
        }
        Comparator<Target> byName = Comparator.comparingInt((Target target) -> -target.code().length())
                .thenComparingDouble(Target::distSq);
        named.sort(byName);
        namedStock.sort(byName);
        grouped.sort(Comparator.comparingDouble(Target::distSq));
        groupedStock.sort(Comparator.comparingDouble(Target::distSq));
        overflow.sort(Comparator.comparingDouble(Target::distSq));
        Set<ChestKey> seen = new HashSet<>();
        overflow.removeIf(target -> !seen.add(target.key()));

        Network network = new Network(named, grouped, namedStock, groupedStock, overflow);
        prune(networkCache, now);
        networkCache.put(self, new Cached<>(network, now + NETWORK_CACHE_MS));
        return network;
    }

    private static Collection<Entity> framesOn(Block block) {
        return block.getWorld().getNearbyEntities(
                BoundingBox.of(block).expand(0.5),
                entity -> entity instanceof ItemFrame frame && block.equals(attachedBlock(frame)));
    }

    private static Block attachedBlock(ItemFrame frame) {
        return frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
    }

    /** Both blocks of a double chest, or just the block itself. */
    private static List<Block> halves(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest data)
                || data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) {
            return List.of(block);
        }
        BlockFace facing = data.getFacing();
        for (BlockFace side : new BlockFace[] {rotate(facing), rotate(facing).getOppositeFace()}) {
            Block other = block.getRelative(side);
            if (isLoaded(other)
                    && other.getType() == block.getType()
                    && other.getBlockData() instanceof org.bukkit.block.data.type.Chest otherData
                    && otherData.getFacing() == facing
                    && otherData.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE
                    && otherData.getType() != data.getType()) {
                return List.of(block, other);
            }
        }
        return List.of(block);
    }

    private static BlockFace rotate(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private static ChestKey keyOf(List<Block> halves) {
        Block min = halves.get(0);
        for (Block block : halves) {
            if (block.getX() < min.getX() || block.getZ() < min.getZ()) {
                min = block;
            }
        }
        return new ChestKey(min.getWorld().getUID(), min.getX(), min.getY(), min.getZ());
    }

    private static Inventory inventoryOf(Block block) {
        if (!isLoaded(block) || !isStorage(block)) {
            return null;
        }
        // For a double chest this is the inventory of both halves.
        return block.getState(false) instanceof Container container ? container.getInventory() : null;
    }

    /**
     * Blocks that can be input, receiver or overflow storage: chests, trapped chests, every copper
     * chest (including oxidized and waxed ones) and barrels. Ender chests (per player) and shulker
     * boxes (they move around) are left out.
     */
    private static final Set<Material> STORAGE = storageTypes();

    private static Set<Material> storageTypes() {
        Set<Material> types = EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);
        for (Material type : Material.values()) {
            if (!type.isLegacy() && type.isBlock() && type.name().endsWith("COPPER_CHEST")) {
                types.add(type);
            }
        }
        return types;
    }

    private static boolean isStorage(Block block) {
        return STORAGE.contains(block.getType());
    }

    private static boolean isLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static <K, T> void prune(Map<K, Cached<T>> cache, long now) {
        if (cache.size() > CACHE_PRUNE_SIZE) {
            cache.values().removeIf(cached -> cached.expires() <= now);
        }
    }
}

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
 * then to the overflow chests (nearest first). Whatever does not fit stays in the input chest.
 */
final class SortingService {

    private static final long INPUT_CACHE_MS = 1_000;
    private static final long NETWORK_CACHE_MS = 3_000;
    private static final int CACHE_PRUNE_SIZE = 4_096;

    record ChestKey(UUID world, int x, int y, int z) {}

    private record Target(ChestKey key, Block block, String code, double distSq) {}

    private record Network(List<Target> named, List<Target> grouped, List<Target> overflow) {
        boolean isEmpty() {
            return named.isEmpty() && grouped.isEmpty() && overflow.isEmpty();
        }
    }

    private record Cached<T>(T value, long expires) {}

    private final Plugin plugin;
    private final int radius;
    private final GroupStore groups;
    private final ItemNames itemNames = new ItemNames();
    private final Set<ChestKey> pending = new HashSet<>();
    private final Map<ChestKey, Cached<Boolean>> inputCache = new HashMap<>();
    private final Map<ChestKey, Cached<Network>> networkCache = new HashMap<>();

    SortingService(Plugin plugin, int radius, GroupStore groups) {
        this.plugin = plugin;
        this.radius = radius;
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
        ItemStack[] contents = input.getStorageContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            ItemStack rest = deliver(stack, network, resolved);
            if (rest == null || rest.getAmount() != stack.getAmount()) {
                contents[i] = rest;
                changed = true;
            }
        }
        if (changed) {
            input.setStorageContents(contents);
        }
    }

    /** Returns what could not be stored anywhere, or null if everything was stored. */
    private ItemStack deliver(ItemStack stack, Network network, Map<ChestKey, Optional<Inventory>> resolved) {
        List<Target> order = new ArrayList<>();
        for (Target target : network.named()) {
            if (itemNames.matches(stack.getType(), target.code())) {
                order.add(target);
            }
        }
        String group = groups.groupOf(stack.getType());
        if (group != null) {
            for (Target target : network.grouped()) {
                if (target.code().equals(group)) {
                    order.add(target);
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
            if (left.isEmpty()) {
                return null;
            }
            remaining = left.values().iterator().next();
        }
        return remaining;
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
                    (tag.kind() == ItemNames.Kind.NAME ? named : grouped)
                            .add(new Target(key, chest, tag.code(), distSq));
                }
            } else {
                overflow.add(new Target(key, chest, null, distSq));
            }
        }

        // Never send items into an input chest (including this one).
        named.removeIf(target -> inputs.contains(target.key()));
        grouped.removeIf(target -> inputs.contains(target.key()));
        overflow.removeIf(target -> inputs.contains(target.key()));
        named.sort(Comparator.comparingInt((Target target) -> -target.code().length())
                .thenComparingDouble(Target::distSq));
        grouped.sort(Comparator.comparingDouble(Target::distSq));
        overflow.sort(Comparator.comparingDouble(Target::distSq));
        Set<ChestKey> seen = new HashSet<>();
        overflow.removeIf(target -> !seen.add(target.key()));

        Network network = new Network(named, grouped, overflow);
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

    private static <T> void prune(Map<ChestKey, Cached<T>> cache, long now) {
        if (cache.size() > CACHE_PRUNE_SIZE) {
            cache.values().removeIf(cached -> cached.expires() <= now);
        }
    }
}

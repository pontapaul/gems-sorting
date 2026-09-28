package com.github.wssorting;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

/**
 * Anything that may put items into a chest schedules a sort of that chest for the next tick.
 * The service decides whether the chest is actually an input chest.
 */
final class SortingListener implements Listener {

    private final SortingService service;

    SortingListener(SortingService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        service.schedule(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        service.schedule(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        service.schedule(event.getView().getTopInventory());
    }

    /** Hoppers, droppers and hopper minecarts feeding a chest. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        service.schedule(event.getDestination());
    }
}

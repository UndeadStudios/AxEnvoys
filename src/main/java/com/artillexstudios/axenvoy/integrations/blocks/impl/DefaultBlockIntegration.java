package com.artillexstudios.axenvoy.integrations.blocks.impl;

import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axenvoy.integrations.blocks.BlockIntegration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

import java.util.Locale;

public class DefaultBlockIntegration implements BlockIntegration {

    @Override
    public void place(String id, Location location) {
        Scheduler.get().executeAt(location, () -> {
            Block targetBlock = location.getBlock();
            Material material = Material.matchMaterial(id.toUpperCase(Locale.ENGLISH));

            // Ensure the material is valid
            if (material != null) {
                // Place the block at the target location
                targetBlock.setType(material, false);

                // Now check if there's water above the block
                Block blockAbove = targetBlock.getLocation().add(0, 1, 0).getBlock();

                // Check if there's water above the block
                if (blockAbove.getType() == Material.WATER || blockAbove.getType() == Material.KELP) {
                    // If the block is waterloggable, make it waterlogged
                    if (targetBlock.getBlockData() instanceof Waterlogged waterlogged) {
                        waterlogged.setWaterlogged(true); // Set it as waterlogged
                        targetBlock.setBlockData(waterlogged); // Apply the waterlogged state
                    }
                }
            }
        });
    }

    @Override
    public void remove(Location location) {
        Scheduler.get().executeAt(location, () -> {
            Block targetBlock = location.getBlock();

            // If the block is waterlogged, replace it with water first
            if (targetBlock.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
                // Replace the block with water (effectively "removing" the block)
                targetBlock.setType(Material.WATER, false);
            } else {
                // If not waterlogged, remove the block by setting it to air
                targetBlock.setType(Material.AIR, false);
            }
        });
    }
}

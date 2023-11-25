package com.artillexstudios.axenvoy.integrations.blocks.impl;

import com.artillexstudios.axenvoy.integrations.blocks.BlockIntegration;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.Locale;

public class DefaultBlockIntegration implements BlockIntegration {

    @Override
    public void place(String id, Location location) {
        location.getBlock().setType(Material.matchMaterial(id.toUpperCase(Locale.ENGLISH)));
    }

    @Override
    public void remove(Location location) {
        location.getBlock().setType(Material.AIR);
    }
}

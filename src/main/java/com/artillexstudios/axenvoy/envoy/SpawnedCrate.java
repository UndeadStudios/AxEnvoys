package com.artillexstudios.axenvoy.envoy;

import com.artillexstudios.axapi.hologram.Hologram;
import com.artillexstudios.axapi.hologram.HologramLine;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.placeholder.Placeholder;
import com.artillexstudios.axapi.utils.placeholder.StaticPlaceholder;
import com.artillexstudios.axenvoy.AxEnvoyPlugin;
import com.artillexstudios.axenvoy.config.impl.Config;
import com.artillexstudios.axenvoy.event.EnvoyCrateCollectEvent;
import com.artillexstudios.axenvoy.integrations.blocks.BlockIntegration;
import com.artillexstudios.axenvoy.rewards.Reward;
import com.artillexstudios.axenvoy.user.User;
import com.artillexstudios.axenvoy.utils.FallingBlockChecker;
import com.artillexstudios.axenvoy.utils.Utils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public class SpawnedCrate {
    public static final NamespacedKey FIREWORK_KEY = new NamespacedKey(AxEnvoyPlugin.getInstance(), "axenvoy_firework");
    public static final NamespacedKey FALLING_BLOCK_KEY = new NamespacedKey(AxEnvoyPlugin.getInstance(), "axenvoy_falling_block");
    private final Envoy parent;
    private final CrateType handle;
    private Location finishLocation;
    private FallingBlock fallingBlock;
    private Vex vex;
    private Hologram hologram;
    private int tick = 0;
    private int health;
    public SpawnedCrate(@NotNull Envoy parent, @NotNull CrateType handle, @NotNull Location location) {
        this.health = handle.getConfig().REQUIRED_INTERACTION_AMOUNT;
        this.parent = parent;
        this.handle = handle;
        this.finishLocation = location;
        this.parent.getSpawnedCrates().add(this);

        // Using runLater() to schedule the tasks
        Scheduler.get().runLater((task) -> {
            List<Entity> nearby = null;

            boolean chunkLoaded = location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);

            if (handle.getConfig().FALLING_BLOCK_ENABLED && chunkLoaded) {
                nearby = location.getWorld().getNearbyEntities(location, Bukkit.getServer().getSimulationDistance() * 16, Bukkit.getServer().getSimulationDistance() * 16, Bukkit.getServer().getSimulationDistance() * 16).stream().filter(entity -> entity.getType() == EntityType.PLAYER).toList();
            }

            if (!handle.getConfig().FALLING_BLOCK_ENABLED || nearby == null) {
                land(location);
                return;
            }

            // Delay the entity spawning to prevent performance hit
            Location spawnAt = location.clone();
            spawnAt.add(0.5, this.handle.getConfig().FALLING_BLOCK_HEIGHT, 0.5);

            // Use runLater() for delayed Vex spawn
            Scheduler.get().runLater((delayedTask) -> {
                vex = location.getWorld().spawn(spawnAt, Vex.class, ent -> {
                    ent.setInvisible(true);
                    ent.setSilent(true);
                    ent.setInvulnerable(true);
                    ent.setGravity(true);
                    ent.setAware(false);
                    ent.setPersistent(false);
                    if (ent.getEquipment() != null) {
                        ent.getEquipment().clear();
                    }
                });

                vex.setGravity(true);

                // Delay falling block spawn
                Scheduler.get().runLater((delayedTask2) -> {
                    fallingBlock = location.getWorld().spawnFallingBlock(spawnAt, Material.matchMaterial(this.handle.getConfig().FALLING_BLOCK_BLOCK.toUpperCase(Locale.ENGLISH)).createBlockData());
                    vex.addPassenger(fallingBlock);
                    fallingBlock.setPersistent(false);
                    fallingBlock.getPersistentDataContainer().set(FALLING_BLOCK_KEY, PersistentDataType.BYTE, (byte) 0);
                    FallingBlockChecker.addToCheck(this);
                    vex.setVelocity(new Vector(0, handle.getConfig().FALLING_BLOCK_SPEED, 0));
                }, 10); // 10 ticks later
            }, 5); // 5 ticks later
        }, 1); // 1 tick later
    }


    public void land(@NotNull Location location) {
        int radius = 1; // Start search radius
        boolean validLocationFound = false;

        while (!validLocationFound) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    // Clone the location and offset by x and z
                    Location candidateLocation = location.clone().add(x, 0, z);

                    // Check vertically for the first valid solid block
                    Location solidBlockLocation = findValidGround(candidateLocation);
                    if (solidBlockLocation != null) {
                        // A valid location was found
                        validLocationFound = true;
                        this.finishLocation = solidBlockLocation;

                        // Place the envoy
                        BlockIntegration.Companion.place(handle.getConfig().BLOCK_TYPE, solidBlockLocation);
                        this.updateHologram();
                        this.spawnFirework(solidBlockLocation);
                        return;
                    }
                }
            }

            // Expand the search radius if no location is found
            radius++;
            if (radius > 5000) { // Safety limit to prevent infinite loop
                System.out.println("No valid location found within 5000 blocks.");
                return;
            }
        }
    }

    /**
     * Finds a valid ground block starting from the given location.
     * Searches vertically downwards to avoid water-dominant issues.
     *
     * @param location The starting location
     * @return The location of the valid ground block, or null if none is found.
     */
    private Location findValidGround(Location location) {
        // Start from the current Y level and move down
        World world = location.getWorld();
        for (int y = location.getBlockY(); y >= world.getMinHeight(); y--) {
            Location groundLocation = location.clone();
            groundLocation.setY(y);

            Block blockBelow = groundLocation.getBlock();
            Material blockBelowType = blockBelow.getType();

            // Check if the block is valid natural terrain
            boolean isValidBlock = blockBelowType == Material.SAND
                    || blockBelowType == Material.GRASS_BLOCK
                    || blockBelowType == Material.STONE
                    || blockBelowType == Material.COARSE_DIRT
                    || blockBelowType == Material.ANDESITE
                    || blockBelowType == Material.DIORITE
                    || blockBelowType == Material.GRANITE
                    || blockBelowType == Material.DRIPSTONE_BLOCK
                    || blockBelowType == Material.MUD
                    || blockBelowType == Material.DEEPSLATE
                    || blockBelowType == Material.GRAVEL
                    || blockBelowType == Material.DIRT
                    || blockBelowType == Material.PODZOL
                    || blockBelowType == Material.MYCELIUM
                    || blockBelowType == Material.OAK_LEAVES
                    || blockBelowType == Material.BIRCH_LEAVES
                    || blockBelowType == Material.SPRUCE_LEAVES
                    || blockBelowType == Material.JUNGLE_LEAVES
                    || blockBelowType == Material.DARK_OAK_LEAVES
                    || blockBelowType == Material.ACACIA_LEAVES
                    || blockBelowType == Material.CHERRY_LEAVES
                    || blockBelowType == Material.PALE_OAK_LEAVES;

            boolean isInvalidBlock = blockBelowType == Material.WATER
                    || blockBelowType == Material.KELP
                    || blockBelowType == Material.SEAGRASS
                    || blockBelowType == Material.BUBBLE_COLUMN;

            // Ensure the block is solid and not invalid
            if (isValidBlock && !isInvalidBlock) {
                // Ensure the block isn't surrounded by water
                if (isSurroundedByWater(groundLocation)) continue;
                return groundLocation.add(0, 1, 0); // Return the location above the ground
            }
        }
        return null; // No valid ground found
    }

    /**
     * Checks if the block is surrounded by water.
     *
     * @param location The location of the block
     * @return true if surrounded by water, false otherwise
     */
    private boolean isSurroundedByWater(Location location) {
        World world = location.getWorld();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                // Skip the center block
                if (x == 0 && z == 0) continue;

                Location adjacentLocation = location.clone().add(x, 0, z);
                Material adjacentType = world.getBlockAt(adjacentLocation).getType();

                if (adjacentType != Material.WATER) {
                    return false; // Not surrounded by water
                }
            }
        }
        return true; // Fully surrounded by water
    }



                private void updateHologram() {
        if (!handle.getConfig().HOLOGRAM_ENABLED) return;
        if (hologram == null) {
            Location hologramLocation = finishLocation.clone().add(0.5, 0, 0.5);
            hologramLocation.add(0, handle.getConfig().HOLOGRAM_HEIGHT, 0);

            hologram = new Hologram(hologramLocation, Utils.serializeLocation(hologramLocation).replace(";", "-"), 0.3);
            hologram.addPlaceholder(new StaticPlaceholder(string -> string.replace("%max_hits%", String.valueOf(getHandle().getConfig().REQUIRED_INTERACTION_AMOUNT))));
            hologram.addPlaceholder(new Placeholder((player, string) -> string.replace("%hits%", String.valueOf(health))));
            hologram.addLines(handle.getConfig().HOLOGRAM_LINES, HologramLine.Type.TEXT);
        }
    }

    public void claim(@Nullable Player player, Envoy envoy) {
        this.claim(player, envoy, true);
    }

    public void spawnFirework(Location location) {
        if (!this.handle.getConfig().FIREWORK_ENABLED) return;

        Scheduler.get().executeAt(location, () -> {
            Location loc2 = location.clone();
            loc2.add(0.5, 0.5, 0.5);
            Firework fw = (Firework) location.getWorld().spawnEntity(loc2, EntityType.FIREWORK_ROCKET);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(this.handle.getFireworkType()).withColor(org.bukkit.Color.fromRGB(this.handle.getFireworkColor().getRed(), this.handle.getFireworkColor().getGreen(), this.handle.getFireworkColor().getBlue())).build());
            meta.setPower(0);
            fw.setFireworkMeta(meta);
            fw.getPersistentDataContainer().set(FIREWORK_KEY, PersistentDataType.BYTE, (byte) 0);
            fw.detonate();
        });
    }
    private boolean isWater(Location location) {
        Material blockType = location.getBlock().getType();
        return blockType == Material.WATER || blockType == Material.KELP || blockType == Material.SEAGRASS || blockType == Material.BUBBLE_COLUMN;
    }
    public void damage(User user, Envoy envoy) {
        if (user.canCollect(envoy, this.getHandle())) {
            health--;
            if (health == 0) {
                claim(user.getPlayer(), envoy);
            }
        } else {
            Utils.sendMessage(user.getPlayer(), envoy.getConfig().PREFIX, envoy.getConfig().COOLDOWN.replace("%player%", user.getPlayer().getName()).replace("%player_name%", user.getPlayer().getName()).replace("%crate%", getHandle().getConfig().DISPLAY_NAME).replace("%cooldown%", String.valueOf((user.getCollectCooldown(envoy, getHandle()) - System.currentTimeMillis()) / 1000)));
        }
    }


    public void claim(@Nullable Player player, Envoy envoy, boolean remove) {
        if (fallingBlock != null) {
            fallingBlock.remove();
            fallingBlock = null;
        }

        Reward finalReward = null;
        if (player != null) {
            ItemStack item = player.getInventory().getItemInMainHand();
            for (Reward reward : this.handle.getRewards()) {
                if (reward.doesMatchRequired(item)) {
                    finalReward = reward;
                    item.setAmount(item.getAmount() - 1);
                    break;
                }
            }

            if (finalReward != null) {
                finalReward.execute(player, envoy);
            } else {
                for (int i = 0; i < this.handle.getRewardAmount(); i++) {
                    Reward reward = this.handle.randomReward();
                    reward.execute(player, envoy);
                    finalReward = reward;
                }
            }

            Bukkit.getPluginManager().callEvent(new EnvoyCrateCollectEvent(player, this.parent, this, finalReward));

            int cooldown = getHandle().getConfig().COLLECT_COOLDOWN > 0 ? getHandle().getConfig().COLLECT_COOLDOWN : envoy.getConfig().COLLECT_COOLDOWN;
            if (envoy.getConfig().COLLECT_GLOBAL_COOLDOWN) {
                cooldown = envoy.getConfig().COLLECT_COOLDOWN;
            }

            User.USER_MAP.get(player.getUniqueId()).addCrateCooldown(getHandle(), cooldown, envoy);
        }

        BlockIntegration.Companion.remove(getHandle().getConfig().BLOCK_TYPE, finishLocation);
        if (hologram != null) {
            hologram.remove();
        }

        if (remove) {
            this.parent.getSpawnedCrates().remove(this);
        }

        if (envoy != null) {
            boolean broadcast = envoy.getConfig().BROADCAST_COLLECT;
            if (!broadcast) {
                if (handle.getConfig().BROADCAST_COLLECT) {
                    broadcast = true;
                }
            }

            if (broadcast && player != null && !this.parent.getSpawnedCrates().isEmpty()) {
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (!onlinePlayer.getPersistentDataContainer().has(AxEnvoyPlugin.MESSAGE_KEY, PersistentDataType.BYTE)) {
                        Utils.sendMessage(onlinePlayer, envoy.getConfig().PREFIX, envoy.getConfig().COLLECT.replace("%reward%", finalReward.name()).replace("%player_name%", player.getName()).replace("%player%", player.getName()).replace("%crate%", this.handle.getConfig().DISPLAY_NAME).replace("%amount%", String.valueOf(envoy.getSpawnedCrates().size())));
                    }
                }
            }

            if (this.parent.getSpawnedCrates().isEmpty()) {
                envoy.updateNext();
                envoy.setActive(false);

                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    Utils.sendMessage(onlinePlayer, envoy.getConfig().PREFIX, envoy.getConfig().ENDED);
                }
            }
        }
    }

    public void tickFlare() {
        if (!this.handle.getConfig().FLARE_ENABLED) return;
        if (this.handle.getConfig().FLARE_EVERY == 0) return;
        tick++;

        if (tick == this.handle.getConfig().FLARE_EVERY) {
            Scheduler.get().executeAt(finishLocation, () -> {
                if (!getFinishLocation().getWorld().isChunkLoaded(getFinishLocation().getBlockX() >> 4, getFinishLocation().getBlockZ() >> 4))
                    return;
                Location loc2 = finishLocation.clone();
                loc2.add(0.5, 0.5, 0.5);
                Firework fw = (Firework) loc2.getWorld().spawnEntity(loc2, EntityType.FIREWORK_ROCKET);
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(this.handle.getFlareFireworkType()).withColor(org.bukkit.Color.fromRGB(this.handle.getFlareFireworkColor().getRed(), this.handle.getFlareFireworkColor().getGreen(), this.handle.getFlareFireworkColor().getBlue())).build());
                meta.setPower(0);
                fw.setFireworkMeta(meta);
                fw.getPersistentDataContainer().set(FIREWORK_KEY, PersistentDataType.BYTE, (byte) 0);
                fw.detonate();
            });

            tick = 0;
        }
    }

    public Vex getVex() {
        return vex;
    }

    public void setVex(Vex vex) {
        this.vex = vex;
    }

    public CrateType getHandle() {
        return handle;
    }

    public FallingBlock getFallingBlock() {
        return fallingBlock;
    }

    public void setFallingBlock(FallingBlock fallingBlock) {
        this.fallingBlock = fallingBlock;
    }

    public Location getFinishLocation() {
        return finishLocation;
    }
}

package com.palmergames.bukkit.towny.listeners;

import java.util.Collections;
import java.util.List;

import net.citizensnpcs.api.CitizensAPI;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.painting.PaintingBreakByEntityEvent;
import org.bukkit.event.painting.PaintingBreakEvent;
import org.bukkit.event.painting.PaintingPlaceEvent;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Coord;
import com.palmergames.bukkit.towny.object.PlayerCache;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownBlockType;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.object.TownyUniverse;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.bukkit.towny.object.PlayerCache.TownBlockStatus;
import com.palmergames.bukkit.towny.regen.BlockLocation;
import com.palmergames.bukkit.towny.regen.TownyRegenAPI;
import com.palmergames.bukkit.towny.tasks.MobRemovalTimerTask;
import com.palmergames.bukkit.towny.tasks.ProtectionRegenTask;
import com.palmergames.bukkit.towny.utils.CombatUtil;
import com.palmergames.bukkit.towny.war.flagwar.TownyWarConfig;
import com.palmergames.bukkit.util.ArraySort;

/**
 * 
 * @author ElgarL,Shade
 * 
 */
public class TownyEntityListener implements Listener {

	private final Towny plugin;

	public TownyEntityListener(Towny instance) {

		plugin = instance;
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onEntityDamage(EntityDamageEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		Entity attacker = null;
		Entity defender = null;
		Projectile projectile = null;

		if (event instanceof EntityDamageByEntityEvent) {

			EntityDamageByEntityEvent entityEvent = (EntityDamageByEntityEvent) event;
			if (entityEvent.getDamager() instanceof Projectile) {
				projectile = (Projectile) entityEvent.getDamager();
				attacker = projectile.getShooter();
				defender = entityEvent.getEntity();
			} else {
				attacker = entityEvent.getDamager();
				defender = entityEvent.getEntity();
			}
		}

		// Has an attacker and Not wartime
		if ((attacker != null) && (!TownyUniverse.isWarTime())) {

			if (CombatUtil.preventDamageCall(attacker, defender)) {
				// Remove the projectile here so no
				// other events can fire to cause damage
				if (projectile != null)
					projectile.remove();
				event.setCancelled(true);
			}
		}
	}

	/**
	 * 
	 * @param event
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onEntityDeath(EntityDeathEvent event) {

		if (plugin.isError()) {
			return;
		}

		Entity entity = event.getEntity();

		if (entity instanceof Monster) {

			Location loc = entity.getLocation();
			TownyWorld townyWorld = null;

			try {
				townyWorld = TownyUniverse.getDataSource().getWorld(loc.getWorld().getName());

				//remove drops from monster deaths if in an arena plot           
				if (townyWorld.isUsingTowny()) {
					if (townyWorld.getTownBlock(Coord.parseCoord(loc)).getType() == TownBlockType.ARENA)
						event.getDrops().clear();
				}

			} catch (NotRegisteredException e) {
				// Unknown world or not in a town
			}
		}
	}

	/**
	 * Prevent potion damage on players in non PVP areas
	 * 
	 * @param event
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onPotionSplashEvent(PotionSplashEvent event) {

		List<LivingEntity> affectedEntities = (List<LivingEntity>) event.getAffectedEntities();
		ThrownPotion potion = event.getPotion();

		Entity attacker = potion.getShooter();

		// Not Wartime
		if (!TownyUniverse.isWarTime())
			for (LivingEntity defender : affectedEntities)
				if (CombatUtil.preventDamageCall(attacker, defender))
					event.setIntensity(defender, -1.0);

	}

	/**
	 * 
	 * @param event
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onCreatureSpawn(CreatureSpawnEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		if (event.getEntity() instanceof LivingEntity) {
			LivingEntity livingEntity = (LivingEntity) event.getEntity();
			Location loc = event.getLocation();
			Coord coord = Coord.parseCoord(loc);
			TownyWorld townyWorld = null;

			try {
				townyWorld = TownyUniverse.getDataSource().getWorld(loc.getWorld().getName());
			} catch (NotRegisteredException e) {
				// Failed to fetch a world
				return;
			}

			//remove from world if set to remove mobs globally
			if (townyWorld.isUsingTowny())
				if (!townyWorld.hasWorldMobs() && MobRemovalTimerTask.isRemovingWorldEntity(livingEntity)) {
					if (plugin.isCitizens2()) {
						if (!CitizensAPI.getNPCManager().isNPC(livingEntity)) {
							//TownyMessaging.sendDebugMsg("onCreatureSpawn world: Canceled " + event.getEntityType().name() + " from spawning within "+coord.toString()+".");
							event.setCancelled(true);
						}
					} else
						event.setCancelled(true);
				}

			//remove from towns if in the list and set to remove            
			try {
				TownBlock townBlock = townyWorld.getTownBlock(coord);
				if (townyWorld.isUsingTowny() && !townyWorld.isForceTownMobs()) {
					if (!townBlock.getTown().hasMobs() && !townBlock.getPermissions().mobs) {
						if (MobRemovalTimerTask.isRemovingTownEntity(livingEntity)) {
							if (plugin.isCitizens2()) {
								if (!CitizensAPI.getNPCManager().isNPC(livingEntity)) {
									//TownyMessaging.sendDebugMsg("onCreatureSpawn town: Canceled " + event.getEntityType().name() + " from spawning within "+coord.toString()+".");
									event.setCancelled(true);
								}
							} else
								event.setCancelled(true);
						}
					}
				}
			} catch (TownyException x) {
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onEntityInteract(EntityInteractEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		Block block = event.getBlock();
		Entity entity = event.getEntity();

		try {
			TownyWorld townyWorld = TownyUniverse.getDataSource().getWorld(block.getLocation().getWorld().getName());

			// Prevent creatures trampling crops
			if ((townyWorld.isUsingTowny()) && (townyWorld.isDisableCreatureTrample())) {
				if ((block.getType() == Material.SOIL) || (block.getType() == Material.CROPS)) {
					if (entity instanceof Creature)
						event.setCancelled(true);
					return;
				}
			}

		} catch (NotRegisteredException e) {
			// Failed to fetch world
			e.printStackTrace();
		}

	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onEntityChangeBlockEvent(EntityChangeBlockEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		if (!(event.getEntity() instanceof Enderman))
			return;

		try {
			TownyWorld townyWorld = TownyUniverse.getDataSource().getWorld(event.getBlock().getWorld().getName());

			if (!townyWorld.isUsingTowny())
				return;

			if (townyWorld.isEndermanProtect())
				event.setCancelled(true);

		} catch (NotRegisteredException e) {
			// Failed to fetch world
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onEntityExplode(EntityExplodeEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		TownyWorld townyWorld;

		/**
		 * Perform this test outside the block loop so we
		 * only get the world once per explosion.
		 */
		try {
			townyWorld = TownyUniverse.getDataSource().getWorld(event.getLocation().getWorld().getName());

			if (!townyWorld.isUsingTowny())
				return;

		} catch (NotRegisteredException e) {
			// failed to get world so abort
			return;
		}

		Coord coord;
		List<Block> blocks = event.blockList();
		Entity entity = event.getEntity();
		int count = 0;

		// Sort blocks by height (lowest to highest).
		Collections.sort(blocks, ArraySort.getInstance());

		for (Block block : blocks) {
			coord = Coord.parseCoord(block.getLocation());
			count++;

			// Warzones
			if (townyWorld.isWarZone(coord)) {
				if (!TownyWarConfig.isAllowingExplosionsInWarZone()) {
					if (event.getEntity() != null)
						TownyMessaging.sendDebugMsg("onEntityExplode: Canceled " + event.getEntity().getEntityId() + " from exploding within " + coord.toString() + ".");
					event.setCancelled(true);
					return;
				} else {
					if (TownyWarConfig.explosionsBreakBlocksInWarZone()) {
						if (TownyWarConfig.regenBlocksAfterExplosionInWarZone()) {
							// ***********************************
							// TODO

							// On completion, remove TODO from config.yml comments.

							/*
							 * if
							 * (!plugin.getTownyUniverse().hasProtectionRegenTask
							 * (new BlockLocation(block.getLocation()))) {
							 * ProtectionRegenTask task = new
							 * ProtectionRegenTask(plugin.getTownyUniverse(),
							 * block, false);
							 * task.setTaskId(plugin.getServer().getScheduler().
							 * scheduleSyncDelayedTask(plugin, task,
							 * ((TownySettings.getPlotManagementWildRegenDelay()
							 * + count)*20)));
							 * plugin.getTownyUniverse().addProtectionRegenTask(task
							 * );
							 * }
							 */

							// TODO
							// ***********************************
						}

						// Break the block
					} else {
						event.blockList().remove(block);
					}
				}
				return;
			}

			//TODO: expand to protect neutrals during a war
			try {
				TownBlock townBlock = townyWorld.getTownBlock(coord);

				// If explosions are off, or it's wartime and explosions are off and the towns has no nation
				if (townyWorld.isUsingTowny() && !townyWorld.isForceExpl()) {
					if ((!townBlock.getPermissions().explosion) || (TownyUniverse.isWarTime() && TownySettings.isAllowWarBlockGriefing() && !townBlock.getTown().hasNation() && !townBlock.getTown().isBANG())) {
						if (event.getEntity() != null)
							TownyMessaging.sendDebugMsg("onEntityExplode: Canceled " + event.getEntity().getEntityId() + " from exploding within " + coord.toString() + ".");
						event.setCancelled(true);
						return;
					}
				}
			} catch (TownyException x) {
				// Wilderness explosion regeneration
				if (townyWorld.isUsingTowny())
					if (townyWorld.isExpl()) {
						if (townyWorld.isUsingPlotManagementWildRevert() && (entity != null)) {
							if (townyWorld.isProtectingExplosionEntity(entity)) {
								if ((!TownyRegenAPI.hasProtectionRegenTask(new BlockLocation(block.getLocation()))) && (block.getType() != Material.TNT)) {
									ProtectionRegenTask task = new ProtectionRegenTask(plugin.getTownyUniverse(), block, false);
									task.setTaskId(plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, task, ((TownySettings.getPlotManagementWildRegenDelay() + count) * 20)));
									TownyRegenAPI.addProtectionRegenTask(task);
									event.setYield((float) 0.0);
								}
							}
						}
					} else {
						event.setCancelled(true);
						return;
					}
			}
		}
	}

	/**
	 * Prevent fire arrows and charges igniting players when PvP is disabled
	 * 
	 * @param event
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onEntityCombustByEntityEvent(EntityCombustByEntityEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		Entity combuster = event.getCombuster();
		Entity defender = event.getEntity();

		if (combuster instanceof Projectile) {

			LivingEntity attacker = ((Projectile) combuster).getShooter();

			// There is an attacker and Not war time.
			if ((attacker != null) && (!TownyUniverse.isWarTime())) {

				if (CombatUtil.preventDamageCall(attacker, defender)) {
					// Remove the projectile here so no
					// other events can fire to cause damage
					combuster.remove();
					event.setCancelled(true);
				}
			}
		}

	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPaintingBreak(PaintingBreakEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		long start = System.currentTimeMillis();

		if (event instanceof PaintingBreakByEntityEvent) {
			PaintingBreakByEntityEvent evt = (PaintingBreakByEntityEvent) event;
			Painting painting = evt.getPainting();
			Object remover = evt.getRemover();
			WorldCoord worldCoord;

			try {
				String worldName = painting.getWorld().getName();
				TownyWorld townyWorld = TownyUniverse.getDataSource().getWorld(worldName);

				if (!townyWorld.isUsingTowny())
					return;

				worldCoord = new WorldCoord(worldName, Coord.parseCoord(painting.getLocation()));

				if (remover instanceof Player) {
					Player player = (Player) evt.getRemover();

					//Get destroy permissions (updates if none exist)
					boolean bDestroy = TownyUniverse.getCachePermissions().getCachePermission(player, painting.getLocation(), TownyPermission.ActionType.DESTROY);

					PlayerCache cache = plugin.getCache(player);
					cache.updateCoord(worldCoord);
					TownBlockStatus status = cache.getStatus();
					if (status == TownBlockStatus.UNCLAIMED_ZONE && TownyUniverse.getPermissionSource().hasWildOverride(townyWorld, player, painting.getEntityId(), TownyPermission.ActionType.DESTROY))
						return;
					if (!bDestroy)
						event.setCancelled(true);
					if (cache.hasBlockErrMsg())
						TownyMessaging.sendErrorMsg(player, cache.getBlockErrMsg());

				} else if ((remover instanceof Fireball) || (remover instanceof LightningStrike)) {

					try {
						TownBlock townBlock = worldCoord.getTownBlock();

						// Explosions are blocked in this plot
						if ((!townBlock.getPermissions().explosion) && (!townBlock.getWorld().isForceExpl()))
							event.setCancelled(true);

					} catch (NotRegisteredException e) {
						// Not in a town
						if ((!townyWorld.isExpl()) && (!townyWorld.isForceExpl()))
							event.setCancelled(true);
					}

				}

			} catch (NotRegisteredException e1) {
				//TownyMessaging.sendErrorMsg(player, TownySettings.getLangString("msg_err_not_configured"));
				event.setCancelled(true);
				return;
			}

		}

		TownyMessaging.sendDebugMsg("onPaintingBreak took " + (System.currentTimeMillis() - start) + "ms (" + event.getCause().name() + ", " + event.isCancelled() + ")");
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPaintingPlace(PaintingPlaceEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		long start = System.currentTimeMillis();

		Player player = event.getPlayer();
		Painting painting = event.getPainting();

		try {
			TownyWorld townyWorld = TownyUniverse.getDataSource().getWorld(painting.getWorld().getName());

			if (!townyWorld.isUsingTowny())
				return;

			//Get build permissions (updates if none exist)
			boolean bBuild = TownyUniverse.getCachePermissions().getCachePermission(player, painting.getLocation(), TownyPermission.ActionType.BUILD);

			PlayerCache cache = plugin.getCache(player);
			TownBlockStatus status = cache.getStatus();

			if (status == TownBlockStatus.UNCLAIMED_ZONE && TownyUniverse.getPermissionSource().hasWildOverride(townyWorld, player, painting.getEntityId(), TownyPermission.ActionType.BUILD))
				return;

			if (!bBuild)
				event.setCancelled(true);

			if (cache.hasBlockErrMsg())
				TownyMessaging.sendErrorMsg(player, cache.getBlockErrMsg());

		} catch (NotRegisteredException e1) {
			TownyMessaging.sendErrorMsg(player, TownySettings.getLangString("msg_err_not_configured"));
			event.setCancelled(true);
			return;
		}

		TownyMessaging.sendDebugMsg("onPaintingBreak took " + (System.currentTimeMillis() - start) + "ms (" + event.getEventName() + ", " + event.isCancelled() + ")");
	}

}

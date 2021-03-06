package com.aquanova_mp.winhomes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

public class CommandHome implements CommandExecutor {
	private static final String MESSAGE_HOME_DOES_NOT_EXIST = "You have no home yet, set one with /sethome!";
	private static final String MESSAGE_HOME_WORLD_GONE = "The world this home was in does not exist anymore!";
	private static final String MESSAGE_TELEPORTING_HOME = "Teleporting you to your home...";
	private static final String MESSAGE_TELEPORTING_HOME_OTHER = "Teleporting you to the player's home...";
	private static final String MESSAGE_HOME_UNINVITED = "You are not invited to this player's home!";
	private static final String MESSAGE_SOMETHING_WENT_WRONG = "Something went wrong, please let the admin know!";
	private static final String MESSAGE_TELEPORT_CANCELLED_MOVE ="Teleportation cancelled because you moved too much!";
	private static final String MESSAGE_TELEPORT_CANCELLED_DAMAGE ="Teleportation cancelled because you were damaged!";
	private static final String MESSAGE_CANCELLING_PREVIOUS_COMMAND ="Cancelling previous teleportation...";


	private WinHomes main;

	private class PlayerWarmupCancelListener implements Listener {
		private final Player player;
		private final BukkitTask task;
		private final Location startLoc;
		private final double movementThreshold;

		public PlayerWarmupCancelListener(Player p, BukkitTask t, double mT) {
			player = p;
			task = t;
			movementThreshold = mT;
			startLoc = player.getLocation();
		}

		@EventHandler
		public void onHit(EntityDamageEvent e) {
			if (!main.getServer().getScheduler().isQueued(task.getTaskId())) {
				HandlerList.unregisterAll(this);
				return;
			}
			if (e.getEntity() instanceof Player) {
				Player p = (Player) e.getEntity();
				if (p.equals(player)) {
					player.sendMessage(MESSAGE_TELEPORT_CANCELLED_DAMAGE);
					task.cancel();
				}
			}
		}

		@EventHandler
		public void onPlayerMove(PlayerMoveEvent e) {
			if (!main.getServer().getScheduler().isQueued(task.getTaskId())) {
				HandlerList.unregisterAll(this);
				return;
			}
			Player p = e.getPlayer();
			if (p.equals(player)) {
				if (startLoc.distance(player.getLocation()) > movementThreshold) {
					player.sendMessage(MESSAGE_TELEPORT_CANCELLED_MOVE);
					task.cancel();
				}
			}
		}

		@EventHandler
		public void onHomeCommand(PlayerCommandPreprocessEvent e) {
			Player p = e.getPlayer();
			String c = e.getMessage();
			if (p.equals(player) && c.contains("/home")) {
				player.sendMessage(MESSAGE_CANCELLING_PREVIOUS_COMMAND);
				HandlerList.unregisterAll(this);
				task.cancel();
			}
		}
	}

	public CommandHome(WinHomes winhomes) {
		this.main = winhomes;
	}

	public World getBukkitWorld(String world) {
		World w = null;

		for (int i = 0; i < Bukkit.getWorlds().size(); i++) {
			if (Bukkit.getWorlds().get(i).getUID().toString().equals(world)) {
				w = Bukkit.getWorlds().get(i);
			}
		}

		return w;
	}

	@Override
	public boolean onCommand(CommandSender commandSender, Command command, String label, String[] args) {
		if (commandSender instanceof Player) {
			Player player = (Player) commandSender;

			try (Connection conn = main.getDataSource().getConnection()) {
				if (args.length > 0) {
					String otherPlayerName = args[0];

					// First perform a query to see whether this player is actually invited to the other's home
					String queryCheckOtherHome = SQLTools.queryReader("check_home_other.sql");
					PreparedStatement preparedStmtCheckHomeOther = conn.prepareStatement(queryCheckOtherHome);
					preparedStmtCheckHomeOther.setString(1, otherPlayerName);
					ResultSet rs1 = preparedStmtCheckHomeOther.executeQuery();

					// Iterate over UUID of players invited to the home of the other player
					boolean invited = false;
					while (rs1.next()) {
						String uuid = rs1.getString(1);
						if (uuid.equals(player.getUniqueId().toString())) {
							invited = true;
							break;
						}
					}

					// If invited, teleport to that player's home
					if (invited) {
						String queryGetHomeOther = SQLTools.queryReader("get_home_other.sql");
						PreparedStatement preparedStmtGetHomeOther = conn.prepareStatement(queryGetHomeOther);
						preparedStmtGetHomeOther.setString(1, otherPlayerName);
						ResultSet rs = preparedStmtGetHomeOther.executeQuery();
						Location loc = getLocationFromResultSet(player, rs);
						if (loc != null) {
							teleportPlayerWarmup(player, loc, MESSAGE_TELEPORTING_HOME_OTHER);
						}
						rs.close();
					} else {
						main.getLogger().log(Level.FINE, "Player " + player.getName() + " attempted to telepport to " +otherPlayerName + "'s home but was not invited");
						player.sendMessage(MESSAGE_HOME_UNINVITED);
					}
				} else {
					// If no argument was given, teleport to own home
					String queryGetHome = SQLTools.queryReader("get_home.sql");
					PreparedStatement preparedStmtGetHome = conn.prepareStatement(queryGetHome);
					preparedStmtGetHome.setString(1, player.getUniqueId().toString());
					ResultSet rs = preparedStmtGetHome.executeQuery();
					Location loc = getLocationFromResultSet(player, rs);
					if (loc != null) {
						teleportPlayerWarmup(player, loc, MESSAGE_TELEPORTING_HOME);
					}
					rs.close();
				}
			} catch (SQLException | IOException e) {
				main.commandError(label, args, commandSender.getName(), e);
				player.sendMessage(MESSAGE_SOMETHING_WENT_WRONG);
			}
		}
		return true;
	}

	private Location getLocationFromResultSet(Player player, ResultSet rs) throws SQLException {
		if (rs.next()) {
			String worldID = rs.getString(1);
			World world = getBukkitWorld(worldID);
			if (world != null) {
				// Retrieve location data and put player at home location
				Location loc = player.getLocation();
				loc.setWorld(world);
				loc.setX(rs.getDouble(2));
				loc.setY(rs.getDouble(3));
				loc.setZ(rs.getDouble(4));
				loc.setPitch((float) rs.getDouble(5));
				loc.setYaw((float) rs.getDouble(6));
				return loc;
			} else {
				main.getLogger().log(Level.WARNING, "World with ID " + worldID + " does not exist!");
				player.sendMessage(MESSAGE_HOME_WORLD_GONE);
				rs.close();
			}
		}  else {
			main.getLogger().log(Level.FINE, "No home found for player " + player.getName() + " (" + player.getUniqueId().toString() + ")");
			player.sendMessage(MESSAGE_HOME_DOES_NOT_EXIST);
			rs.close();
		}
		return null;
	}


	private void teleportPlayerWarmup(Player player, Location loc, String message) {
		player.sendMessage("Warming up teleportation device, wait 5 seconds...");
		long delay = main.getConfig().getLong("home_warmup");
		BukkitTask task = new BukkitRunnable() {
			@Override
			public void run() {
				player.teleport(loc);
				player.sendMessage(message);
				main.getLogger().log(Level.FINE, "Teleported player " + player.getPlayerListName() + " to the designated home.");
			}
		}.runTaskLater(this.main, 20 * delay);
		main.getServer().getPluginManager().registerEvents(new PlayerWarmupCancelListener(player, task, main.getConfig().getDouble("warmup_movement_threshold")), main);
	}
}

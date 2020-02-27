package com.bermudalocket.lastseen;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.ocpsoft.prettytime.Duration;
import org.ocpsoft.prettytime.PrettyTime;

// ----------------------------------------------------------------------------
/**
 * The LastSeen plugin in its entirety, including event handlers and command
 * execution.
 */
public class LastSeen extends JavaPlugin implements Listener, TabExecutor {
    // ------------------------------------------------------------------------
    /**
     * Plugin instance as singleton.
     */
    public static LastSeen PLUGIN;

    // ------------------------------------------------------------------------
    /**
     * Return true if debug messages are logged.
     * 
     * @return true if debug messages are logged.
     */
    public boolean isDebug() {
        return _debug;
    }

    // ------------------------------------------------------------------------
    /**
     * @see org.bukkit.plugin.java.JavaPlugin#onEnable()
     */
    @Override
    public void onEnable() {
        PLUGIN = this;
        saveDefaultConfig();
        _debug = getConfig().getBoolean("debug", false);

        long start = System.currentTimeMillis();
        _storage = new DataStorage();
        if (isDebug()) {
            getLogger().info("YAML loading elapsed time: " + (System.currentTimeMillis() - start) + "ms");
        }
        start = System.currentTimeMillis();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            _playerCache.put(player.getName().toLowerCase(), player);
        }
        if (isDebug()) {
            getLogger().info("Player caching elapsed time: " + (System.currentTimeMillis() - start) + "ms");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            _storage.save();
        }, PERIOD, PERIOD);
    }

    // ------------------------------------------------------------------------
    /**
     * @see org.bukkit.plugin.java.JavaPlugin#onDisable()
     */
    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        _storage.save();
    }

    // ------------------------------------------------------------------------
    /**
     * Records the timestamp when a player logs in.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        _storage.setLastSeen(e.getPlayer().getName(), System.currentTimeMillis());

        // Add brand new players to _playerCache.
        if (!e.getPlayer().hasPlayedBefore()) {
            String lowerName = e.getPlayer().getName().toLowerCase();
            _playerCache.put(lowerName, Bukkit.getOfflinePlayer(lowerName));
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Records the timestamp when a player logs out.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        _storage.setLastSeen(e.getPlayer().getName(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------------
    /**
     * @see TabExecutor#onCommand(CommandSender, Command, String, String[]).
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase();

        if (commandName.equals("date")) {
            if (args.length != 0) {
                error(sender, "Invalid extra arguments. Usage: /date");
                return true;
            }

            msg(sender, "It is now " + longToDate(System.currentTimeMillis()) + ".");
            return true;
        }

        if (args.length != 1) {
            error(sender, "Usage: /" + commandName + " <player-name>");
            return true;
        }

        String playerName = args[0];
        OfflinePlayer player = Bukkit.getPlayer(playerName);
        if (player == null) {
            player = getOfflinePlayerByName(playerName);
        }
        if (player == null) {
            msg(sender, playerName + " has never been seen before.");
            return true;
        }

        if (commandName.equals("seen")) {
            if (player.isOnline()) {
                msg(sender, player.getName() + " is online now!");
            } else {
                long lastSeen = _storage.getLastSeen(playerName);
                if (lastSeen == 0) {
                    msg(sender, "Either that player doesn't exist or they haven't been online in a while.");
                } else {
                    msg(sender, player.getName() + " was last seen on " + longToDate(lastSeen) +
                                "\n(" + longToRelativeDate(lastSeen) + ")");
                }
            }
        } else if (commandName.equals("firstseen")) {
            long time = player.getFirstPlayed();
            msg(sender, player.getName() + " first played on " + longToDate(time) +
                        "\n(" + longToRelativeDate(time) + ")");
        }

        return true;
    }

    // ------------------------------------------------------------------------
    /**
     * Returns an instance of OfflinePlayer if one can be found in the Bukkit
     * list of offline players which matches the given playerName String.
     * Otherwise, returns null.
     *
     * Bukkit.getOfflinepPlayer(String) has a couple of quirks that make it
     * unsuitable for this purpose. Firstly, it will always return non-null for
     * any player name whether the corresponding account exists or not.
     * Secondly, it may issue a blocking web request to get the UUID for a given
     * name.
     * 
     * Iterating over an entire collection of ~3500 OfflinePlayers takes about
     * 40ms on the test lappy, which is an appreciable chunk of a tick, so
     * instead we simply cache with _playerCache.
     * 
     * @see https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Bukkit.html#getOfflinePlayer-java.lang.String-
     *
     * @param playerName the name of the player being queried.
     */
    private OfflinePlayer getOfflinePlayerByName(String playerName) {
        return _playerCache.get(playerName.toLowerCase());
    }

    // ------------------------------------------------------------------------
    /**
     * Turns the given timestamp into a string following the form described by
     * DATE_FORMAT.
     *
     * @param time the timestamp.
     * @return a string following the form described by DATE_FORMAT.
     */
    private static String longToDate(Long time) {
        CALENDAR.setTimeInMillis(time);
        return DATE_FORMAT.format(CALENDAR.getTime());
    }

    // ------------------------------------------------------------------------
    /**
     * Turns the given timestamp into a string describing the relative date in
     * English to the current time now.
     *
     * @param time the timestamp.
     * @return a string describing the relative date.
     */
    private static String longToRelativeDate(Long time) {
        CALENDAR.setTimeInMillis(time);
        List<Duration> durations = PRETTY_TIME_FORMAT.calculatePreciseDuration(CALENDAR.getTime());
        return PRETTY_TIME_FORMAT.format(durations);
    }

    // ------------------------------------------------------------------------
    /**
     * Sends a colorised message to the given CommandSender.
     *
     * @param sender the recipient.
     * @param msg the message.
     */
    private static void msg(CommandSender sender, String msg) {
        sender.sendMessage(ChatColor.GOLD + msg);
    }

    // ------------------------------------------------------------------------
    /**
     * Sends a red error message to the given CommandSender.
     *
     * @param sender the recipient.
     * @param msg the message.
     */
    private static void error(CommandSender sender, String msg) {
        sender.sendMessage(ChatColor.RED + msg);
    }

    // ------------------------------------------------------------------------
    /**
     * Period in ticks of repeating task that initiates an async save.
     */
    private static final int PERIOD = 10 * 60 * 20;

    /**
     * Calendar object used for converting timestamps.
     */
    private static final Calendar CALENDAR = Calendar.getInstance();

    /**
     * SimpleDateFormat instance used to convert a timestamp to a date string.
     */
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("E MMM d y hh:mm:ss a");

    /**
     * PrettyTime instance used to convert a timestamp to a relative date
     * string.
     */
    private static final PrettyTime PRETTY_TIME_FORMAT = new PrettyTime();

    /**
     * Persistent YAML storage.
     */
    private DataStorage _storage;

    /**
     * Cache the mapping from lowercase player name to OfflinePlayer instance
     * rather than performing a linear search on getOfflinePlayers(), which
     * takes about 40ms for ~3500 players on the lappy.
     */
    private final HashMap<String, OfflinePlayer> _playerCache = new HashMap<>();

    /**
     * Debug setting from the config, enables debug messages.
     */
    private boolean _debug;

}

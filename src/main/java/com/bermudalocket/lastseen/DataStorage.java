package com.bermudalocket.lastseen;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;

import org.bukkit.configuration.file.YamlConfiguration;

// ----------------------------------------------------------------------------
/**
 * Loads and stores last seen time stamp in a YAML file.
 * 
 * Last seen time stamps are stored as (long) milliseconds since epoch, per
 * System.currentTimeMillis().
 * 
 * YamlConfiguration is not thread safe, so therefore, no public methods of this
 * class are thread-safe. So clients of DataStorage should call these methods
 * from a single thread. But internally the code uses CompletableFuture<>s in a
 * thread-safe manner.
 * 
 * The file format is somewhat redundant; it's inherited from original design.
 * It would become reasonably efficient if a second datum were added. And by
 * keeping it we can use existing data files.
 */
public class DataStorage {
    // ------------------------------------------------------------------------
    /**
     * Constructor.
     */
    public DataStorage() {
        _file = new File(LastSeen.PLUGIN.getDataFolder(), "last-seen.yml");
        if (!_file.exists()) {
            try {
                _file.createNewFile();
            } catch (IOException ex) {
                LastSeen.PLUGIN.getLogger().severe("Cannot create storage: " + ex.getMessage());
            }
        }
        try {
            _yaml = YamlConfiguration.loadConfiguration(_file);
        } catch (Exception ex) {
            LastSeen.PLUGIN.getLogger().severe("Cannot load storage: " + ex.getMessage());
            _yaml = new YamlConfiguration();
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Return the last-seen time stamp of the specified player, or 0 if not seen
     * before.
     * 
     * @param playerName the player name.
     * @return the last-seen time stamp of the specified player, or 0 if not
     *         seen before.
     */
    public long getLastSeen(String playerName) {
        return _yaml.getLong(getKey(playerName, LAST_SEEN), 0);
    }

    // ------------------------------------------------------------------------
    /**
     * Sets the last-seen time stamp of the specified player.
     *
     * @param playerName the player name.
     * @param lastSeen the time stamp, as milliseconds since epoch.
     */
    public void setLastSeen(String playerName, long lastSeen) {
        awaitOngoingSave();
        _yaml.set(getKey(playerName, LAST_SEEN), lastSeen);
        _dirty = true;
    }

    // ------------------------------------------------------------------------
    /**
     * Return the YAML key for the specified player and purpose.
     * 
     * @param playerName the name of the player.
     * @param subkey the sub-key of the player name indicating the purpose of
     *        the value it stores; must include the leading '.'.
     */
    protected String getKey(String playerName, String subkey) {
        return "players." + playerName.toLowerCase() + subkey;
    }

    // ------------------------------------------------------------------------
    /**
     * Synchronously save the YAML storage.
     * 
     * Don't bother to save if the data is unchanged. If a previous asynchronous
     * save is fully complete, then synchronously save. Otherwise, an async save
     * is already in flight, so simply wait for that to complete.
     * 
     * We can be certain that changes will hit the disk. If the last-seen data
     * was changed, setLastSeen() would wait for ongoing saves to complete.
     */
    public void save() {
        if (_dirty && !awaitOngoingSave()) {
            saveFile();
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Asynchronously save the YAML storage.
     * 
     * If an asynchronous save is ongoing, then no new save is initiated; we
     * simply let the old one continue. A new save is only started if the
     * previous one has finished.
     */
    public void saveAsync() {
        if (isQuiescent()) {
            _ongoingSave = CompletableFuture.runAsync(() -> saveFile(), _executor);
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Return true if there is no asynchronous save currently ongoing.
     * 
     * As a side-effect, if the CompletableFuture reference was completed, then
     * _ongoingSave is guaranteed to be null after this method is called.
     * 
     * @return true if there is no asynchronous save ongoing; false if it is
     *         still ongoing.
     */
    protected boolean isQuiescent() {
        if (_ongoingSave == null) {
            return true;
        } else if (_ongoingSave.isDone()) {
            _ongoingSave = null;
            return true;
        } else {
            return false;
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Ensure that any ongoing asynchronous save is completed before returning.
     * 
     * Post-condition: _ongoingSave will always be null.
     * 
     * @return true if the current thread had to block (join()); false if the
     *         last asynchronous save was already complete.
     */
    protected boolean awaitOngoingSave() {
        if (isQuiescent()) {
            return false;
        } else {
            try {
                _ongoingSave.join();
            } catch (CancellationException | CompletionException ex) {
                LastSeen.PLUGIN.getLogger().severe("Unexpected async error: " + ex.getMessage());
            }
            _ongoingSave = null;
            return true;
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Save the file on the current thread.
     */
    protected void saveFile() {
        try {
            long start = System.currentTimeMillis();
            if (LastSeen.PLUGIN.isDebug()) {
                LastSeen.PLUGIN.getLogger().info("Saving last seen data.");
            }
            _yaml.save(_file);
            _dirty = false;
            if (LastSeen.PLUGIN.isDebug()) {
                LastSeen.PLUGIN.getLogger().info("Saving elapsed time: " + (System.currentTimeMillis() - start) + "ms");
            }
        } catch (Exception ex) {
            LastSeen.PLUGIN.getLogger().severe("Cannot save storage: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    /**
     * The subkey parameter to getKey() for last-seen time stamps.
     */
    protected static final String LAST_SEEN = ".last-seen";

    /**
     * The YAML file.
     */
    protected YamlConfiguration _yaml;

    /**
     * The path to the YAML file.
     */
    protected File _file;

    /**
     * A CompletableFuture<> representing the currently ongoing asynchronous
     * file save, or null if not currently saving asynchronously.
     */
    protected CompletableFuture<Void> _ongoingSave;

    /**
     * A non-default fork-join pool for saves, since we don't know what other
     * code might be using the default one for very long tasks.
     * 
     * At most, one thread will be needed.
     */
    protected ForkJoinPool _executor = new ForkJoinPool(1);

    /**
     * True if the last seen data has changed. Declared volatile to ensure the
     * main thread sees the latest value when an async save mutates it.
     */
    protected volatile boolean _dirty;
}

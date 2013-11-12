/*
 * The MIT License
 *
 * Copyright 2013 SBPrime.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.primesoft.asyncworldedit.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalWorld;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.bags.BlockBag;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.expression.ExpressionException;
import com.sk89q.worldedit.masks.Mask;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.TreeGenerator;
import java.util.*;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.primesoft.asyncworldedit.*;

/**
 *
 * @author SBPrime
 */
public class AsyncEditSession extends EditSession {

    private String m_player;
    private BlockPlacer m_blockPlacer;
    private World m_world;
    private final List<BukkitTask> m_tasks;
    /**
     * The blocks hub integrator
     */
    private BlocksHubIntegration m_bh;
    /**
     * The parent factory class
     */
    private AsyncEditSessionFactory m_factory;
    /**
     * Force all functions to by performed in async mode this is used to
     * override the config by API calls
     */
    private boolean m_asyncForced;
    /**
     * Indicates that the async mode has been disabled (inner state)
     */
    private boolean m_asyncDisabled;
    /**
     * Plugin instance
     */
    private PluginMain m_plugin;
    /**
     * Bukkit schedule
     */
    private BukkitScheduler m_schedule;

    public String getPlayer() {
        return m_player;
    }

    public AsyncEditSession(AsyncEditSessionFactory factory, PluginMain plugin,
            String player, LocalWorld world, int maxBlocks) {
        super(world, maxBlocks);
        m_tasks = new ArrayList<BukkitTask>();
        initialize(player, plugin, world, factory);
    }

    public AsyncEditSession(AsyncEditSessionFactory factory, PluginMain plugin,
            String player, LocalWorld world, int maxBlocks,
            BlockBag blockBag) {
        super(world, maxBlocks, blockBag);
        m_tasks = new ArrayList<BukkitTask>();
        initialize(player, plugin, world, factory);
    }

    @Override
    public boolean rawSetBlock(Vector pt, BaseBlock block) {
        if (!m_bh.canPlace(m_player, m_world, pt)) {
            return false;
        }

        if (m_asyncForced || (PluginMain.hasAsyncMode(m_player) && !m_asyncDisabled)) {
            return m_blockPlacer.addTasks(new BlockPlacerBlockEntry(this, pt, block));
        } else {
            return doRawSetBlock(pt, block);
        }
    }

    @Override
    public void setMask(Mask mask) {
        if (m_asyncForced || (PluginMain.hasAsyncMode(m_player) && !m_asyncDisabled)) {
            m_blockPlacer.addTasks(new BlockPlacerMaskEntry(this, mask));
        } else {
            doSetMask(mask);
        }

    }

    @Override
    public void flushQueue() {
        boolean queued = isQueueEnabled();
        super.flushQueue();
        if (queued) {
            resetAsync();
        }
    }

    @Override
    public void undo(EditSession sess) {
        BukkitTask[] tasks;
        synchronized (m_tasks) {
            tasks = m_tasks.toArray(new BukkitTask[0]);
            for (BukkitTask task : tasks) {
                task.cancel();
            }

            m_tasks.clear();
        }

        checkAsync(WorldeditOperations.undo);
        UndoSession undoSession = new UndoSession();
        super.undo(undoSession);

        final Map.Entry<Vector, BaseBlock>[] blocks = undoSession.getEntries();
        final HashMap<Integer, HashMap<Integer, HashSet<Integer>>> placedBlocks = new HashMap<Integer, HashMap<Integer, HashSet<Integer>>>();

        for (int i = blocks.length - 1; i >= 0; i--) {
            Map.Entry<Vector, BaseBlock> entry = blocks[i];
            Vector pos = entry.getKey();
            BaseBlock block = entry.getValue();

            int x = pos.getBlockX();
            int y = pos.getBlockY();
            int z = pos.getBlockZ();
            boolean ignore = false;

            HashMap<Integer, HashSet<Integer>> mapX = placedBlocks.get(x);
            if (mapX == null) {
                mapX = new HashMap<Integer, HashSet<Integer>>();
                placedBlocks.put(x, mapX);
            }

            HashSet<Integer> mapY = mapX.get(y);
            if (mapY == null) {
                mapY = new HashSet<Integer>();
                mapX.put(y, mapY);
            }
            if (mapY.contains(z)) {
                ignore = true;
            } else {
                mapY.add(z);
            }

            if (!ignore) {
                sess.smartSetBlock(pos, block);
            }
        }

        sess.flushQueue();
        if (!isQueueEnabled()) {
            resetAsync();
        }
    }

    @Override
    public void redo(EditSession sess) {
        checkAsync(WorldeditOperations.redo);
        super.redo(sess);
        if (!isQueueEnabled()) {
            resetAsync();
        }
    }

    @Override
    public int fillXZ(Vector origin, BaseBlock block, double radius, int depth,
            boolean recursive)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.fillXZ);
        int result = super.fillXZ(origin, block, radius, depth, recursive);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int fillXZ(Vector origin, Pattern pattern, double radius, int depth,
            boolean recursive)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.fillXZ);
        int result = super.fillXZ(origin, pattern, radius, depth, recursive);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int removeAbove(Vector pos, int size, int height)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.removeAbove);
        int result = super.removeAbove(pos, size, height);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int removeBelow(Vector pos, int size, int height)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.removeBelow);
        int result = super.removeBelow(pos, size, height);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int removeNear(Vector pos, int blockType, int size)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.removeNear);
        int result = super.removeNear(pos, blockType, size);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    private int supperSetBlocks(Region region, BaseBlock block) throws MaxChangedBlocksException {
        return super.setBlocks(region, block);
    }

    private int superSetBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        return super.setBlocks(region, pattern);
    }

    @Override
    public int setBlocks(final Region region, final BaseBlock block)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.setBlocks);

        if (!isAsync) {
            return supperSetBlocks(region, block);
        }

        final Player p = PluginMain.getPlayer(m_player);
        PluginMain.Say(p, ChatColor.RED + "Warning full async mode! Number of changed blocks is incorrect!");

        final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
        tasks.add(m_schedule.runTaskAsynchronously(m_plugin,
                new AsyncTask(this, p, "setBlocks", m_tasks, tasks) {

                    @Override
                    public int task() throws MaxChangedBlocksException {
                        return supperSetBlocks(region, block);
                    }
                }));

        synchronized (m_tasks) {
            for (BukkitTask task : tasks) {
                m_tasks.add(task);
            }
        }
        return 0;
    }

    @Override
    public int setBlocks(final Region region, final Pattern pattern)
            throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.setBlocks);

        if (!isAsync) {
            return superSetBlocks(region, pattern);
        }

        final Player p = PluginMain.getPlayer(m_player);
        PluginMain.Say(p, ChatColor.RED + "Warning full async mode! Number of changed blocks is incorrect!");

        final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
        tasks.add(m_schedule.runTaskAsynchronously(m_plugin,
                new AsyncTask(this, p, "setBlocks", m_tasks, tasks) {

                    @Override
                    public int task() throws MaxChangedBlocksException {
                        return superSetBlocks(region, pattern);
                    }
                }));

        synchronized (m_tasks) {
            for (BukkitTask task : tasks) {
                m_tasks.add(task);
            }
        }
        return 0;
    }

    private int superReplaceBlocks(Region region, Set<BaseBlock> fromBlockTypes,
            BaseBlock toBlock) throws MaxChangedBlocksException {
        return super.replaceBlocks(region, fromBlockTypes, toBlock);
    }

    private int superReplaceBlocks(Region region, Set<BaseBlock> fromBlockTypes,
            Pattern pattern) throws MaxChangedBlocksException {
        return super.replaceBlocks(region, fromBlockTypes, pattern);
    }

    @Override
    public int replaceBlocks(final Region region, final Set<BaseBlock> fromBlockTypes,
            final BaseBlock toBlock) throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.replaceBlocks);

        if (!isAsync) {
            return superReplaceBlocks(region, fromBlockTypes, toBlock);
        }

        final Player p = PluginMain.getPlayer(m_player);
        PluginMain.Say(p, ChatColor.RED + "Warning full async mode! Number of changed blocks is incorrect!");

        final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
        tasks.add(m_schedule.runTaskAsynchronously(m_plugin,
                new AsyncTask(this, p, "replaceBlocks", m_tasks, tasks) {

                    @Override
                    public int task() throws MaxChangedBlocksException {
                        return superReplaceBlocks(region, fromBlockTypes, toBlock);
                    }
                }));

        synchronized (m_tasks) {
            for (BukkitTask task : tasks) {
                m_tasks.add(task);
            }
        }
        return 0;
    }

    @Override
    public int replaceBlocks(final Region region, final Set<BaseBlock> fromBlockTypes,
            final Pattern pattern) throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.replaceBlocks);
        if (!isAsync) {
            return superReplaceBlocks(region, fromBlockTypes, pattern);
        }

        final Player p = PluginMain.getPlayer(m_player);
        PluginMain.Say(p, ChatColor.RED + "Warning full async mode! Number of changed blocks is incorrect!");

        final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
        tasks.add(m_schedule.runTaskAsynchronously(m_plugin,
                new AsyncTask(this, p, "replaceBlocks", m_tasks, tasks) {

                    @Override
                    public int task() throws MaxChangedBlocksException {
                        return superReplaceBlocks(region, fromBlockTypes, pattern);
                    }
                }));

        synchronized (m_tasks) {
            for (BukkitTask task : tasks) {
                m_tasks.add(task);
            }
        }
        return 0;
    }

    @Override
    public int makeCuboidFaces(Region region, BaseBlock block)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeCuboidFaces);
        int result = super.makeCuboidFaces(region, block);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeCuboidFaces(Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeCuboidFaces);
        int result = super.makeCuboidFaces(region, pattern);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeCuboidWalls(Region region, BaseBlock block)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeCuboidWalls);
        int result = super.makeCuboidWalls(region, block);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeCuboidWalls(Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeCuboidWalls);
        int result = super.makeCuboidWalls(region, pattern);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int overlayCuboidBlocks(Region region, BaseBlock block)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.overlayCuboidBlocks);
        int result = super.overlayCuboidBlocks(region, block);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int overlayCuboidBlocks(Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.overlayCuboidBlocks);
        int result = super.overlayCuboidBlocks(region, pattern);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int naturalizeCuboidBlocks(Region region)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.naturalizeCuboidBlocks);
        int result = super.naturalizeCuboidBlocks(region);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int stackCuboidRegion(Region region, Vector dir, int count,
            boolean copyAir)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.stackCuboidRegion);
        int result = super.stackCuboidRegion(region, dir, count, copyAir);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    private int superMoveCuboidRegion(Region region, Vector dir, int distance,
            boolean copyAir, BaseBlock replace)
            throws MaxChangedBlocksException {
        return super.moveCuboidRegion(region, dir, distance, copyAir, replace);
    }

    @Override
    public int moveCuboidRegion(final Region region, final Vector dir, final int distance,
            final boolean copyAir, final BaseBlock replace) throws MaxChangedBlocksException {
        boolean isAsync = checkAsync(WorldeditOperations.moveCuboidRegion);
        if (!isAsync) {
            return superMoveCuboidRegion(region, dir, distance, copyAir, replace);
        }

        final Player p = PluginMain.getPlayer(m_player);
        PluginMain.Say(p, ChatColor.RED + "Warning full async mode! Number of changed blocks is incorrect!");

        final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
        tasks.add(m_schedule.runTaskAsynchronously(m_plugin,
                new AsyncTask(this, p, "moveCuboidRegion", m_tasks, tasks) {

                    @Override
                    public int task() throws MaxChangedBlocksException {
                        return superMoveCuboidRegion(region, dir, distance, copyAir, replace);
                    }
                }));

        synchronized (m_tasks) {
            for (BukkitTask task : tasks) {
                m_tasks.add(task);
            }
        }
        return 0;
    }

    @Override
    public int drainArea(Vector pos, double radius)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.drainArea);
        int result = super.drainArea(pos, radius);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int fixLiquid(Vector pos, double radius, int moving, int stationary)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.fixLiquid);
        int result = super.fixLiquid(pos, radius, moving, stationary);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeCylinder(Vector pos, Pattern block, double radius, int height,
            boolean filled)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeCylinder);
        int result = super.makeCylinder(pos, block, radius, height, filled);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeCylinder(Vector pos, Pattern block, double radiusX,
            double radiusZ, int height, boolean filled)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeCylinder);
        int result = super.makeCylinder(pos, block, radiusX, radiusZ, height, filled);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeSphere(Vector pos, Pattern block, double radius,
            boolean filled)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeSphere);
        int result = super.makeSphere(pos, block, radius, filled);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeSphere(Vector pos, Pattern block, double radiusX,
            double radiusY, double radiusZ, boolean filled)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeSphere);
        int result = super.makeSphere(pos, block, radiusX, radiusY, radiusZ, filled);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makePyramid(Vector pos, Pattern block, int size, boolean filled)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makePyramid);
        int result = super.makePyramid(pos, block, size, filled);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int thaw(Vector pos, double radius)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.thaw);
        int result = super.thaw(pos, radius);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int simulateSnow(Vector pos, double radius)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.simulateSnow);
        int result = super.simulateSnow(pos, radius);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int green(Vector pos, double radius)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.green);
        int result = super.green(pos, radius);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makePumpkinPatches(Vector basePos, int size)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makePumpkinPatches);
        int result = super.makePumpkinPatches(basePos, size);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeForest(Vector basePos, int size, double density,
            TreeGenerator treeGenerator)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeForest);
        int result = super.makeForest(basePos, size, density, treeGenerator);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int makeShape(Region region, Vector zero, Vector unit,
            Pattern pattern, String expressionString,
            boolean hollow)
            throws ExpressionException, MaxChangedBlocksException {
        checkAsync(WorldeditOperations.makeShape);
        int result = super.makeShape(region, zero, unit, pattern, expressionString, hollow);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int deformRegion(Region region, Vector zero, Vector unit,
            String expressionString)
            throws ExpressionException, MaxChangedBlocksException {
        checkAsync(WorldeditOperations.deformRegion);
        int result = super.deformRegion(region, zero, unit, expressionString);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int hollowOutRegion(Region region, int thickness, Pattern pattern)
            throws MaxChangedBlocksException {
        checkAsync(WorldeditOperations.hollowOutRegion);
        int result = super.hollowOutRegion(region, thickness, pattern);
        if (!isQueueEnabled()) {
            resetAsync();
        }
        return result;
    }

    @Override
    public int size() {
        final int result = super.size();
        if (result <= 0 && m_tasks.size() > 0) {
            return 1;
        }
        return result;
    }

    public boolean doRawSetBlock(Vector location, BaseBlock block) {
        String player = getPlayer();
        World world = getCBWorld();
        BaseBlock oldBlock = getBlock(location);

        boolean success = super.rawSetBlock(location, block);
        //boolean success = eSession.doRawSetBlock(location, block);

        if (success && world != null) {
            m_bh.logBlock(player, world, location, oldBlock, block);
        }
        return success;
    }

    public void doSetMask(Mask mask) {
        super.setMask(mask);

    }

    public World getCBWorld() {
        return m_world;
    }

    /**
     * Initialize the local veriables
     *
     * @param player edit session owner
     * @param plugin parent plugin
     * @param world edit session world
     */
    private void initialize(String player, PluginMain plugin,
            LocalWorld world, AsyncEditSessionFactory factory) {
        m_plugin = plugin;
        m_bh = plugin.getBlocksHub();
        m_factory = factory;
        m_player = player;
        m_blockPlacer = plugin.getBlockPlacer();
        m_schedule = plugin.getServer().getScheduler();
        if (world != null) {
            m_world = plugin.getServer().getWorld(world.getName());
        }
        m_asyncForced = false;
        m_asyncDisabled = false;
    }

    /**
     * Enables or disables the async mode configuration bypass this function
     * should by used only by other plugins
     *
     * @param value true to enable async mode force
     */
    public void setAsyncForced(boolean value) {
        m_asyncForced = value;
    }

    /**
     * Check if async mode is forced
     *
     * @return
     */
    public boolean isAsyncForced() {
        return m_asyncForced;
    }

    /**
     * This function checks if async mode is enabled for specific command
     *
     * @param operation
     */
    private boolean checkAsync(WorldeditOperations operation) {
        boolean result = ConfigProvider.isAsyncAllowed(operation);

        m_asyncDisabled = !result;
        return result;
    }

    /**
     * Reset async disabled inner state (enable async mode)
     */
    public void resetAsync() {
        m_asyncDisabled = false;
    }
}

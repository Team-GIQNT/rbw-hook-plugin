package dev.giqnt.rbw.hook.voting;

import dev.giqnt.rbw.hook.HookPlugin;
import dev.giqnt.rbw.hook.api.GameCreateException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs map votes. Every game has a vote of its own, so votes never touch each other. A vote gives its players a voting
 * item and window, and ends when everyone has voted or {@link #VOTING_SECONDS} have passed. Main thread only.
 */
public class MapVoteManager implements Listener {
    public static final int VOTING_SECONDS = 15;
    private static final int ITEM_SLOT = 2;
    private static final int GUI_SIZE = 27;
    private static final String GUI_TITLE = ChatColor.DARK_GRAY + "Vote for a map";
    private static final String ITEM_NAME = ChatColor.GREEN + "Map Voting " + ChatColor.GRAY + "(Right Click)";
    /** EMPTY_MAP before 1.13, where it was renamed to MAP. */
    private static final Material MAP_MATERIAL = findMapMaterial();

    private final HookPlugin plugin;
    /** Game id to its vote. */
    private final Map<String, Vote> votes = new HashMap<>();
    private final Map<UUID, Vote> votesByPlayer = new HashMap<>();

    public MapVoteManager(final HookPlugin plugin) {
        this.plugin = plugin;
    }

    private static Material findMapMaterial() {
        Material material = Material.matchMaterial("EMPTY_MAP");
        if (material == null) material = Material.matchMaterial("MAP");
        return material == null ? Material.PAPER : material;
    }

    public boolean isVoting(final Player player) {
        return votesByPlayer.containsKey(player.getUniqueId());
    }

    /**
     * Starts a vote and opens it for every player. The future completes with the winning map name, or fails with a
     * {@link GameCreateException} if the vote is cancelled.
     */
    public CompletableFuture<String> start(final String gameId, final Set<Player> players, final List<String> maps) {
        if (maps.isEmpty()) throw new IllegalArgumentException("No maps to vote on");
        if (votes.containsKey(gameId)) throw new GameCreateException("Game " + gameId + " is already voting");
        for (final Player player : players) {
            if (votesByPlayer.containsKey(player.getUniqueId())) {
                throw new GameCreateException(String.format("Player `%s` is already voting", player.getName()));
            }
        }
        final Vote vote = new Vote(gameId, List.copyOf(maps), List.copyOf(players));
        votes.put(gameId, vote);
        for (final Player player : vote.players) {
            votesByPlayer.put(player.getUniqueId(), vote);
            giveItem(vote, player);
            player.sendMessage(ChatColor.YELLOW + "Vote for a map! You have " + VOTING_SECONDS + " seconds.");
            openGui(vote, player);
        }
        vote.timer = Bukkit.getScheduler().runTaskLater(plugin, () -> finish(vote), VOTING_SECONDS * 20L);
        return vote.future;
    }

    /** Cancels every vote, for example when the server shuts down. */
    public void cancelAll(final String reason) {
        List.copyOf(votes.values()).forEach(vote -> cancel(vote, reason));
    }

    private void cancel(final Vote vote, final String reason) {
        if (!close(vote)) return;
        vote.future.completeExceptionally(new GameCreateException(reason));
    }

    private void finish(final Vote vote) {
        if (!close(vote)) return;
        final int[] counts = vote.counts();
        int highest = 0;
        for (final int count : counts) highest = Math.max(highest, count);
        // With no votes every map ties at 0, so the same rule picks a random map.
        final List<Integer> tied = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == highest) tied.add(i);
        }
        final int winner = tied.get(ThreadLocalRandom.current().nextInt(tied.size()));
        final String message = ChatColor.GREEN + "Map voting finished: " + ChatColor.YELLOW + vote.maps.get(winner)
                + ChatColor.GRAY + " (" + counts[winner] + (counts[winner] == 1 ? " vote)" : " votes)");
        vote.players.forEach(player -> player.sendMessage(message));
        vote.future.complete(vote.maps.get(winner));
    }

    /** Removes the vote and everything it gave to the players. False if it was already closed. */
    private boolean close(final Vote vote) {
        if (!votes.remove(vote.gameId, vote)) return false;
        if (vote.timer != null) vote.timer.cancel();
        for (final Player player : vote.players) {
            votesByPlayer.remove(player.getUniqueId(), vote);
            final Inventory gui = vote.open.remove(player.getUniqueId());
            removeItem(vote, player);
            if (gui != null && player.isOnline()) player.closeInventory();
        }
        return true;
    }

    private void cast(final Vote vote, final Player player, final int mapIndex) {
        final Integer previous = vote.choices.put(player.getUniqueId(), mapIndex);
        if (previous != null && previous == mapIndex) return;
        player.sendMessage(ChatColor.GREEN + "You voted for " + ChatColor.YELLOW + vote.maps.get(mapIndex)
                + ChatColor.GREEN + ".");
        vote.open.forEach((uuid, gui) -> {
            final Player viewer = Bukkit.getPlayer(uuid);
            if (viewer != null) render(vote, viewer, gui);
        });
        if (vote.choices.size() >= vote.players.size()) finish(vote);
    }

    private void openGui(final Vote vote, final Player player) {
        if (vote.open.containsKey(player.getUniqueId())) return;
        final VoteHolder holder = new VoteHolder(vote);
        final Inventory gui = Bukkit.createInventory(holder, GUI_SIZE, GUI_TITLE);
        holder.inventory = gui;
        render(vote, player, gui);
        vote.open.put(player.getUniqueId(), gui);
        player.openInventory(gui);
    }

    private void render(final Vote vote, final Player player, final Inventory gui) {
        final int[] counts = vote.counts();
        final Integer own = vote.choices.get(player.getUniqueId());
        final int[] slots = slotsFor(vote.maps.size());
        for (int i = 0; i < vote.maps.size(); i++) {
            final ItemStack item = new ItemStack(MAP_MATERIAL);
            final ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + vote.maps.get(i));
                final List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Votes: " + ChatColor.WHITE + counts[i]);
                lore.add("");
                lore.add(own != null && own == i
                        ? ChatColor.GREEN + "\u2714 Your vote"
                        : ChatColor.GREEN + "Click to vote");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            gui.setItem(slots[i], item);
        }
    }

    private static int[] slotsFor(final int count) {
        return switch (count) {
            case 1 -> new int[]{13};
            case 2 -> new int[]{12, 14};
            default -> new int[]{11, 13, 15};
        };
    }

    private void giveItem(final Vote vote, final Player player) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack replaced = inventory.getItem(ITEM_SLOT);
        if (replaced != null && !isVoteItem(replaced)) vote.replaced.put(player.getUniqueId(), replaced);
        final ItemStack item = new ItemStack(MAP_MATERIAL);
        final ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ITEM_NAME);
            meta.setLore(List.of(ChatColor.GRAY + "Click to vote for a map"));
            item.setItemMeta(meta);
        }
        inventory.setItem(ITEM_SLOT, item);
        player.updateInventory();
    }

    private void removeItem(final Vote vote, final Player player) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isVoteItem(contents[i])) inventory.setItem(i, null);
        }
        final ItemStack replaced = vote.replaced.remove(player.getUniqueId());
        if (replaced != null) inventory.setItem(ITEM_SLOT, replaced);
        if (player.isOnline()) player.updateInventory();
    }

    private static boolean isVoteItem(final @Nullable ItemStack item) {
        if (item == null || item.getType() != MAP_MATERIAL || !item.hasItemMeta()) return false;
        final ItemMeta meta = item.getItemMeta();
        return meta != null && ITEM_NAME.equals(meta.getDisplayName());
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        final Inventory top = event.getInventory();
        if (top.getHolder() instanceof VoteHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() != top) return;
            final int[] slots = slotsFor(holder.vote.maps.size());
            for (int i = 0; i < holder.vote.maps.size(); i++) {
                if (slots[i] == event.getSlot() && votes.get(holder.vote.gameId) == holder.vote) {
                    cast(holder.vote, player, i);
                    return;
                }
            }
            return;
        }
        if (!votesByPlayer.containsKey(player.getUniqueId())) return;
        boolean movesItem = isVoteItem(event.getCurrentItem()) || isVoteItem(event.getCursor());
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            movesItem |= isVoteItem(player.getInventory().getItem(event.getHotbarButton()));
        }
        if (movesItem) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof VoteHolder || isVoteItem(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(final InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof VoteHolder holder)) return;
        holder.vote.open.remove(event.getPlayer().getUniqueId(), event.getInventory());
    }

    @EventHandler
    public void onInteract(final PlayerInteractEvent event) {
        final Action action = event.getAction();
        if (action == Action.PHYSICAL || !isVoteItem(event.getItem())) return;
        final Vote vote = votesByPlayer.get(event.getPlayer().getUniqueId());
        if (vote == null) return;
        event.setCancelled(true);
        openGui(vote, event.getPlayer());
    }

    @EventHandler
    public void onDrop(final PlayerDropItemEvent event) {
        if (isVoteItem(event.getItemDrop().getItemStack())
                && votesByPlayer.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Vote vote = votesByPlayer.get(event.getPlayer().getUniqueId());
        if (vote == null) return;
        cancel(vote, String.format("Player `%s` left the game", event.getPlayer().getName()));
    }

    private static final class VoteHolder implements InventoryHolder {
        private final Vote vote;
        private Inventory inventory;

        private VoteHolder(final Vote vote) {
            this.vote = vote;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Vote {
        private final String gameId;
        private final List<String> maps;
        private final List<Player> players;
        /** Player to the index of the map they voted for. */
        private final Map<UUID, Integer> choices = new HashMap<>();
        /** Player to the voting window they have open. */
        private final Map<UUID, Inventory> open = new LinkedHashMap<>();
        /** What the voting item replaced in each player's inventory. */
        private final Map<UUID, ItemStack> replaced = new HashMap<>();
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private @Nullable BukkitTask timer;

        private Vote(final String gameId, final List<String> maps, final List<Player> players) {
            this.gameId = gameId;
            this.maps = maps;
            this.players = players;
        }

        private int[] counts() {
            final int[] counts = new int[maps.size()];
            choices.values().forEach(index -> counts[index]++);
            return counts;
        }
    }
}

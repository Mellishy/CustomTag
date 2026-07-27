package com.mellishy.customtag.listener;

import com.mellishy.customtag.MellishyCustomTag;
import com.mellishy.customtag.config.ConfigManager;
import com.mellishy.customtag.data.PlayerData;
import com.mellishy.customtag.data.TagEntry;
import com.mellishy.customtag.event.TagSubmitEvent;
import com.mellishy.customtag.util.ColorUtil;
import com.mellishy.customtag.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles the "book" tag-creation method.
 *
 * IMPORTANT DESIGN NOTE: the book is never actually "given" to the player as a real inventory item.
 * Minecraft's protocol has no way to open the writable-book editor for a player unless a book is
 * physically in one of their hands (unlike read-only signed books, which Paper can show with zero
 * inventory footprint via Player#openBook) - so the closest we can get to the clean, Hypixel-style
 * experience the player wants is: briefly place the book in their MAIN HAND only for the duration of
 * editing, remember whatever was there before, and restore it the instant they click Done/Sign. The
 * book never lingers anywhere in their inventory before or after.
 */
public class BookEditListener implements Listener {

    private final MellishyCustomTag plugin;

    public BookEditListener(MellishyCustomTag plugin) {
        this.plugin = plugin;
    }

    /**
     * @param target        "new" for a fresh tag, or the tag-id being edited.
     * @param reservationId only relevant for target="new" - the reservation id charged in
     *                      TagService#reserveForCreation. The book is stamped with it, and
     *                      signing is rejected unless it still matches the player's active
     *                      reservation (prevents a stale/abandoned book from being signed
     *                      later for a free tag - see TagService class javadoc).
     */
    public void giveBook(Player player, String target, String reservationId) {
        ConfigManager cfg = plugin.config();

        // What page 2 starts out showing: the generic template for a new tag, or - when re-editing -
        // the tag's OWN current text, so the player edits from what's actually there instead of a
        // blank placeholder. This value is also what "did they actually change anything?" is checked
        // against once they submit (see onEditBook).
        String originalText;
        if (target.equals("new")) {
            originalText = cfg.bookTemplatePage();
        } else {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            originalText = data.getTagById(target).map(TagEntry::getRawText).orElseGet(cfg::bookTemplatePage);
        }

        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();

        Component help = ColorUtil.parse(cfg.bookHelpPage());
        Component templatePage = ColorUtil.parse(originalText);
        meta.addPages(help, templatePage);

        meta.getPersistentDataContainer().set(Keys.bookTarget(), PersistentDataType.STRING, target);
        meta.getPersistentDataContainer().set(Keys.bookOriginalText(), PersistentDataType.STRING, originalText);
        meta.getPersistentDataContainer().set(Keys.bookOwner(), PersistentDataType.STRING, player.getUniqueId().toString());
        if (reservationId != null) {
            meta.getPersistentDataContainer().set(Keys.bookReservation(), PersistentDataType.STRING, reservationId);
        }

        // stash whatever the player was already holding so it can come straight back afterwards -
        // the book only ever borrows the hand slot, it's not really "given"
        ItemStack previousHand = player.getInventory().getItemInMainHand();
        if (previousHand != null && !previousHand.getType().isAir()) {
            meta.getPersistentDataContainer().set(Keys.bookSavedHand(), PersistentDataType.BYTE_ARRAY, previousHand.serializeAsBytes());
        }

        book.setItemMeta(meta);
        player.getInventory().setItemInMainHand(book);
        player.sendMessage(ColorUtil.parse(cfg.msg("gui-book-given")));
    }

    @EventHandler
    public void onEditBook(PlayerEditBookEvent event) {
        // NOTE: vanilla's book editor has TWO ways to close it - "Done" (keeps it as an unsigned
        // book, isSigning() == false) and "Sign and Close" (isSigning() == true). Players naturally
        // click "Done" after writing their tag, so we must treat BOTH as "the player is finished
        // submitting". Either way this event fires exactly once and the book editor closes.
        BookMeta previousMeta = event.getPreviousBookMeta();
        String target = previousMeta.getPersistentDataContainer().get(Keys.bookTarget(), PersistentDataType.STRING);
        if (target == null) return; // not one of our books

        Player player = event.getPlayer();
        event.setCancelled(true); // we handle the submission ourselves, the book is never actually kept

        ConfigManager cfg = plugin.config();
        String reservationId = previousMeta.getPersistentDataContainer().get(Keys.bookReservation(), PersistentDataType.STRING);
        String originalText = previousMeta.getPersistentDataContainer().get(Keys.bookOriginalText(), PersistentDataType.STRING);

        if (target.equals("new")) {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            boolean stillValid = data.isReservationActive()
                    && reservationId != null
                    && reservationId.equals(data.getReservationId());
            if (!stillValid) {
                // the reservation behind this book was already refunded (they disconnected mid-creation,
                // or this is a stale duplicate of an old book) - reject it instead of granting a free tag
                restoreHandNextTick(player, previousMeta);
                player.sendMessage(ColorUtil.parse(cfg.msg("reservation-expired")));
                return;
            }
        }

        BookMeta newMeta = event.getNewBookMeta();
        String content = "";
        if (newMeta.getPageCount() >= 2) {
            Component page = newMeta.page(2);
            content = PlainTextComponentSerializer.plainText().serialize(page).trim();
        }

        // The item is only ever borrowed for this edit - always give the player's hand back, whatever
        // they end up submitting (or not submitting) below. Done on the NEXT tick, not here: CraftBukkit
        // re-applies the edited item to the hand slot right after this event returns, so anything we set
        // in this same tick gets silently overwritten and the book appears "stuck" - this is what was
        // causing the book to remain in hand after clicking Done.
        restoreHandNextTick(player, previousMeta);

        if (content.isEmpty()) {
            return; // wrote nothing at all - nothing to submit
        }

        // BUGFIX: same tokens.max-tag-length check now enforced on the CHAT method (see
        // ChatInputListener#handle) - previously only that path had any length limit at all, so the
        // book method was a way to bypass it entirely (a book page can hold far more characters than
        // is reasonable for a chat-rendered tag). Checked against the plain, stripped length for the
        // same reason as the chat path.
        int plainLength = ColorUtil.stripToPlain(content).length();
        int maxLength = cfg.maxTagLength();
        if (plainLength > maxLength) {
            player.sendMessage(ColorUtil.parse(cfg.msg("tag-too-long")
                    .replace("{length}", String.valueOf(plainLength))
                    .replace("{max}", String.valueOf(maxLength))));
            return;
        }

        // Reject a submission that's identical to what they were shown when the book was handed out
        // (the default template for a new tag, or their existing text when re-editing) - previously
        // clicking Done/Sign without writing anything real still forwarded the placeholder/old text
        // straight to the admin queue as if it were a real request.
        String plainOriginal = originalText != null ? ColorUtil.stripToPlain(originalText).trim() : null;
        if (plainOriginal != null && content.equalsIgnoreCase(plainOriginal)) {
            player.sendMessage(ColorUtil.parse(cfg.msg("book-unedited")));
            return;
        }

        String editingId = target.equals("new") ? null : target;

        // Same live preview + explicit confirm step the chat method already had (see
        // ChatInputListener#showBookPreview) - previously the book method skipped straight to
        // submitting the moment "Done"/"Sign" was clicked, with no chance to see how it'll look or
        // back out first. Respects the same preview.enabled toggle as the chat method so both
        // creation methods behave identically.
        if (cfg.previewEnabled()) {
            ChatInputListener.InputType type = editingId == null
                    ? ChatInputListener.InputType.CREATE_TAG
                    : ChatInputListener.InputType.EDIT_TAG;
            plugin.chatInput().showBookPreview(player, type, editingId, content, reservationId);
        } else {
            plugin.getServer().getPluginManager().callEvent(new TagSubmitEvent(player, editingId, content, reservationId));
        }
    }

    /**
     * Prevents a creation book from dropping on the ground when its owner dies (so nobody else
     * can pick it up, and it doesn't clutter the world). Since the physical book is now gone
     * either way, any reservation it represented is refunded immediately via TagService so the
     * player isn't left with a spent token and nothing to show for it.
     *
     * The stashed hand item (see giveBook) rides along INSIDE the book, so deleting the book on its
     * own would silently destroy the real item the player was holding when they asked for the book -
     * it's put back into the drop list here so it behaves exactly like any other item they died with.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        List<ItemStack> stashed = new ArrayList<>(1);
        boolean removedOne = event.getDrops().removeIf(drop -> {
            if (!isOurBook(drop)) return false;
            ItemStack saved = savedHandOf(drop);
            if (saved != null) stashed.add(saved);
            return true;
        });
        if (!removedOne) return;
        event.getDrops().addAll(stashed);
        plugin.tagService().handleBookDiscarded(event.getEntity(), "book-lost-to-death");
    }

    /**
     * The book is only ever meant to borrow the main-hand slot, so throwing it away is treated as
     * "I changed my mind": the drop is cancelled, the player's original item comes straight back into
     * their hand and the reservation is refunded.
     *
     * Without this the book - carrying that original item in its NBT - would become a free-floating
     * world item that could despawn, burn, or be picked up by someone else, taking the owner's item
     * with it.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (!isOurBook(item)) return;
        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(ownerOf(item))) return; // somebody else's stray book - not ours to hand back

        event.setCancelled(true);
        // the cancelled drop leaves the book back in hand, so clear it out on the next tick the same
        // way a finished edit does
        restoreHandNextTick(player, item);
        plugin.tagService().handleBookDiscarded(player, "book-thrown-away");
    }

    /**
     * A creation book must never outlive the session it was handed out in. The reservation behind it
     * is already refunded on quit ({@link com.mellishy.customtag.service.TagService#handleDisconnect}),
     * so the book itself is dead weight from this point on - and it's still holding the player's real
     * item hostage in its NBT. Swap every one of their books back for the item it borrowed the slot
     * from, so they log back in exactly as they were.
     *
     * Runs synchronously on purpose: the inventory is written to disk right after this event, so there
     * is no "next tick" left to defer to.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isOurBook(item) || !player.getUniqueId().equals(ownerOf(item))) continue;
            contents[slot] = savedHandOf(item);
            changed = true;
        }
        if (changed) {
            player.getInventory().setContents(contents);
        }
    }

    private boolean isOurBook(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        if (!(item.getItemMeta() instanceof BookMeta meta)) return false;
        return meta.getPersistentDataContainer().has(Keys.bookTarget(), PersistentDataType.STRING);
    }

    /** The player the book was handed to, or null if it isn't one of ours / the stamp is unreadable. */
    private UUID ownerOf(ItemStack book) {
        if (book == null || !(book.getItemMeta() instanceof BookMeta meta)) return null;
        return ownerOf(meta);
    }

    private UUID ownerOf(BookMeta meta) {
        String raw = meta.getPersistentDataContainer().get(Keys.bookOwner(), PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null; // hand-crafted / corrupted stamp - treat as unowned rather than throwing in an event handler
        }
    }

    /** The item the book borrowed the hand slot from, or null if the hand was empty (or the stash is unreadable). */
    private ItemStack savedHandOf(ItemStack book) {
        if (book == null || !(book.getItemMeta() instanceof BookMeta meta)) return null;
        return savedHandOf(meta);
    }

    private ItemStack savedHandOf(BookMeta meta) {
        byte[] bytes = meta.getPersistentDataContainer().get(Keys.bookSavedHand(), PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return null;
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException ex) {
            // written by an older/newer server version, or tampered with - losing the stash is bad but
            // throwing out of an event handler (and leaving the book stuck in hand) is worse
            plugin.getLogger().warning("Could not restore the item stashed in a tag-creation book: " + ex.getMessage());
            return null;
        }
    }

    private void restoreHandNextTick(Player player, ItemStack book) {
        restoreHandNextTick(player, book.getItemMeta() instanceof BookMeta meta ? meta : null);
    }

    /**
     * Restores whatever the player's main hand held before the book was placed there (or clears it
     * if their hand was empty). Runs one tick later on purpose - see the comment in onEditBook for why
     * doing this synchronously inside the event doesn't stick.
     *
     * The stash is only ever handed back to the player the book was stamped for: a creation book is a
     * real item that can be dropped and picked up, so without that check anyone who found a stray book
     * could sign it and be handed the original owner's item.
     */
    private void restoreHandNextTick(Player player, BookMeta bookMeta) {
        ItemStack restore = bookMeta != null && player.getUniqueId().equals(ownerOf(bookMeta))
                ? savedHandOf(bookMeta)
                : null;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            ItemStack current = player.getInventory().getItemInMainHand();
            // only touch it if the hand still holds one of our books - if the player somehow already
            // swapped it away in the meantime, don't clobber whatever they're holding now
            if (isOurBook(current)) {
                player.getInventory().setItemInMainHand(restore);
            }
        });
    }
}
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
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

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
        byte[] savedHand = previousMeta.getPersistentDataContainer().get(Keys.bookSavedHand(), PersistentDataType.BYTE_ARRAY);

        if (target.equals("new")) {
            PlayerData data = plugin.data().get(player.getUniqueId(), player.getName());
            boolean stillValid = data.isReservationActive()
                    && reservationId != null
                    && reservationId.equals(data.getReservationId());
            if (!stillValid) {
                // the reservation behind this book was already refunded (they disconnected mid-creation,
                // or this is a stale duplicate of an old book) - reject it instead of granting a free tag
                restoreHandNextTick(player, savedHand);
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
        restoreHandNextTick(player, savedHand);

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
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        boolean removedOne = event.getDrops().removeIf(this::isOurBook);
        if (removedOne) {
            plugin.tagService().handleBookLostToDeath(event.getEntity());
        }
    }

    private boolean isOurBook(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        if (!(item.getItemMeta() instanceof BookMeta meta)) return false;
        return meta.getPersistentDataContainer().has(Keys.bookTarget(), PersistentDataType.STRING);
    }

    /**
     * Restores whatever the player's main hand held before the book was placed there (or clears it
     * if their hand was empty). Runs one tick later on purpose - see the comment in onEditBook for why
     * doing this synchronously inside the event doesn't stick.
     */
    private void restoreHandNextTick(Player player, byte[] savedHandBytes) {
        ItemStack restore = savedHandBytes != null ? ItemStack.deserializeBytes(savedHandBytes) : null;
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
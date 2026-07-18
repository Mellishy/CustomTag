package com.mellishy.customtag.util;

import com.mellishy.customtag.MellishyCustomTag;
import org.bukkit.NamespacedKey;

public final class Keys {

    private Keys() {}

    private static NamespacedKey bookTarget;
    private static NamespacedKey bookReservation;
    private static NamespacedKey bookOriginalText;
    private static NamespacedKey bookSavedHand;

    /** Value stored: "new" for a brand-new tag, or the tag-id being edited. */
    public static NamespacedKey bookTarget() {
        if (bookTarget == null) {
            bookTarget = new NamespacedKey(MellishyCustomTag.getInstance(), "book_target");
        }
        return bookTarget;
    }

    /**
     * Value stored: the exact plain text the player was shown on page 2 when the book was handed out
     * (the default template for a new tag, or the tag's current text when re-editing). Compared against
     * what they submit so a player clicking Done/Sign without actually changing anything can be rejected
     * instead of silently forwarding the unedited default/old text to the admin queue.
     */
    public static NamespacedKey bookOriginalText() {
        if (bookOriginalText == null) {
            bookOriginalText = new NamespacedKey(MellishyCustomTag.getInstance(), "book_original_text");
        }
        return bookOriginalText;
    }

    /**
     * Value stored: the serialized bytes of whatever item was in the player's main hand before the
     * book was placed there (absent if their hand was empty). The book only ever *borrows* the hand
     * slot for the duration of editing - this is what's restored once they finish, so the book is
     * never really "given" to sit permanently in their inventory.
     */
    public static NamespacedKey bookSavedHand() {
        if (bookSavedHand == null) {
            bookSavedHand = new NamespacedKey(MellishyCustomTag.getInstance(), "book_saved_hand");
        }
        return bookSavedHand;
    }

    /**
     * Value stored: the reservation-id (see {@link com.mellishy.customtag.data.PlayerData#getReservationId()})
     * that was active when this book was handed out. Only relevant for brand-new tags ("new" target).
     * When the book is signed, the plugin checks this against the player's CURRENT reservation-id;
     * if it doesn't match (e.g. the reservation was refunded because the player disconnected mid-creation)
     * the book is rejected instead of granting a free tag. This is what makes the leave/rejoin flow dupe-proof.
     */
    public static NamespacedKey bookReservation() {
        if (bookReservation == null) {
            bookReservation = new NamespacedKey(MellishyCustomTag.getInstance(), "book_reservation");
        }
        return bookReservation;
    }
}

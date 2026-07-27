package com.mellishy.customtag.validation;

/**
 * Everything a validator may look at. Built once per submission by
 * {@link ValidationService#validate} so each validator doesn't re-derive the same strings.
 *
 * @param playerName     submitting player's name (for logs only - rules match on text, never on players)
 * @param rawText        exactly what the player typed, colors and all
 * @param plainText      color/format codes stripped ({@code &7[&6King&7]} -> {@code [King]})
 * @param normalizedText lowercase, separators removed, leet-speak and unicode-lookalikes folded -
 *                       the anti-bypass form ({@code O_w-n3r} and {@code Оwner} both become "owner")
 */
public record ValidationInput(String playerName, String rawText, String plainText, String normalizedText) {}

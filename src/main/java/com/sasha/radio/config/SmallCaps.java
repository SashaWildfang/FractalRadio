/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns letters into small capitals ("Now Playing" → "ɴᴏᴡ ᴘʟᴀʏɪɴɢ"), the "tiny text" style. Small capitals
 * only exist as lowercase characters, so text is lowercased first. Digits, punctuation and symbols are
 * kept as they are, and so is any letter without a small-capital form.
 */
final class SmallCaps {

    private static final String FROM = "abcdefghijklmnopqrstuvwxyz";
    private static final String TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    private static final TextReplacementConfig REPLACE_LETTERS = TextReplacementConfig.builder()
            .match(Pattern.compile("\\p{L}+"))
            .replacement((match, builder) -> builder.content(convert(match.group())))
            .build();

    private SmallCaps() {
    }

    static String convert(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int index = FROM.indexOf(c);
            out.append(index >= 0 ? TO.charAt(index) : c);
        }
        return out.toString();
    }

    /** Converts every piece of text in a component (and its children), keeping all colours and styles. */
    static Component apply(Component component) {
        return component.replaceText(REPLACE_LETTERS);
    }
}

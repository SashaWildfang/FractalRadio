/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private static final Logger LOGGER = Logger.getLogger("MessagesTest");

    private static YamlConfiguration builtIn() throws Exception {
        try (InputStream in = MessagesTest.class.getResourceAsStream("/messages.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** A server file from an older version lacks newer keys; the built-in text must fill in, not the key name. */
    @Test
    void missingKeysFallBackToBuiltInText() throws Exception {
        YamlConfiguration server = new YamlConfiguration();
        server.set("prefix", "[Radio] ");
        server.set("format.font", "normal");
        Messages messages = Messages.of(server, builtIn(), LOGGER);

        String boss = plain(messages.component("bar.boss-playing",
                Messages.arg("title", "Animals"), Messages.arg("artist", "Martin Garrix"),
                Messages.arg("elapsed", "1m 3s"), Messages.arg("length", "4m 2s"),
                Messages.arg("source", Component.empty())));
        assertTrue(boss.contains("Animals") && boss.contains("Martin Garrix"), boss);
        assertFalse(boss.contains("bar.boss-playing"), boss);
        assertFalse(boss.contains("4m 2s"), "the boss bar should not show the time: " + boss);

        String action = plain(messages.component("bar.playing",
                Messages.arg("title", "Animals"), Messages.arg("artist", "Martin Garrix"),
                Messages.arg("elapsed", "1m 3s"), Messages.arg("length", "4m 2s"),
                Messages.arg("source", Component.empty())));
        assertTrue(action.contains("1m 3s") && action.contains("4m 2s"), "the action bar keeps the time: " + action);
    }

    /** Text the server owner changed wins over the built-in text. */
    @Test
    void serverTextOverridesBuiltIn() throws Exception {
        YamlConfiguration server = new YamlConfiguration();
        server.set("bar.boss-playing", "Now: <title>");
        server.set("format.font", "normal");
        Messages messages = Messages.of(server, builtIn(), LOGGER);

        assertEquals("Now: Animals", plain(messages.component("bar.boss-playing", Messages.arg("title", "Animals"))));
    }

    /** Lore lists also fall back to the built-in text. */
    @Test
    void missingLoreFallsBackToBuiltIn() throws Exception {
        Messages messages = Messages.of(new YamlConfiguration(), builtIn(), LOGGER);
        assertFalse(messages.lore("gui.page-next.lore", Messages.arg("page", 2), Messages.arg("pages", 5)).isEmpty());
    }

    /** The default font is small caps, applied to template text and to inserted values like song titles. */
    @Test
    void smallCapsIsTheDefaultAndCoversPlaceholders() throws Exception {
        Messages messages = Messages.of(new YamlConfiguration(), builtIn(), LOGGER);
        YamlConfiguration server = new YamlConfiguration();
        server.set("test.line", "<primary>Now playing</primary> <title> 4m 2s!");
        messages = Messages.of(server, builtIn(), LOGGER);

        assertEquals("ɴᴏᴡ ᴘʟᴀʏɪɴɢ ᴀɴɪᴍᴀʟꜱ 4ᴍ 2ꜱ!",
                plain(messages.component("test.line", Messages.arg("title", "Animals"))));
    }

    /** "normal" leaves text alone. */
    @Test
    void normalFontLeavesTextAlone() throws Exception {
        YamlConfiguration server = new YamlConfiguration();
        server.set("format.font", "normal");
        server.set("test.line", "Now playing <title>");
        Messages messages = Messages.of(server, builtIn(), LOGGER);

        assertEquals("Now playing Animals", plain(messages.component("test.line", Messages.arg("title", "Animals"))));
    }

    @Test
    void conversionKeepsDigitsAndSymbols() {
        assertEquals("ᴀʙᴄ xʏᴢ 123 ★ » ü", SmallCaps.convert("ABC xyz 123 ★ » Ü"));
    }
}

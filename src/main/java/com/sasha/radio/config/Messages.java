/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Everything players read, loaded from messages.yml with the bundled file as fallback for missing keys.
 * Theme colours become tags (&lt;primary&gt; etc.), &lt;brand&gt; becomes the theme gradient and &lt;prefix&gt; the prefix.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final YamlConfiguration yaml;
    private final String gradientOpen;
    private final TagResolver theme;
    private final Component on;
    private final Component off;
    private final String barFilled;
    private final String barEmpty;
    private final String barKnob;
    private final int barLength;
    /** format.font: small-caps turns every letter shown to players into tiny text. */
    private final boolean smallCaps;

    private Messages(YamlConfiguration yaml, Logger logger) {
        this.yaml = yaml;
        String font = text("format.font", "small-caps").trim();
        this.smallCaps = !font.equalsIgnoreCase("normal");
        if (!font.equalsIgnoreCase("normal") && !font.equalsIgnoreCase("small-caps")) {
            logger.warning("messages.yml format.font: unknown value '" + font + "', using small-caps");
        }

        List<String> gradient = yaml.getStringList("theme.gradient");
        this.gradientOpen = gradient.size() >= 2 ? "<gradient:" + String.join(":", gradient) + ">" : "<gradient:#8E2DE2:#E58BFF>";

        TagResolver colours = TagResolver.resolver(
                colour("primary", logger), colour("secondary", logger), colour("accent", logger),
                colour("muted", logger), colour("good", logger), colour("bad", logger));
        Component prefix = MM.deserialize(brand(text("prefix", "")), colours);
        if (smallCaps) prefix = SmallCaps.apply(prefix);
        this.theme = TagResolver.resolver(colours, Placeholder.component("prefix", prefix));

        this.on = component("format.on");
        this.off = component("format.off");
        this.barFilled = text("format.progress-filled", "━");
        this.barKnob = brand(text("format.progress-knob", "<white>●"));
        this.barEmpty = brand(text("format.progress-empty", "<dark_gray>━"));
        this.barLength = Math.max(1, Math.min(60, yaml.getInt("format.progress-length")));
    }

    public static Messages load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);

        YamlConfiguration defaults = null;
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    defaults = YamlConfiguration.loadConfiguration(reader);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the default messages.yml: " + e.getMessage());
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // A newer plugin version ships new text: keep the server's old file as a backup and use the new one.
        if (defaults != null && yaml.getInt("messages-version", 1) < defaults.getInt("messages-version", 1)) {
            File backup = new File(plugin.getDataFolder(), "messages.yml.old");
            if (file.renameTo(backup) || (backup.delete() && file.renameTo(backup))) {
                plugin.saveResource("messages.yml", true);
                yaml = YamlConfiguration.loadConfiguration(file);
                plugin.getLogger().info("Updated messages.yml to the new version. Your previous file is saved as messages.yml.old.");
            }
        }
        return of(yaml, defaults, plugin.getLogger());
    }

    /** Messages from a server's file, with the plugin's built-in file filling any gaps. */
    static Messages of(YamlConfiguration yaml, YamlConfiguration defaults, Logger logger) {
        if (defaults != null) yaml.setDefaults(defaults);
        return new Messages(yaml, logger);
    }

    // ---------------------------------------------------------------- chat

    /** Sends a message (or every line of a list message). Empty messages are skipped, so admins can turn them off. */
    public void send(Audience to, String key, TagResolver... args) {
        if (yaml.isList(key)) {
            for (String line : yaml.getStringList(key)) {
                if (!line.isEmpty()) to.sendMessage(parse(line, args));
            }
            return;
        }
        String template = text(key, key);
        if (!template.isEmpty()) to.sendMessage(parse(template, args));
    }

    public Component component(String key, TagResolver... args) {
        return parse(text(key, key), args);
    }

    public String raw(String key) {
        return text(key, key);
    }

    // ---------------------------------------------------------------- items

    /** A single line for an item name, without the default italics. */
    public Component item(String key, TagResolver... args) {
        return parse("<!italic>" + text(key, key), args);
    }

    /**
     * Lore lines. A line that only contained placeholders and rendered to nothing is dropped, so
     * optional lines (badges, search text) vanish instead of leaving a gap.
     */
    public List<Component> lore(String key, TagResolver... args) {
        return lore(key, Map.of(), args);
    }

    /**
     * Lore where a line consisting only of {@code <name>} for a key in {@code blocks} is replaced by
     * those lines (used for lists such as the sort options).
     */
    public List<Component> lore(String key, Map<String, List<Component>> blocks, TagResolver... args) {
        List<Component> lines = new ArrayList<>();
        for (String template : yaml.getStringList(key)) {
            String trimmed = template.strip();
            if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
                List<Component> block = blocks.get(trimmed.substring(1, trimmed.length() - 1));
                if (block != null) {
                    lines.addAll(block);
                    continue;
                }
            }
            Component line = parse("<!italic>" + template, args);
            if (!template.isEmpty() && PLAIN.serialize(line).isBlank()) continue;
            lines.add(line);
        }
        return lines;
    }

    // ---------------------------------------------------------------- pieces

    public Component onOff(boolean value) {
        return value ? on : off;
    }

    /** e.g. ▰▰▰▰▱▱▱▱ for 0.5. */
    public Component progressBar(double fraction) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * barLength);
        // The played part in the brand gradient, a knob where the song is, then the rest.
        StringBuilder bar = new StringBuilder();
        if (filled > 0) bar.append(gradientOpen).append(barFilled.repeat(filled)).append("</gradient>");
        bar.append(barKnob);
        for (int i = filled; i < barLength; i++) bar.append(barEmpty);
        return MM.deserialize(bar.toString(), theme);
    }

    /** Placeholder whose value is inserted as plain text, so tags in song titles or names are never parsed. */
    public static TagResolver arg(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver arg(String key, Component value) {
        return Placeholder.component(key, value);
    }

    /**
     * A string from messages.yml, falling back to the plugin's built-in messages.yml for keys the server's
     * file doesn't have (e.g. added in a newer version), and only then to {@code fallback}.
     * Note: Bukkit's {@code getString(path, def)} skips the built-in defaults, so it must not be used here.
     */
    private String text(String key, String fallback) {
        String value = yaml.getString(key);
        return value != null ? value : fallback;
    }


    /** Whether hints show the resource pack's mouse and Shift pictures instead of words like "Left-click". */
    private volatile boolean buttonIcons;

    public void buttonIcons(boolean on) {
        this.buttonIcons = on;
    }

    /** <left-click> <right-click> <click> <shift>: a picture from the pack (font fractal:lobby), or the words. */
    private TagResolver clicks() {
        return TagResolver.resolver(click("left-click", '\uF010', "Left-click"), click("right-click", '\uF011', "Right-click"),
                click("click", '\uF012', "Click"), click("shift", '\uF013', "Shift + "));
    }

    private TagResolver click(String tag, char glyph, String words) {
        Component shown = buttonIcons
                ? Component.text(glyph).font(net.kyori.adventure.key.Key.key("fractal", "lobby"))
                        .color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                        .shadowColor(net.kyori.adventure.text.format.ShadowColor.none())
                : Component.text(words);
        return TagResolver.resolver(tag, net.kyori.adventure.text.minimessage.tag.Tag.selfClosingInserting(shown));
    }

    private Component parse(String template, TagResolver... args) {
        Component component = MM.deserialize(brand(template), TagResolver.resolver(theme, clicks(), TagResolver.resolver(args)));
        // Applied after placeholders are filled in, so song titles and player names are converted too.
        return smallCaps ? SmallCaps.apply(component) : component;
    }

    private String brand(String template) {
        return template.replace("<brand>", gradientOpen).replace("</brand>", "</gradient>");
    }

    private TagResolver colour(String name, Logger logger) {
        String hex = text("theme." + name, "#FFFFFF");
        TextColor colour = TextColor.fromHexString(hex);
        if (colour == null) {
            logger.warning("messages.yml theme." + name + " is not a hex colour: " + hex);
            colour = TextColor.color(0xFFFFFF);
        }
        return Placeholder.styling(name, colour);
    }
}

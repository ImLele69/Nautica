package it.gabrieledev.nautica.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

// lang files are MiniMessage. Placeholders are passed as name/value pairs and inserted unparsed
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlConfiguration file;
    private final YamlConfiguration defaults;

    public Messages(YamlConfiguration file, YamlConfiguration defaults) {
        this.file = file;
        this.defaults = defaults;
    }

    public Component get(String key, Object... pairs) {
        String raw = file.getString(key, defaults.getString(key, "<red>" + key));
        String prefix = file.getString("prefix", defaults.getString("prefix", ""));
        List<TagResolver> resolvers = new ArrayList<>();
        resolvers.add(Placeholder.parsed("prefix", prefix));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            if (v instanceof Component c) {
                resolvers.add(Placeholder.component(String.valueOf(pairs[i]), c));
            } else {
                resolvers.add(Placeholder.unparsed(String.valueOf(pairs[i]), String.valueOf(v)));
            }
        }
        return MM.deserialize(raw, TagResolver.resolver(resolvers));
    }

    public String plain(String key, Object... pairs) {
        return PlainTextComponentSerializer.plainText().serialize(get(key, pairs));
    }

    public void send(Audience to, String key, Object... pairs) {
        to.sendMessage(get(key, pairs));
    }

    public void actionBar(Audience to, String key, Object... pairs) {
        to.sendActionBar(get(key, pairs));
    }
}

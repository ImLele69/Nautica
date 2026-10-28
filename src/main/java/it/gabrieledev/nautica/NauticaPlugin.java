package it.gabrieledev.nautica;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import it.gabrieledev.nautica.command.ShipCommand;
import it.gabrieledev.nautica.config.Messages;
import it.gabrieledev.nautica.config.NauticaConfig;
import it.gabrieledev.nautica.fixture.FixtureType;
import it.gabrieledev.nautica.fixture.Fixtures;
import it.gabrieledev.nautica.gui.Shipyard;
import it.gabrieledev.nautica.listener.ShipListener;
import it.gabrieledev.nautica.prefab.PrefabStore;
import it.gabrieledev.nautica.sail.ShipController;
import it.gabrieledev.nautica.ship.ShipRegistry;
import it.gabrieledev.nautica.store.SnapshotStore;
import it.gabrieledev.nautica.world.Assembler;
import it.gabrieledev.nautica.world.Protection;
import it.gabrieledev.nautica.world.WorldOps;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class NauticaPlugin extends JavaPlugin {

    // bundled translations, copied to plugins/Nautica/lang on first start
    private static final List<String> LANGUAGES = List.of("en", "it");

    private NauticaConfig config;
    private Messages messages;
    private ShipRegistry registry;
    private SnapshotStore store;
    private PrefabStore prefabs;
    private Protection protection;
    private Assembler assembler;
    private ShipController controller;
    private final List<NamespacedKey> recipes = new ArrayList<>();

    @Override
    public void onEnable() {
        Keys.init(this);
        saveDefaultConfig();
        for (String lang : LANGUAGES) {
            if (!new File(getDataFolder(), "lang/" + lang + ".yml").exists()) {
                saveResource("lang/" + lang + ".yml", false);
            }
        }
        try {
            store = new SnapshotStore(getDataFolder().toPath(), getLogger());
            prefabs = new PrefabStore(getDataFolder().toPath(), getLogger());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Can't create the data folders, disabling Nautica", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        registry = new ShipRegistry();
        protection = new Protection();
        buildRuntime();
        prefabs.installBuiltins();
        controller.recoverAll();
        controller.start();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new ShipCommand(this).build(), "Nautica ships", List.of("nave")));
        if (config.recipes) {
            registerRecipes();
        }
        getLogger().info("Nautica ready: " + registry.all().size() + " ships, "
                + config.blocks.allowedCount() + " allowed blocks");
    }

    @Override
    public void onDisable() {
        if (controller != null) {
            controller.shutdown();
        }
        for (NamespacedKey key : recipes) {
            Bukkit.removeRecipe(key);
        }
        if (store != null) {
            store.close();
        }
    }

    private void buildRuntime() {
        reloadConfig();
        config = new NauticaConfig(getConfig(), getLogger());
        messages = loadMessages(getConfig().getString("language", "en"));
        Fixtures.useMessages(messages);
        assembler = new Assembler(config, registry, protection);
        WorldOps ops = new WorldOps(config, protection, getLogger());
        controller = new ShipController(this, config, messages, registry, store, assembler, ops);
        getServer().getPluginManager().registerEvents(
                new ShipListener(this, config, messages, registry, controller, protection), this);
        getServer().getPluginManager().registerEvents(new Shipyard.Clicks(controller, config, messages), this);
    }

    // rebuilds config and runtime. Only allowed with no ship at sea, so no voyage holds the old config
    public void reloadAll() {
        controller.shutdown();
        HandlerList.unregisterAll(this);
        buildRuntime();
        controller.start();
    }

    // keys missing from the file on disk fall back to the bundled copy of the same
    // language, then to english, so an old translation never shows raw keys
    private Messages loadMessages(String lang) {
        File file = new File(getDataFolder(), "lang/" + lang + ".yml");
        if (!file.exists()) {
            getLogger().warning("lang/" + lang + ".yml not found, using english");
            lang = "en";
            file = new File(getDataFolder(), "lang/en.yml");
        }
        YamlConfiguration defaults = bundled("en");
        if (!lang.equals("en")) {
            YamlConfiguration own = bundled(lang);
            own.setDefaults(defaults);
            defaults = own;
        }
        return new Messages(YamlConfiguration.loadConfiguration(file), defaults);
    }

    private YamlConfiguration bundled(String lang) {
        YamlConfiguration y = new YamlConfiguration();
        var in = getResource("lang/" + lang + ".yml");
        if (in == null) {
            return y;
        }
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            y.load(r);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "bundled lang/" + lang + ".yml is broken", e);
        }
        return y;
    }

    private void registerRecipes() {
        addRecipe(FixtureType.HELM, new String[]{" S ", "SPS", " S "},
                'S', new RecipeChoice.MaterialChoice(Material.STICK), 'P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        addRecipe(FixtureType.SEAT, new String[]{"C", "S"},
                'C', new RecipeChoice.MaterialChoice(Tag.WOOL_CARPETS), 'S', new RecipeChoice.MaterialChoice(Tag.WOODEN_SLABS));
        addRecipe(FixtureType.PAD, new String[]{"L", "H"},
                'L', new RecipeChoice.MaterialChoice(Material.LEAD), 'H', new RecipeChoice.MaterialChoice(Material.HAY_BLOCK));
        addRecipe(FixtureType.PERCH, new String[]{"F", "S"},
                'F', new RecipeChoice.MaterialChoice(Material.FEATHER), 'S', new RecipeChoice.MaterialChoice(Tag.WOODEN_FENCES));
    }

    private void addRecipe(FixtureType type, String[] shape, Object... ingredients) {
        NamespacedKey key = new NamespacedKey(this, type.id());
        ShapedRecipe recipe = new ShapedRecipe(key, Fixtures.item(type));
        recipe.shape(shape);
        for (int i = 0; i < ingredients.length; i += 2) {
            recipe.setIngredient((Character) ingredients[i], (RecipeChoice) ingredients[i + 1]);
        }
        if (Bukkit.addRecipe(recipe)) {
            recipes.add(key);
        }
    }

    public NauticaConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public ShipRegistry registry() {
        return registry;
    }

    public PrefabStore prefabs() {
        return prefabs;
    }

    public Assembler assembler() {
        return assembler;
    }

    public ShipController controller() {
        return controller;
    }

    public Protection protection() {
        return protection;
    }
}

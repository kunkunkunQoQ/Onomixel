package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads custom onomatopoeia overrides from:
 * Tier 3: Local configuration (.minecraft/config/onomixel/custom_words.json)
 * Tier 2: Active resource packs (assets/&lt;namespace&gt;/onomixel/custom_words.json and sound_overrides.json)
 */
public final class SoundOverrideLoader {
    private static final System.Logger LOGGER = System.getLogger(SoundOverrideLoader.class.getName());

    private static final String EXAMPLE_CONFIG_JSON = """
    {
      "$schema": "https://onomixel.kunkun.dev/schemas/v1/custom_words.json",
      "priority": 100,
      "comment": "Onomixel 漫画拟声词自定义配置模板（支持日漫、美漫、加权随机池与动效包络）",
      "overrides": [
        {
          "comment": "【美式波普风格】TNT 点燃爆轰：经典大写撞色 + 方案1定格爆刺",
          "match": "minecraft:entity.tnt.primed",
          "display": {
            "mode": "WEIGHTED_RANDOM",
            "variants": [
              { "text": "KABOOM!!", "weight": 70 },
              { "text": "BAAAAM!!", "weight": 20 },
              { "text": "POW!!", "weight": 10 }
            ]
          },
          "style": {
            "kinetic_motion": "HITSTOP",
            "vibrant_color": "#FACC15",
            "pastel_color": "#FEF08A",
            "bold": true,
            "scale_multiplier": 1.35
          },
          "physics": {
            "height_offset": 0.50
          }
        },
        {
          "comment": "【战术紧迫感】苦力怕引线燃烧：加权多变体 + 24fps 高频战栗颤抖",
          "match": "minecraft:entity.creeper.primed",
          "display": {
            "mode": "WEIGHTED_RANDOM",
            "variants": [
              { "text": "嘶——！", "weight": 60 },
              { "text": "呲呲呲——！", "weight": 30 },
              { "text": "危！！", "weight": 10 }
            ]
          },
          "style": {
            "kinetic_motion": "SHAKE",
            "vibrant_color": "#EF4444",
            "bold": true
          }
        },
        {
          "comment": "【日式热血风格】暴击斩击：热血汉字拟声词 + 极光金刀光",
          "match": "minecraft:entity.player.attack.crit",
          "display": {
            "mode": "SINGLE",
            "text": "斩！！"
          },
          "style": {
            "kinetic_motion": "HITSTOP",
            "vibrant_color": "#F59E0B",
            "bold": true,
            "italic": true,
            "scale_multiplier": 1.25
          }
        },
        {
          "comment": "【通配符批量规则】将所有骷髅类生物的射箭声音统一应用针状极速刺出动效",
          "match": "minecraft:entity.skeleton*.shoot",
          "display": {
            "mode": "SINGLE",
            "text": "嗖——！"
          },
          "style": {
            "kinetic_motion": "PIERCE",
            "vibrant_color": "#0EA5E9",
            "scale_multiplier": 1.10
          }
        }
      ]
    }
    """;

    public void onResourceManagerReload(ResourceManager manager) {
        reloadAll(manager);
    }

    /**
     * Hot-reloads all custom words and style rules across all tiers from client environment.
     * Can be invoked from the in-game configuration GUI or developer keybinds.
     *
     * @return count of activated rules
     */
    public static int reloadAll() {
        ResourceManager manager = null;
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            if (mc != null) {
                manager = (ResourceManager) mcClass.getMethod("getResourceManager").invoke(mc);
            }
        } catch (Throwable ignored) {
            // Headless or test environment fallback
        }
        return reloadAll(manager);
    }

    /**
     * Hot-reloads all custom words and style rules from active resource packs and local config.
     */
    public static int reloadAll(ResourceManager manager) {
        List<String> packJsons = new ArrayList<>();

        // 1. Gather all modern custom_words.json from active resource packs
        if (manager != null) {
            try {
                Map<Identifier, Resource> customWordResources = manager.listResources("onomixel",
                        id -> id.getPath().endsWith("custom_words.json"));
                for (Map.Entry<Identifier, Resource> entry : customWordResources.entrySet()) {
                    try (Reader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                        packJsons.add(readFully(reader));
                    } catch (Exception e) {
                        LOGGER.log(System.Logger.Level.WARNING, "Failed to read custom_words from resource pack " + entry.getKey() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to query custom_words from resource manager: " + e.getMessage());
            }

            // 2. Gather legacy sound_overrides.json for backward compatibility
            try {
                Map<Identifier, Resource> legacyResources = manager.listResources("onomixel",
                        id -> id.getPath().endsWith("sound_overrides.json"));
                for (Map.Entry<Identifier, Resource> entry : legacyResources.entrySet()) {
                    try (Reader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                        String content = readFully(reader);
                        // Also apply legacy parser
                        List<SoundOverrideParser.OverrideEntry> parsed = SoundOverrideParser.parse(content);
                        SoundOverrideParser.apply(parsed);
                        packJsons.add(content);
                    } catch (Exception e) {
                        LOGGER.log(System.Logger.Level.WARNING, "Failed to load legacy sound overrides from " + entry.getKey() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to query legacy sound overrides: " + e.getMessage());
            }
        }

        // 3. Locate local config directory (.minecraft/config/onomixel)
        Path configDir = resolveConfigDir();
        Path localConfigFile = null;
        if (configDir != null) {
            try {
                if (!Files.exists(configDir)) {
                    Files.createDirectories(configDir);
                }
                // Create example file if not present
                Path exampleFile = configDir.resolve("custom_words.example.json");
                if (!Files.exists(exampleFile)) {
                    Files.writeString(exampleFile, EXAMPLE_CONFIG_JSON);
                }
                Path customFile = configDir.resolve("custom_words.json");
                if (Files.isRegularFile(customFile)) {
                    localConfigFile = customFile;
                }
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not setup onomixel config directory: " + e.getMessage());
            }
        }

        // 4. Reload all tiers into SoundCustomizationManager
        int loadedCount = SoundCustomizationManager.reloadAll(localConfigFile, packJsons);
        LOGGER.log(System.Logger.Level.INFO, "Onomixel: Hot-reloaded custom words. Successfully activated {0} custom rules.", loadedCount);
        return loadedCount;
    }

    private static String readFully(Reader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1024];
        int read;
        while ((read = reader.read(buf)) != -1) {
            sb.append(buf, 0, read);
        }
        return sb.toString();
    }

    public static Path resolveConfigDir() {
        try {
            return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("onomixel");
        } catch (Throwable ignored) {
            return Path.of("config", "onomixel");
        }
    }

    /**
     * Opens the local onomatopoeia configuration folder in the system file explorer.
     */
    public static void openConfigFolder() {
        Path configDir = resolveConfigDir();
        if (configDir != null) {
            try {
                if (!Files.exists(configDir)) {
                    Files.createDirectories(configDir);
                }
                Path exampleFile = configDir.resolve("custom_words.example.json");
                if (!Files.exists(exampleFile)) {
                    Files.writeString(exampleFile, EXAMPLE_CONFIG_JSON);
                }
            } catch (Throwable ignored) {}

            try {
                com.mojang.blaze3d.Blaze3D.openPath(configDir);
                return;
            } catch (Throwable t) {
                try {
                    if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                        java.awt.Desktop.getDesktop().open(configDir.toFile());
                        return;
                    }
                } catch (Throwable ignored) {}
                try {
                    new ProcessBuilder("explorer.exe", configDir.toAbsolutePath().toString()).start();
                } catch (Throwable ignored) {}
            }
        }
    }
}

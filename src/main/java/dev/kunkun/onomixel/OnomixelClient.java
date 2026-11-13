package dev.kunkun.onomixel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kunkun.onomixel.api.OnomixelAPI;
import dev.kunkun.onomixel.custom.CustomWordParser;
import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.gui.OnomixelConfigScreen;
import dev.kunkun.onomixel.render.WorldSubtitleRenderer;
import dev.kunkun.onomixel.render.ScreenRadarRenderer;
import dev.kunkun.onomixel.sound.ClientSoundCapture;
import dev.kunkun.onomixel.sound.BuiltinSoundMappings;
import dev.kunkun.onomixel.sound.SoundOverrideLoader;
import dev.kunkun.onomixel.subtitle.ThreatRegistry;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.InterModComms;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.InterModProcessEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Client-only entry point; no world entities, server registrations or vanilla subtitle changes. */
@Mod(value = OnomixelClient.MOD_ID, dist = Dist.CLIENT)
public final class OnomixelClient {
    public static final String MOD_ID = "onomixel";
    private static final Logger LOGGER = LoggerFactory.getLogger(OnomixelClient.class);

    public OnomixelClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, OnomixelConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new OnomixelConfigScreen(parent));
        ClientSoundCapture capture = new ClientSoundCapture();
        NeoForge.EVENT_BUS.register(capture);
        WorldSubtitleRenderer renderer = new WorldSubtitleRenderer(capture);
        NeoForge.EVENT_BUS.register(renderer);
        ScreenRadarRenderer radar = new ScreenRadarRenderer(capture);
        NeoForge.EVENT_BUS.register(radar);
        modBus.addListener(capture::onSoundEngineLoaded);
        modBus.addListener(renderer::onReloadListeners);
        modBus.addListener(this::onInterModProcess);
        modBus.addListener((AddClientReloadListenersEvent event) ->
                event.addListener(Identifier.fromNamespaceAndPath("onomixel", "sound_overrides"),
                        (net.minecraft.server.packs.resources.ResourceManagerReloadListener) SoundOverrideLoader::reloadAll));
        LOGGER.info("Onomixel loaded: {} mapped sounds, dynamic local layout and semantic world-text animation enabled.",
                BuiltinSoundMappings.mappings().size());
    }

    private void onInterModProcess(InterModProcessEvent event) {
        OnomixelAPI api = OnomixelAPI.get();
        InterModComms.getMessages(MOD_ID).forEach(msg -> {
            try {
                handleImcMessage(api, msg);
            } catch (Exception e) {
                LOGGER.warn("Ignoring malformed IMC message '{}' from '{}': {}",
                        msg.method(), msg.senderModId(), e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void handleImcMessage(OnomixelAPI api, InterModComms.IMCMessage msg) {
        String method = msg.method() == null ? "" : msg.method().trim().toLowerCase(Locale.ROOT);
        Object payload = msg.messageSupplier().get();
        if (payload == null) {
            return;
        }
        switch (method) {
            case "run_api" -> {
                if (payload instanceof Consumer<?> consumer) {
                    ((Consumer<OnomixelAPI>) consumer).accept(api);
                }
            }
            case "register_rules_json" -> {
                if (payload instanceof String json && !json.isBlank()) {
                    List<CustomWordRule> rules = CustomWordParser.parse(json);
                    if (!rules.isEmpty()) {
                        api.registerCustomRules(rules);
                    }
                }
            }
            case "register_custom_word" -> {
                if (payload instanceof String[] pair && pair.length >= 2) {
                    api.registerCustomWord(pair[0], pair[1]);
                } else if (payload instanceof Map<?, ?> map) {
                    String soundId = asString(map.get("sound_id"));
                    String text = asString(map.get("text"));
                    if (soundId != null && text != null) {
                        api.registerCustomWord(soundId, text);
                    }
                } else if (payload instanceof String json) {
                    JsonObject obj = parseObject(json);
                    if (obj != null && obj.has("sound_id") && obj.has("text")) {
                        api.registerCustomWord(obj.get("sound_id").getAsString(), obj.get("text").getAsString());
                    }
                }
            }
            case "register_radar_threat" -> {
                if (payload instanceof Map<?, ?> map) {
                    String soundId = asString(map.get("sound_id"));
                    String levelStr = asString(map.get("level"));
                    long durMs = asLong(map.get("duration_ms"), 0L);
                    String label = asString(map.get("label"));
                    if (soundId != null && levelStr != null) {
                        api.registerRadarThreat(soundId, ThreatRegistry.ThreatLevel.valueOf(levelStr.toUpperCase(Locale.ROOT)), durMs, label);
                    }
                } else if (payload instanceof String json) {
                    JsonObject obj = parseObject(json);
                    if (obj != null && obj.has("sound_id") && obj.has("level")) {
                        long durMs = obj.has("duration_ms") ? obj.get("duration_ms").getAsLong() : 0L;
                        String label = obj.has("label") ? obj.get("label").getAsString() : null;
                        api.registerRadarThreat(
                                obj.get("sound_id").getAsString(),
                                ThreatRegistry.ThreatLevel.valueOf(obj.get("level").getAsString().trim().toUpperCase(Locale.ROOT)),
                                durMs,
                                label
                        );
                    }
                }
            }
            case "register_local_player_action" -> {
                if (payload instanceof Map<?, ?> map) {
                    String soundId = asString(map.get("sound_id"));
                    String cat = asString(map.get("category"));
                    double radius = map.containsKey("max_radius") ? ((Number) map.get("max_radius")).doubleValue() : 1.0;
                    if (soundId != null) {
                        api.registerLocalPlayerActionSound(soundId, cat, radius);
                    }
                } else if (payload instanceof String json) {
                    JsonObject obj = parseObject(json);
                    if (obj != null && obj.has("sound_id")) {
                        String cat = obj.has("category") && !obj.get("category").isJsonNull() ? obj.get("category").getAsString() : null;
                        double radius = obj.has("max_radius") ? obj.get("max_radius").getAsDouble() : 1.0;
                        api.registerLocalPlayerActionSound(obj.get("sound_id").getAsString(), cat, radius);
                    }
                }
            }
            default -> LOGGER.debug("Unsupported Onomixel IMC method '{}' from '{}'", method, msg.senderModId());
        }
    }

    private static JsonObject parseObject(String json) {
        JsonElement el = JsonParser.parseString(json);
        return el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String asString(Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static long asLong(Object v, long fallback) {
        return v instanceof Number n ? n.longValue() : fallback;
    }
}

package dev.kunkun.onomixel;

import dev.kunkun.onomixel.gui.OnomixelConfigScreen;
import dev.kunkun.onomixel.render.WorldSubtitleRenderer;
import dev.kunkun.onomixel.render.ScreenRadarRenderer;
import dev.kunkun.onomixel.sound.ClientSoundCapture;
import dev.kunkun.onomixel.sound.BuiltinSoundMappings;
import dev.kunkun.onomixel.sound.SoundOverrideLoader;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        modBus.addListener((AddClientReloadListenersEvent event) ->
                event.addListener(Identifier.fromNamespaceAndPath("onomixel", "sound_overrides"),
                        (net.minecraft.server.packs.resources.ResourceManagerReloadListener) SoundOverrideLoader::reloadAll));
        LOGGER.info("Onomixel loaded: {} mapped sounds, dynamic local layout and semantic world-text animation enabled.",
                BuiltinSoundMappings.mappings().size());
    }
}


package dev.kunkun.onomixel.sound;

import java.util.Set;

/** Source-body estimates for feet-based living sounds; block-fired projectiles keep their actual origin. */
public final class SoundSourceProfile {
    private static final Set<String> PLAYER_BODY_SOUNDS = Set.of(
            "minecraft:entity.player.hurt", "minecraft:entity.player.hurt_drown",
            "minecraft:entity.player.hurt_freeze", "minecraft:entity.player.hurt_on_fire",
            "minecraft:entity.player.hurt_sweet_berry_bush", "minecraft:entity.player.death",
            "minecraft:entity.player.breath", "minecraft:entity.player.burp",
            "minecraft:entity.player.levelup", "minecraft:entity.player.teleport",
            "minecraft:entity.player.splash", "minecraft:entity.player.splash.high_speed",
            "minecraft:entity.player.swim", "minecraft:entity.player.attack.crit",
            "minecraft:entity.player.attack.knockback", "minecraft:entity.player.attack.nodamage",
            "minecraft:entity.player.attack.strong", "minecraft:entity.player.attack.sweep",
            "minecraft:entity.player.attack.weak"
    );
    private static final Set<String> LIVING_ACTION_SOUNDS = Set.of(
            "minecraft:entity.generic.eat", "minecraft:entity.generic.drink",
            "minecraft:item.honey_bottle.drink",
            "minecraft:item.crossbow.loading_start", "minecraft:item.crossbow.loading_middle",
            "minecraft:item.crossbow.loading_end", "minecraft:item.crossbow.quick_charge_1",
            "minecraft:item.crossbow.quick_charge_2", "minecraft:item.crossbow.quick_charge_3",
            "minecraft:item.armor.equip_chain", "minecraft:item.armor.equip_copper",
            "minecraft:item.armor.equip_diamond", "minecraft:item.armor.equip_elytra",
            "minecraft:item.armor.equip_generic", "minecraft:item.armor.equip_gold",
            "minecraft:item.armor.equip_iron", "minecraft:item.armor.equip_leather",
            "minecraft:item.armor.equip_nautilus", "minecraft:item.armor.equip_netherite",
            "minecraft:item.armor.equip_turtle", "minecraft:item.armor.equip_wolf",
            "minecraft:item.armor.unequip_nautilus", "minecraft:item.armor.unequip_wolf",
            "minecraft:item.bundle.insert", "minecraft:item.bundle.insert_fail",
            "minecraft:item.bundle.remove_one", "minecraft:item.spyglass.use",
            "minecraft:item.spyglass.stop_using", "minecraft:entity.fishing_bobber.throw",
            "minecraft:entity.fishing_bobber.retrieve", "minecraft:entity.egg.throw",
            "minecraft:entity.snowball.throw", "minecraft:entity.ender_pearl.throw",
            "minecraft:entity.ender_eye.launch", "minecraft:item.mace.smash_air",
            "minecraft:item.spear_wood.attack", "minecraft:item.spear_wood.hit",
            "minecraft:item.spear_wood.use", "minecraft:item.spear.attack",
            "minecraft:item.spear.hit", "minecraft:item.spear.use"
    );

    private SoundSourceProfile() {}

    public static double height(String id, String category) {
        if (SoundEventPolicy.isGroundContact(id)) return 0;
        double mob = SoundPlacement.mobHeight(id);
        if (mob > 0) return mob;
        if (id == null || category == null || category.equals("BLOCKS")) return 0;
        if (!(category.equals("PLAYERS") || category.equals("NEUTRAL") || category.equals("HOSTILE"))) return 0;
        if (PLAYER_BODY_SOUNDS.contains(id)) return 1.80;
        if (LIVING_ACTION_SOUNDS.contains(id)) return 1.65;
        return switch (id) {
            case "minecraft:entity.arrow.shoot", "minecraft:item.crossbow.shoot" -> 1.65;
            default -> 0;
        };
    }
}

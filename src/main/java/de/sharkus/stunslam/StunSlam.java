package de.sharkus.stunslam;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Voll-automatischer Stun Slam. Du musst NICHT mehr selbst schlagen.
 *
 * Ablauf:
 *  1. Du faellst (ab ~1 Block) und ein Spieler ist in der Naehe -> Mace wird automatisch in die Hand genommen.
 *  2. Sobald dein Fadenkreuz auf einen Gegner in Reichweite zeigt, schlaegt die Mod selbst zu:
 *     - SMART-Modus: Blockt der Gegner mit Schild -> Axt-Schlag (Schild aus), dann Mace-Smash.
 *                    Blockt er nicht -> sofort Mace-Smash.
 *     - IMMER-COMBO: immer Axt zuerst, dann Mace.
 *
 * Taste G = an/aus, Taste H = Modus wechseln.
 */
public class StunSlam implements ClientModInitializer {
    private static KeyMapping toggleKey;
    private static KeyMapping modeKey;
    private static boolean enabled = false;
    private static boolean alwaysCombo = false; // false = Smart

    private enum Stage { IDLE, AXE_HIT, WAIT, MACE_HIT }
    private static Stage stage = Stage.IDLE;
    private static int axeSlot = -1;
    private static int maceSlot = -1;
    private static int timer = 0;
    private static int cooldown = 0;

    private static final double PREPARE_FALL = 1.0;   // ab hier wird die Mace automatisch gezogen
    private static final double SMASH_FALL = 1.5;     // ab hier zaehlt ein Treffer als Smash
    private static final double NEAR_RANGE = 7.0;     // Gegner in dieser Naehe -> Mace vorbereiten
    private static final double REACH = 3.0;          // Schlagreichweite
    private static final int AXE_TO_MACE_DELAY = 1;   // Ticks zwischen Axt- und Mace-Schlag

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("stunslam", "main"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.stunslam.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_G, category));
        modeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.stunslam.mode", InputConstants.Type.KEYSYM, InputConstants.KEY_H, category));
        ClientTickEvents.END_CLIENT_TICK.register(StunSlam::tick);
    }

    private static void say(Minecraft mc, String text) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
    }

    private static void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            stage = Stage.IDLE;
            say(mc, "Auto Stun Slam: " + (enabled ? "§aAN" : "§cAUS")
                    + " §7(" + (alwaysCombo ? "Immer Combo" : "Smart") + ")");
        }
        while (modeKey.consumeClick()) {
            alwaysCombo = !alwaysCombo;
            say(mc, "Stun-Slam-Modus: §e" + (alwaysCombo
                    ? "Immer Combo (Axt, dann Mace)"
                    : "Smart (Axt nur wenn der Gegner blockt)"));
        }

        LocalPlayer p = mc.player;
        if (!enabled || p == null || mc.level == null || mc.gameMode == null) return;

        if (p.onGround()) {
            // gelandet: Ablauf nur zuruecksetzen, wenn er nicht mitten im Combo steckt
            if (stage == Stage.IDLE) { if (cooldown > 0) cooldown--; return; }
        }
        if (cooldown > 0 && stage == Stage.IDLE) { cooldown--; return; }

        findSlots(p);

        switch (stage) {
            case IDLE -> idle(mc, p);
            case AXE_HIT -> {
                Entity t = target(mc, p);
                if (t == null || axeSlot < 0) { stage = Stage.IDLE; return; }
                select(p, axeSlot);
                hit(mc, p, t);
                timer = AXE_TO_MACE_DELAY;
                stage = Stage.WAIT;
            }
            case WAIT -> {
                if (--timer <= 0) stage = Stage.MACE_HIT;
            }
            case MACE_HIT -> {
                select(p, maceSlot);
                Entity t = target(mc, p);
                if (t != null) hit(mc, p, t);
                stage = Stage.IDLE;
                cooldown = 10;
            }
        }
    }

    private static void idle(Minecraft mc, LocalPlayer p) {
        if (maceSlot < 0 || p.onGround() || p.isFallFlying()) return;
        if (p.fallDistance < PREPARE_FALL) return;

        // 1) Mace automatisch vorbereiten, sobald ein Spieler in der Naehe ist
        if (playerNearby(mc, p)) select(p, maceSlot);

        // 2) Zielen musst du selbst - sobald der Gegner im Fadenkreuz ist, geht es automatisch los
        Entity t = target(mc, p);
        if (t == null || p.fallDistance < SMASH_FALL) return;

        boolean blocking = t instanceof LivingEntity le && le.isBlocking();
        if ((alwaysCombo || blocking) && axeSlot >= 0) {
            stage = Stage.AXE_HIT;       // Schild ausschalten, dann Mace
        } else {
            select(p, maceSlot);          // kein Schild -> direkt Smash
            hit(mc, p, t);
            cooldown = 10;
        }
    }

    private static Entity target(Minecraft mc, LocalPlayer p) {
        Entity t = mc.crosshairPickEntity;
        if (t instanceof LivingEntity le && le.isAlive() && p.distanceTo(t) <= REACH) return t;
        return null;
    }

    private static boolean playerNearby(Minecraft mc, LocalPlayer p) {
        for (AbstractClientPlayer o : mc.level.players()) {
            if (o != p && o.isAlive() && p.distanceTo(o) <= NEAR_RANGE) return true;
        }
        return false;
    }

    private static void hit(Minecraft mc, LocalPlayer p, Entity t) {
        mc.gameMode.attack(p, t);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static void findSlots(LocalPlayer p) {
        axeSlot = -1;
        maceSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            if (axeSlot < 0 && s.is(ItemTags.AXES)) axeSlot = i;
            if (maceSlot < 0 && s.is(Items.MACE)) maceSlot = i;
        }
    }

    private static void select(LocalPlayer p, int slot) {
        if (slot >= 0) p.getInventory().setSelectedSlot(slot);
    }
}

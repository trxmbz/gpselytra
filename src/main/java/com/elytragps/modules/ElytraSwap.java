package com.elytragps.modules;

import com.elytragps.ElytraGpsAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

public class ElytraSwap extends Module {
    private static final List<Item> CHESTPLATES = List.of(
        Items.NETHERITE_CHESTPLATE,
        Items.DIAMOND_CHESTPLATE,
        Items.IRON_CHESTPLATE,
        Items.GOLDEN_CHESTPLATE,
        Items.CHAINMAIL_CHESTPLATE,
        Items.LEATHER_CHESTPLATE
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFirework = settings.createGroup("Start on firework");

    @SuppressWarnings("unused")
    private final Setting<Keybind> swapKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("swap-key")
        .description("Бинд: поменять элитры на нагрудник из инвентаря и обратно. Работает, пока модуль включён.")
        .defaultValue(Keybind.none())
        .action(this::swap)
        .build()
    );

    private final Setting<Boolean> closeInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("close-inventory")
        .description("Отправлять закрытие инвентаря после обмена (как делает ChestSwap).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> startOnFirework = sgFirework.add(new BoolSetting.Builder()
        .name("start-on-firework")
        .description("Когда элитры раскрылись — сразу запускать фейерверк из хотбара/оффхенда.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgFirework.add(new IntSetting.Builder()
        .name("delay")
        .description("Задержка (тики) между раскрытием элитр и фейерверком.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 10)
        .visible(startOnFirework::get)
        .build()
    );

    private final Setting<Integer> cooldown = sgFirework.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Минимальная пауза (тики) между автозапусками, чтобы не тратить фейерверки зря.")
        .defaultValue(40)
        .range(0, 200)
        .sliderRange(0, 100)
        .visible(startOnFirework::get)
        .build()
    );

    private boolean wasFlying = false;
    private int pendingDelay = -1;
    private int cooldownTicks = 0;

    public ElytraSwap() {
        super(ElytraGpsAddon.CATEGORY, "elytra-swap", "Меняет элитры и нагрудник по бинду; может запускать фейерверк при раскрытии элитр.");
    }

    @Override
    public void onActivate() {
        wasFlying = mc.player != null && mc.player.isFallFlying();
        pendingDelay = -1;
    }

    public void swap() {
        if (mc.player == null || mc.gameMode == null) return;

        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);

        if (chest.has(DataComponents.GLIDER)) {
            if (!equipChestplate()) warning("В инвентаре нет нагрудника.");
        } else {
            if (!equipElytra()) warning("В инвентаре нет элитр.");
        }
    }

    private boolean equipChestplate() {
        List<ItemStack> items = mc.player.getInventory().getNonEquipmentItems();

        for (Item wanted : CHESTPLATES) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getItem() == wanted) {
                    equip(i);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean equipElytra() {
        List<ItemStack> items = mc.player.getInventory().getNonEquipmentItems();

        int best = -1;
        int bestLeft = -1;

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.has(DataComponents.GLIDER)) continue;

            int left = stack.getMaxDamage() - stack.getDamageValue();
            if (left <= 1) continue;

            if (left > bestLeft) {
                bestLeft = left;
                best = i;
            }
        }

        if (best == -1) return false;
        equip(best);
        return true;
    }

    private void equip(int slot) {
        InvUtils.move().from(slot).toArmor(2);

        if (closeInventory.get() && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundContainerClosePacket(0));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (cooldownTicks > 0) cooldownTicks--;

        boolean flying = mc.player.isFallFlying();

        if (startOnFirework.get() && flying && !wasFlying && cooldownTicks == 0) {
            pendingDelay = delay.get();
        }
        wasFlying = flying;

        if (pendingDelay < 0) return;

        if (!flying) {
            pendingDelay = -1;
            return;
        }

        if (pendingDelay > 0) {
            pendingDelay--;
            return;
        }

        pendingDelay = -1;

        if (Modules.get().get(ElytraGps.class).isNavigating()) return;

        if (launchFirework()) cooldownTicks = cooldown.get();
    }

    private boolean launchFirework() {
        FindItemResult rocket = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!rocket.found()) {
            warning("Нет фейерверков в хотбаре/оффхенде.");
            return false;
        }

        if (rocket.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            mc.player.swing(InteractionHand.OFF_HAND, mc.player.getOffhandItem().getInteractAnimation(), false);
        } else {
            InvUtils.swap(rocket.slot(), true);

            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getInteractAnimation(), false);

            InvUtils.swapBack();
        }
        return true;
    }
}

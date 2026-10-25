package com.elytragps.modules;

import com.elytragps.ElytraGpsAddon;
import com.elytragps.util.Point;
import com.elytragps.util.PointFile;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class ElytraGps extends Module {
    public enum LandMode {
        LastPoint,
        EveryPoint,
        Never
    }

    private enum Phase {
        CLIMB, CRUISE, LANDING, WALK, HOLD
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgTakeoff = settings.createGroup("Takeoff (fireworks)");
    private final SettingGroup sgLanding = settings.createGroup("Landing");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----

    private final Setting<String> fileName = sgGeneral.add(new StringSetting.Builder()
        .name("file-name")
        .description("Файл с точками (ищется в .minecraft/meteor-client/, затем в .minecraft/).")
        .defaultValue("pointelytra.txt")
        .build()
    );

    @SuppressWarnings("unused")
    private final Setting<Keybind> nextPointKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("next-point-key")
        .description("Бинд: следующая точка (то же, что .nextpoint). Работает пока маршрут активен.")
        .defaultValue(Keybind.none())
        .action(this::nextPoint)
        .build()
    );

    @SuppressWarnings("unused")
    private final Setting<Keybind> stopKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("stop-key")
        .description("Бинд: остановить маршрут (то же, что .gps stop). Работает пока

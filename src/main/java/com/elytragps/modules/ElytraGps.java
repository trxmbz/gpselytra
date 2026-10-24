
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
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class ElytraGps extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgFireworks = settings.createGroup("Fireworks");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<String> fileName = sgGeneral.add(
        new StringSetting.Builder()
            .name("file-name")
            .description("Файл с координатами.")
            .defaultValue("pointelytra.txt")
            .build()
    );

    private final Setting<Keybind> nextPointKey = sgGeneral.add(
        new KeybindSetting.Builder()
            .name("next-point-key")
            .description("Продолжить маршрут или пропустить текущую точку.")
            .defaultValue(Keybind.none())
            .action(this::nextPoint)
            .build()
    );

    private final Setting<Boolean> autoAdvance = sgGeneral.add(
        new BoolSetting.Builder()
            .name("auto-advance")
            .description("Автоматически продолжать маршрут после прибытия.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> startNearest = sgGeneral.add(
        new BoolSetting.Builder()
            .name("start-nearest")
            .description("Сначала выбирать ближайшую непосещённую точку.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> autoTakeoff = sgGeneral.add(
        new BoolSetting.Builder()
            .name("auto-takeoff")
            .description("Автоматически прыгать и раскрывать элитры.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(
        new BoolSetting.Builder()
            .name("chat-feedback")
            .description("Показывать сообщения о маршруте.")
            .defaultValue(true)
            .build()
    );

    // Elytra safety

    private final Setting<Boolean> autoReplaceElytra = sgGeneral.add(
        new BoolSetting.Builder()
            .name("auto-replace-elytra")
            .description("Менять элитры до того, как они сломаются.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> elytraDurabilityLeft = sgGeneral.add(
        new IntSetting.Builder()
            .name("elytra-durability-left")
            .description("Менять элитру, когда осталось столько прочности.")
            .defaultValue(15)
            .range(1, 100)
            .sliderRange(1, 50)
            .visible(autoReplaceElytra::get)
            .build()
    );

    private final Setting<Boolean> spawnIfNoElytra = sgGeneral.add(
        new BoolSetting.Builder()
            .name("spawn-if-no-elytra")
            .description("Отправить команду спавна, если запасных элитр нет.")
            .defaultValue(false)
            .build()
    );

    private final Setting<String> spawnCommand = sgGeneral.add(
        new StringSetting.Builder()
            .name("spawn-command")
            .description("Команда сервера для возвращения на спавн, без /.")
            .defaultValue("spawn")
            .visible(spawnIfNoElytra::get)
            .build()
    );

    // Flight

    private final Setting<Double> arriveRadius = sgFlight.add(
        new DoubleSetting.Builder()
            .name("arrive-radius")
            .description("Расстояние, на котором точка считается достигнутой.")
            .defaultValue(12)
            .range(2, 100)
            .sliderRange(2, 50)
            .build()
    );

    private final Setting<Double> orbitRadius = sgFlight.add(
        new DoubleSetting.Builder()
            .name("orbit-radius")
            .description("Радиус ожидания при выключенном auto-advance.")
            .defaultValue(25)
            .range(8, 200)
            .sliderRange(8, 100)
            .visible(() -> !autoAdvance.get())
            .build()
    );

    private final Setting<Integer> cruiseAltitude = sgFlight.add(
        new IntSetting.Builder()
            .name("cruise-altitude")
            .description("Высота полёта. 0 = высота старта + 40.")
            .defaultValue(0)
            .range(0, 2000)
            .sliderRange(0, 320)
            .build()
    );

    private final Setting<Double> maxPitchUp = sgFlight.add(
        new DoubleSetting.Builder()
            .name("max-climb-pitch")
            .defaultValue(45)
            .range(5, 80)
            .sliderRange(5, 80)
            .build()
    );

    private final Setting<Double> maxPitchDown = sgFlight.add(
        new DoubleSetting.Builder()
            .name("max-dive-pitch")
            .defaultValue(30)
            .range(5, 80)
            .sliderRange(5, 80)
            .build()
    );

    private final Setting<Double> turnSpeed = sgFlight.add(
        new DoubleSetting.Builder()
            .name("turn-speed")
            .description("Максимальный поворот за тик.")
            .defaultValue(25)
            .range(1, 180)
            .sliderRange(1, 90)
            .build()
    );

    private final Setting<Boolean> avoidObstacles = sgFlight.add(
        new BoolSetting.Builder()
            .name("avoid-obstacles")
            .description("Набирать высоту перед препятствиями.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> lookahead = sgFlight.add(
        new IntSetting.Builder()
            .name("obstacle-lookahead")
            .defaultValue(45)
            .range(10, 120)
            .sliderRange(10, 120)
            .visible(avoidObstacles::get)
            .build()
    );

    // Fireworks

    private final Setting<Boolean> useFireworks = sgFireworks.add(
        new BoolSetting.Builder()
            .name("use-fireworks")
            .description("Использовать ракеты для ускорения.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Double> minSpeed = sgFireworks.add(
        new DoubleSetting.Builder()
            .name("min-speed")
            .defaultValue(26)
            .range(5, 60)
            .sliderRange(5, 60)
            .visible(useFireworks::get)
            .build()
    );

    private final Setting<Integer> fireworkCooldown = sgFireworks.add(
        new IntSetting.Builder()
            .name("firework-cooldown")
            .defaultValue(25)
            .range(5, 200)
            .sliderRange(5, 100)
            .visible(useFireworks::get)
            .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(
        new BoolSetting.Builder()
            .name("render")
            .description("Показывать текущую точку и линию маршрута.")
            .defaultValue(true)
            .build()
    );

    // State

    private final List<Point> route = new ArrayList<>();

    private int index;
    private boolean navigating;
    private boolean waiting;
    private boolean spawnCommandSent;

    private double cruiseY;

    private int fireworkTimer;
    private int takeoffTimer;
    private int airTicks;

    private boolean jumpHeld;

    public ElytraGps() {
        super(
            ElytraGpsAddon.CATEGORY,
            "elytra-gps",
            "Автопилот на элитрах по координатам."
        );
    }

    // Starting a route

    public void startSingle(Point point) {
        route.clear();
        route.add(point);
        begin();
    }

    public boolean startFromFile() {
        PointFile.Result result = PointFile.load(fileName.get());

        if (result.created()) {
            warning("Создан пример файла: %s. Заполни его координатами.", result.path());
            return false;
        }

        for (String err : result.errors()) warning(err);

        if (result.points().isEmpty()) {
            error("Нет непосещённых точек в файле: %s", result.path());
            return false;
        }

        route.clear();
        route.addAll(result.points());

        begin();
        return navigating;
    }

    /**
     * Сортирует маршрут по принципу ближайшего соседа:
     * сначала ближайшая точка от игрока, затем ближайшая к предыдущей.
     */
    private void orderRouteByNearest() {
        if (mc.player == null || route.size() < 2) return;

        List<Point> remaining = new ArrayList<>(route);
        List<Point> ordered = new ArrayList<>();

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        while (!remaining.isEmpty()) {
            int nearest = 0;
            double best = Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                Point p = remaining.get(i);

                double dx = p.x() - px;
                double dz = p.z() - pz;
                double distanceSquared = dx * dx + dz * dz;

                if (distanceSquared < best) {
                    best = distanceSquared;
                    nearest = i;
                }
            }

            Point selected = remaining.remove(nearest);
            ordered.add(selected);

            px = selected.x();
            pz = selected.z();
        }

        route.clear();
        route.addAll(ordered);
    }

    private void begin() {
        if (mc.player == null) return;

        if (route.isEmpty()) {
            error("Маршрут пуст.");
            return;
        }

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            error("Надень элитры или запусти маршрут с элитрами в слоте нагрудника.");
            route.clear();
            navigating = false;
            return;
        }

        if (startNearest.get()) orderRouteByNearest();

        index = 0;
        waiting = false;
        navigating = true;
        spawnCommandSent = false;

        fireworkTimer = 0;
        takeoffTimer = 0;
        airTicks = 0;
        jumpHeld = false;

        cruiseY = cruiseAltitude.get() > 0
            ? cruiseAltitude.get()
            : mc.player.getY() + 40;

        if (!isActive()) toggle();

        say("Маршрут запущен. Точка %d/%d: %s",
            index + 1, route.size(), route.get(index));
    }

    // NextPoint

    public void nextPoint() {
        if (mc.player == null) return;

        if (!navigating || route.isEmpty()) {
            warning("Маршрут не запущен. Используй .gps");
            return;
        }

        if (waiting) {
            // При прибытии точка уже записана в pointdelete.txt.
            if (index + 1 >= route.size()) {
                say("Маршрут завершён.");
                stopNavigation();
                return;
            }

            index++;
            waiting = false;

            resetFlightTimers();

            if (!isActive()) toggle();

            say("Продолжаю полёт к точке %d/%d: %s",
                index + 1, route.size(), route.get(index));
            return;
        }

        // Если кнопку нажали до прибытия, пропускаем текущую точку.
        Point skipped = route.get(index);

        if (!PointFile.markDeleted(fileName.get(), skipped)) {
            warning("Не удалось записать пропущенную точку в pointdelete.txt");
        }

        if (index + 1 < route.size()) {
            index++;
            waiting = false;

            resetFlightTimers();

            say("Точка пропущена. Следующая: %s", route.get(index));
        } else {
            say("Это была последняя точка. Маршрут завершён.");
            stopNavigation();
        }
    }

    private void resetFlightTimers() {
        releaseJump();
        takeoffTimer = 0;
        airTicks = 0;
        fireworkTimer = 0;
    }

    public void stopNavigation() {
        releaseJump();

        navigating = false;
        waiting = false;
        route.clear();
        index = 0;

        if (isActive()) toggle();
    }

    public String status() {
        if (!navigating || route.isEmpty() || mc.player == null) {
            return "Маршрут не запущен.";
        }

        Point p = route.get(index);

        return String.format(
            "Точка %d/%d: %s | до неё %.0f блоков%s",
            index + 1,
            route.size(),
            p,
            distanceTo(p),
            waiting ? " | ожидание NextPoint" : ""
        );
    }

    public boolean isNavigating() {
        return navigating;
    }

    private void say(String message, Object... args) {
        if (chatFeedback.get()) info(message, args);
    }

    // Elytra safety

    /**
     * Возвращает true, если можно продолжать обычный цикл полёта.
     * При замене элитры текущий тик пропускается.
     */
    private boolean handleElytraSafety() {
        if (mc.player == null) return false;

        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);

        boolean equippedElytra = chest.is(Items.ELYTRA);

        boolean lowDurability = equippedElytra
            && chest.getMaxDamage() - chest.getDamageValue()
                <= elytraDurabilityLeft.get();

        if (equippedElytra && !lowDurability) return true;

        if (autoReplaceElytra.get()) {
            FindItemResult spare = InvUtils.find(
                stack -> stack.is(Items.ELYTRA)
                    && stack.getMaxDamage() - stack.getDamageValue()
                        > elytraDurabilityLeft.get()
            );

            if (spare.found()) {
                releaseJump();

                // В Meteor слот нагрудника имеет номер 2.
                // Перемещение меняет предметы местами, поэтому старая элитра
                // остаётся в инвентаре, если там есть место для обмена.
                InvUtils.move().from(spare.slot()).toArmor(2);

                takeoffTimer = 0;
                airTicks = 0;

                say("Обнаружена изношенная или отсутствующая элитра. Меняю на запасную.");
                return false;
            }
        }

        if (spawnIfNoElytra.get() && !spawnCommandSent) {
            String command = spawnCommand.get().trim();

            if (!command.isEmpty()) {
                spawnCommandSent = true;

                stopNavigation();

                if (mc.player != null && mc.player.connection != null) {
                    mc.player.connection.sendCommand(
                        command.startsWith("/")
                            ? command.substring(1)
                            : command
                    );

                    warning("Запасной элитры нет. Отправлена команда возвращения.");
                }

                return false;
            }
        }

        error("Нет пригодной элитры или запасной элитры. Останавливаю автопилот.");
        stopNavigation();
        return false;
    }

    // Lifecycle

    @Override
    public void onDeactivate() {
        releaseJump();
        navigating = false;
        waiting = false;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        releaseJump();

        navigating = false;
        waiting = false;
        route.clear();
        index = 0;

        if (isActive()) toggle();
    }

    // Main flight loop

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!navigating || mc.player == null || mc.level == null || route.isEmpty()) {
            return;
        }

        if (!handleElytraSafety()) return;

        // В ожидании NextPoint не управляем полётом и не пытаемся взлететь.
        if (waiting) return;

        if (!mc.player.isFallFlying()) {
            handleTakeoff();
            return;
        }

        takeoffTimer = 0;
        releaseJump();

        Point target = route.get(index);

        if (distanceTo(target) <= arriveRadius.get()) {
            onArrive();

            if (!navigating || waiting) return;

            target = route.get(index);
        }

        double yaw = desiredYaw(target);
        double pitch = desiredPitch(target);

        if (avoidObstacles.get()) {
            pitch = avoidTerrain(yaw, pitch);
        }

        applyRotation(yaw, pitch);

        if (useFireworks.get()) handleFireworks();
    }

    private void onArrive() {
        Point reached = route.get(index);

        if (!PointFile.markDeleted(fileName.get(), reached)) {
            warning("Не удалось записать точку в pointdelete.txt");
        }

        if (index + 1 >= route.size()) {
            say("Последняя точка достигнута. Маршрут завершён.");
            stopNavigation();
            return;
        }

        if (autoAdvance.get()) {
            index++;
            waiting = false;

            resetFlightTimers();

            say("Точка достигнута. Следующая точка: %s", route.get(index));
        } else {
            waiting = true;

            say("Точка %d/%d достигнута. Нажми NextPoint для продолжения.",
                index + 1, route.size());
        }
    }

    // Navigation math

    private double horizontalDistanceTo(Point p) {
        double dx = p.x() - mc.player.getX();
        double dz = p.z() - mc.player.getZ();

        return Math.sqrt(dx * dx + dz * dz);
    }

    private double distanceTo(Point p) {
        double horizontal = horizontalDistanceTo(p);

        if (!p.hasY()) return horizontal;

        double dy = p.y() - mc.player.getY();

        return Math.sqrt(horizontal * horizontal + dy * dy);
    }

    private double bearingTo(Point p) {
        double dx = p.x() - mc.player.getX();
        double dz = p.z() - mc.player.getZ();

        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    private double desiredYaw(Point target) {
        return bearingTo(target);
    }

    private double desiredPitch(Point target) {
        double wantY = cruiseY;

        if (target.hasY()) {
            double horizontal = horizontalDistanceTo(target);
            double approachDistance = Math.max(
                80.0,
                Math.abs(cruiseY - target.y()) * 6.0
            );

            if (horizontal < approachDistance) {
                wantY = target.y();
            }
        }

        double errorY = wantY - mc.player.getY();
        double pitch = -errorY * 0.5;

        return Mth.clamp(
            pitch,
            -maxPitchUp.get(),
            maxPitchDown.get()
        );
    }

    private double avoidTerrain(double yaw, double pitch) {
        Vec3 eye = mc.player.getEyePosition();
        double distance = lookahead.get();

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        Vec3 planned = new Vec3(
            -Math.sin(yawRad) * Math.cos(pitchRad),
            -Math.sin(pitchRad),
            Math.cos(yawRad) * Math.cos(pitchRad)
        );

        Vec3 flat = new Vec3(
            -Math.sin(yawRad),
            0,
            Math.cos(yawRad)
        );

        if (blocked(eye, planned, distance) || blocked(eye, flat, distance)) {
            return -maxPitchUp.get();
        }

        return pitch;
    }

    private boolean blocked(Vec3 from, Vec3 direction, double distance) {
        Vec3 to = from.add(direction.scale(distance));

        ClipContext context = new ClipContext(
            from,
            to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            mc.player
        );

        return mc.level.clip(context).getType() != HitResult.Type.MISS;
    }

    private void applyRotation(double yaw, double pitch) {
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        float maxStep = turnSpeed.get().floatValue();

        float deltaYaw = Mth.clamp(
            Mth.wrapDegrees((float) yaw - currentYaw),
            -maxStep,
            maxStep
        );

        float deltaPitch = Mth.clamp(
            (float) pitch - currentPitch,
            -maxStep,
            maxStep
        );

        mc.player.setYRot(currentYaw + deltaYaw);
        mc.player.setXRot(
            Mth.clamp(currentPitch + deltaPitch, -90f, 90f)
        );
    }

    // Takeoff

    private void handleTakeoff() {
        if (!autoTakeoff.get()) return;

        takeoffTimer++;

        if (takeoffTimer > 200) {
            releaseJump();
            error("Не удалось взлететь. Останавливаю маршрут.");
            stopNavigation();
            return;
        }

        if (jumpHeld) {
            releaseJump();
            return;
        }

        if (mc.player.onGround()) {
            airTicks = 0;
            pressJump();
        } else {
            airTicks++;

            if (airTicks >= 3 && (airTicks - 3) % 5 == 0) {
                pressJump();
            }
        }
    }

    private void pressJump() {
        mc.options.keyJump.setDown(true);
        jumpHeld = true;
    }

    private void releaseJump() {
        if (jumpHeld && mc.options != null) {
            mc.options.keyJump.setDown(false);
        }

        jumpHeld = false;
    }

    // Fireworks

    private void handleFireworks() {
        if (fireworkTimer > 0) {
            fireworkTimer--;
            return;
        }

        double speed = mc.player.getDeltaMovement().length() * 20.0;

        if (speed >= minSpeed.get()) return;

        FindItemResult rocket = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);

        if (!rocket.found()) return;

        if (rocket.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);

            mc.player.swing(
                InteractionHand.OFF_HAND,
                mc.player.getOffhandItem().getInteractAnimation(),
                false
            );
        } else {
            InvUtils.swap(rocket.slot(), true);

            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);

            mc.player.swing(
                InteractionHand.MAIN_HAND,
                mc.player.getMainHandItem().getInteractAnimation(),
                false
            );

            InvUtils.swapBack();
        }

        fireworkTimer = fireworkCooldown.get();
    }

    // Render

    private static final Color CURRENT = new Color(40, 160, 255, 255);
    private static final Color CURRENT_SIDE = new Color(40, 160, 255, 40);
    private static final Color OTHER = new Color(255, 255, 255, 120);

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (!render.get() || !navigating || route.isEmpty() || mc.player == null) {
            return;
        }

        for (int i = index; i < route.size(); i++) {
            Point p = route.get(i);
            double y = p.hasY() ? p.y() : mc.player.getY();

            if (i == index) {
                event.renderer.line(
                    RenderUtils.center.x,
                    RenderUtils.center.y,
                    RenderUtils.center.z,
                    p.x(), y, p.z(),
                    CURRENT
                );

                event.renderer.box(
                    p.x() - 1, y - 1, p.z() - 1,
                    p.x() + 1, y + 1, p.z() + 1,
                    CURRENT_SIDE,
                    CURRENT,
                    ShapeMode.Both,
                    0
                );
            } else {
                Point previous = route.get(i - 1);
                double previousY = previous.hasY()
                    ? previous.y()
                    : mc.player.getY();

                event.renderer.line(
                    previous.x(), previousY, previous.z(),
                    p.x(), y, p.z(),
                    OTHER
                );
            }
        }
    }
}

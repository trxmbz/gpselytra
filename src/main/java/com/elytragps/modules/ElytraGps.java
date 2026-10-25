
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

    private final Setting<String> fileName = sgGeneral.add(new StringSetting.Builder()
        .name("file-name")
        .description("Файл с координатами.")
        .defaultValue("pointelytra.txt")
        .build()
    );

    private final Setting<Keybind> nextPointKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("next-point-key")
        .description("Продолжить маршрут или пропустить текущую точку.")
        .defaultValue(Keybind.none())
        .action(this::nextPoint)
        .build()
    );

    private final Setting<Boolean> autoAdvance = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-advance")
        .description("Автоматически лететь к следующей ближайшей точке.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> startNearest = sgGeneral.add(new BoolSetting.Builder()
        .name("start-nearest")
        .description("Начинать с ближайшей точки и заново искать ближайшую после прибытия.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Автоматически взлетать, если игрок на земле.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Сообщения о ходе маршрута.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> arriveRadius = sgFlight.add(new DoubleSetting.Builder()
        .name("arrive-radius")
        .description("Радиус достижения точки.")
        .defaultValue(12)
        .range(2, 100)
        .sliderRange(2, 50)
        .build()
    );

    private final Setting<Double> orbitRadius = sgFlight.add(new DoubleSetting.Builder()
        .name("orbit-radius")
        .description("Радиус ожидания следующей точки.")
        .defaultValue(25)
        .range(8, 200)
        .sliderRange(8, 100)
        .visible(() -> !autoAdvance.get())
        .build()
    );

    private final Setting<Integer> cruiseAltitude = sgFlight.add(new IntSetting.Builder()
        .name("cruise-altitude")
        .description("Высота полёта. 0 = высота старта + 40.")
        .defaultValue(0)
        .range(0, 2000)
        .sliderRange(0, 320)
        .build()
    );

    private final Setting<Double> maxPitchUp = sgFlight.add(new DoubleSetting.Builder()
        .name("max-climb-pitch")
        .defaultValue(45)
        .range(5, 80)
        .sliderRange(5, 80)
        .build()
    );

    private final Setting<Double> maxPitchDown = sgFlight.add(new DoubleSetting.Builder()
        .name("max-dive-pitch")
        .defaultValue(30)
        .range(5, 80)
        .sliderRange(5, 80)
        .build()
    );

    private final Setting<Double> turnSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("Максимальный поворот за тик.")
        .defaultValue(25)
        .range(1, 180)
        .sliderRange(1, 90)
        .build()
    );

    private final Setting<Boolean> avoidObstacles = sgFlight.add(new BoolSetting.Builder()
        .name("avoid-obstacles")
        .description("Пытаться набирать высоту перед препятствиями.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lookahead = sgFlight.add(new IntSetting.Builder()
        .name("obstacle-lookahead")
        .defaultValue(45)
        .range(10, 120)
        .sliderRange(10, 120)
        .visible(avoidObstacles::get)
        .build()
    );

    private final Setting<Boolean> useFireworks = sgFireworks.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Использовать ракеты для ускорения.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minSpeed = sgFireworks.add(new DoubleSetting.Builder()
        .name("min-speed")
        .defaultValue(26)
        .range(5, 60)
        .sliderRange(5, 60)
        .visible(useFireworks::get)
        .build()
    );

    private final Setting<Integer> fireworkCooldown = sgFireworks.add(new IntSetting.Builder()
        .name("firework-cooldown")
        .defaultValue(25)
        .range(5, 200)
        .sliderRange(5, 100)
        .visible(useFireworks::get)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .defaultValue(true)
        .build()
    );

    // В route хранятся только ещё не посещённые точки.
    // Текущая цель всегда находится в route.get(0).
    private final List<Point> route = new ArrayList<>();

    private boolean navigating;
    private boolean waiting;
    private double cruiseY;
    private int fireworkTimer;
    private int takeoffTimer;
    private int airTicks;
    private boolean jumpHeld;

    public ElytraGps() {
        super(ElytraGpsAddon.CATEGORY, "elytra-gps",
            "Автопилот на элитрах по координатам.");
    }

    public void startSingle(Point point) {
        route.clear();
        route.add(point);
        begin();
    }

    public boolean startFromFile() {
        PointFile.Result result = PointFile.load(fileName.get());

        if (result.created()) {
            warning("Создан пример файла: %s. Заполни его точками.", result.path());
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
     * Выбирает ближайшую точку относительно текущего положения игрока
     * и помещает её первой в списке.
     */
    private void selectNearestPoint() {
        if (mc.player == null || route.size() < 2) return;

        int nearestIndex = 0;
        double bestDistance = Double.MAX_VALUE;

        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        for (int i = 0; i < route.size(); i++) {
            Point point = route.get(i);

            double dx = point.x() - px;
            double dz = point.z() - pz;
            double dy = point.hasY() ? point.y() - py : 0;

            double distance = dx * dx + dz * dz + dy * dy;

            if (distance < bestDistance) {
                bestDistance = distance;
                nearestIndex = i;
            }
        }

        if (nearestIndex != 0) {
            Point nearest = route.remove(nearestIndex);
            route.add(0, nearest);
        }
    }

    private void begin() {
        if (mc.player == null) return;

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            error("Надень элитры в слот нагрудника.");
            route.clear();
            navigating = false;
            return;
        }

        if (route.isEmpty()) {
            error("Нет непосещённых точек.");
            navigating = false;
            return;
        }

        if (startNearest.get()) selectNearestPoint();

        navigating = true;
        waiting = false;
        fireworkTimer = 0;
        takeoffTimer = 0;
        airTicks = 0;
        jumpHeld = false;

        cruiseY = cruiseAltitude.get() > 0
            ? cruiseAltitude.get()
            : mc.player.getY() + 40;

        if (!isActive()) toggle();

        say("Маршрут запущен. Точек осталось: %d. Ближайшая: %s",
            route.size(), route.get(0));
    }

    public void nextPoint() {
        if (mc.player == null) return;

        if (!navigating || route.isEmpty()) {
            warning("Маршрут не запущен. Используй .gps");
            return;
        }

        // Если уже прибыли, продолжаем маршрут без повторной записи точки.
        if (waiting) {
            if (route.isEmpty()) {
                stopNavigation();
                return;
            }

            if (startNearest.get()) selectNearestPoint();

            waiting = false;
            resetFlightTimers();

            if (!isActive()) toggle();

            say("Продолжаю. Точек осталось: %d. Следующая: %s",
                route.size(), route.get(0));
            return;
        }

        // Ручной пропуск текущей точки тоже помечает её обработанной,
        // чтобы она не появилась снова при следующей загрузке файла.
        Point skipped = route.get(0);

        if (!PointFile.markDeleted(fileName.get(), skipped)) {
            warning("Не удалось сохранить точку в pointdelete.txt");
        }

        route.remove(0);

        if (route.isEmpty()) {
            say("Точки закончились. Маршрут завершён.");
            stopNavigation();
            return;
        }

        if (startNearest.get()) selectNearestPoint();

        resetFlightTimers();

        say("Точка пропущена. Осталось: %d. Следующая: %s",
            route.size(), route.get(0));
    }

    private void resetFlightTimers() {
        takeoffTimer = 0;
        airTicks = 0;
        fireworkTimer = 0;
        releaseJump();
    }

    public void stopNavigation() {
        releaseJump();

        navigating = false;
        waiting = false;
        route.clear();

        if (isActive()) toggle();
    }

    public String status() {
        if (!navigating || route.isEmpty() || mc.player == null) {
            return "Маршрут не запущен.";
        }

        Point target = route.get(0);

        return String.format(
            "Точек осталось: %d | цель: %s | до неё %.0f блоков%s",
            route.size(),
            target,
            distanceTo(target),
            waiting ? " | ожидание NextPoint" : ""
        );
    }

    public boolean isNavigating() {
        return navigating;
    }

    private void say(String message, Object... args) {
        if (chatFeedback.get()) info(message, args);
    }

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

        if (isActive()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!navigating || mc.player == null || mc.level == null || route.isEmpty()) return;

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            error("Элитры пропали — останавливаюсь.");
            stopNavigation();
            return;
        }

        if (waiting) return;

        if (!mc.player.isFallFlying()) {
            handleTakeoff();
            return;
        }

        takeoffTimer = 0;
        releaseJump();

        Point target = route.get(0);

        if (distanceTo(target) <= arriveRadius.get()) {
            onArrive();

            if (!navigating || waiting || route.isEmpty()) return;

            target = route.get(0);
        }

        double yaw = desiredYaw(target);
        double pitch = desiredPitch(target);

        if (avoidObstacles.get()) pitch = avoidTerrain(yaw, pitch);

        applyRotation(yaw, pitch);

        if (useFireworks.get()) handleFireworks();
    }

    private void onArrive() {
        if (route.isEmpty()) return;

        Point reached = route.get(0);

        // Сохраняем достигнутую точку в pointdelete.txt.
        if (!PointFile.markDeleted(fileName.get(), reached)) {
            warning("Не удалось записать точку в pointdelete.txt");
        }

        // Удаляем её из текущего маршрута.
        route.remove(0);

        if (route.isEmpty()) {
            say("Все точки достигнуты. Маршрут завершён.");
            stopNavigation();
            return;
        }

        // Ищем ближайшую точку заново от текущего положения игрока.
        if (startNearest.get()) selectNearestPoint();

        if (autoAdvance.get()) {
            waiting = false;
            resetFlightTimers();

            say("Точка достигнута и записана. Осталось: %d. Следующая: %s",
                route.size(), route.get(0));
        } else {
            waiting = true;

            say("Точка достигнута и записана. Осталось: %d. Нажми NextPoint: %s",
                route.size(), route.get(0));
        }
    }

    private double horizontalDistanceTo(Point point) {
        double dx = point.x() - mc.player.getX();
        double dz = point.z() - mc.player.getZ();

        return Math.sqrt(dx * dx + dz * dz);
    }

    private double distanceTo(Point point) {
        double horizontal = horizontalDistanceTo(point);

        if (!point.hasY()) return horizontal;

        double dy = point.y() - mc.player.getY();

        return Math.sqrt(horizontal * horizontal + dy * dy);
    }

    private double bearingTo(Point point) {
        double dx = point.x() - mc.player.getX();
        double dz = point.z() - mc.player.getZ();

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
                80.0, Math.abs(cruiseY - target.y()) * 6.0
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

    private void handleTakeoff() {
        if (!autoTakeoff.get()) return;

        takeoffTimer++;

        if (takeoffTimer > 200) {
            releaseJump();
            error("Не удалось взлететь. Останавливаюсь.");
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

    private static final Color CURRENT = new Color(40, 160, 255, 255);
    private static final Color CURRENT_SIDE = new Color(40, 160, 255, 40);
    private static final Color OTHER = new Color(255, 255, 255, 120);

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (!render.get() || !navigating || route.isEmpty() || mc.player == null) {
            return;
        }

        for (int i = 0; i < route.size(); i++) {
            Point point = route.get(i);
            double y = point.hasY() ? point.y() : mc.player.getY();

            if (i == 0) {
                event.renderer.line(
                    RenderUtils.center.x,
                    RenderUtils.center.y,
                    RenderUtils.center.z,
                    point.x(),
                    y,
                    point.z(),
                    CURRENT
                );

                event.renderer.box(
                    point.x() - 1,
                    y - 1,
                    point.z() - 1,
                    point.x() + 1,
                    y + 1,
                    point.z() + 1,
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
                    previous.x(),
                    previousY,
                    previous.z(),
                    point.x(),
                    y,
                    point.z(),
                    OTHER
                );
            }
        }
    }
}

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

/**
 * Автопилот на элитрах: летит к одной точке или по списку точек.
 * <p>
 * Управляется командами {@code .gps} / {@code .nextpoint}; модуль включается сам при старте маршрута
 * и выключается при остановке/завершении. Бинд "next-point" работает пока модуль включён (т.е. во время полёта).
 */
public class ElytraGps extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgFireworks = settings.createGroup("Fireworks");
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
        .description("Бинд: перейти к следующей точке (то же, что .nextpoint). Работает пока маршрут активен.")
        .defaultValue(Keybind.none())
        .action(this::nextPoint)
        .build()
    );

    private final Setting<Boolean> autoAdvance = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-advance")
        .description("Сам лететь к следующей точке, когда достиг текущей. Если выключено — кружит над точкой, пока не нажмёшь бинд/.nextpoint.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> startNearest = sgGeneral.add(new BoolSetting.Builder()
        .name("start-nearest")
        .description("Начинать маршрут из файла с ближайшей к тебе точки (а не с первой).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Сам прыгать и раскрывать элитры, если ты на земле.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Сообщения в чат о ходе маршрута.")
        .defaultValue(true)
        .build()
    );

    // ---- Flight ----

    private final Setting<Double> arriveRadius = sgFlight.add(new DoubleSetting.Builder()
        .name("arrive-radius")
        .description("На каком расстоянии (в блоках) точка считается достигнутой.")
        .defaultValue(12)
        .range(2, 100)
        .sliderRange(2, 50)
        .build()
    );

    private final Setting<Double> orbitRadius = sgFlight.add(new DoubleSetting.Builder()
        .name("orbit-radius")
        .description("Радиус круга ожидания, когда auto-advance выключен.")
        .defaultValue(25)
        .range(8, 200)
        .sliderRange(8, 100)
        .build()
    );

    private final Setting<Integer> cruiseAltitude = sgFlight.add(new IntSetting.Builder()
        .name("cruise-altitude")
        .description("Высота (Y) полёта между точками. 0 = авто: высота старта + 40.")
        .defaultValue(0)
        .range(0, 2000)
        .sliderRange(0, 320)
        .build()
    );

    private final Setting<Double> maxPitchUp = sgFlight.add(new DoubleSetting.Builder()
        .name("max-climb-pitch")
        .description("Максимальный угол набора высоты (градусы).")
        .defaultValue(45)
        .range(5, 80)
        .sliderRange(5, 80)
        .build()
    );

    private final Setting<Double> maxPitchDown = sgFlight.add(new DoubleSetting.Builder()
        .name("max-dive-pitch")
        .description("Максимальный угол снижения (градусы).")
        .defaultValue(30)
        .range(5, 80)
        .sliderRange(5, 80)
        .build()
    );

    private final Setting<Double> turnSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("Максимальный поворот камеры за тик (градусы). Меньше — плавнее.")
        .defaultValue(25)
        .range(1, 180)
        .sliderRange(1, 90)
        .build()
    );

    private final Setting<Boolean> avoidObstacles = sgFlight.add(new BoolSetting.Builder()
        .name("avoid-obstacles")
        .description("Задирать нос вверх, если впереди блоки (горы, деревья).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lookahead = sgFlight.add(new IntSetting.Builder()
        .name("obstacle-lookahead")
        .description("Дальность проверки препятствий (блоки).")
        .defaultValue(45)
        .range(10, 120)
        .sliderRange(10, 120)
        .visible(avoidObstacles::get)
        .build()
    );

    // ---- Fireworks ----

    private final Setting<Boolean> useFireworks = sgFireworks.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Использовать фейерверки из хотбара/оффхенда, чтобы держать скорость.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minSpeed = sgFireworks.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Если скорость ниже (блоков/сек) — запускаем фейерверк.")
        .defaultValue(26)
        .range(5, 60)
        .sliderRange(5, 60)
        .visible(useFireworks::get)
        .build()
    );

    private final Setting<Integer> fireworkCooldown = sgFireworks.add(new IntSetting.Builder()
        .name("firework-cooldown")
        .description("Минимальная пауза между фейерверками (тики).")
        .defaultValue(25)
        .range(5, 200)
        .sliderRange(5, 100)
        .visible(useFireworks::get)
        .build()
    );

    // ---- Render ----

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Рисовать линию и рамку к текущей точке, а также остальные точки маршрута.")
        .defaultValue(true)
        .build()
    );

    // ---- State ----

    private final List<Point> route = new ArrayList<>();
    private int index = 0;
    private boolean navigating = false;
    private boolean waiting = false;     // кружим над точкой, ждём .nextpoint
    private double cruiseY = 0;
    private int fireworkTimer = 0;
    private int takeoffTimer = 0;
    private int airTicks = 0;
    private boolean jumpHeld = false;

    public ElytraGps() {
        super(ElytraGpsAddon.CATEGORY, "elytra-gps", "Летит на элитрах к координатам или по списку точек (.gps, .nextpoint).");
    }

    // =====================================================================
    //  Публичное API для команд
    // =====================================================================

    /** Лететь к одной точке. */
    public void startSingle(Point point) {
        route.clear();
        route.add(point);
        begin();
    }

    /** Лететь по всем точкам из файла. */
    public boolean startFromFile() {
        PointFile.Result result = PointFile.load(fileName.get());

        if (result.created()) {
            warning("Файл не найден — создал пример: %s. Впиши туда точки и повтори .gps", result.path());
            return false;
        }
        for (String err : result.errors()) warning(err);

        if (result.points().isEmpty()) {
            error("В %s нет ни одной корректной точки.", result.path());
            return false;
        }

        route.clear();
        route.addAll(result.points());
        info("Загружено точек: %d (%s)", route.size(), result.path());
        begin();
        return true;
    }

    /** Перейти к следующей точке (команда .nextpoint и бинд). */
    public void nextPoint() {
        if (mc.player == null) return;

        if (!navigating) {
            warning("Маршрут не запущен. Используй .gps");
            return;
        }

        if (index + 1 < route.size()) {
            index++;
            waiting = false;
            say("Следующая точка %d/%d: %s", index + 1, route.size(), route.get(index));
        } else {
            say("Это была последняя точка — маршрут завершён.");
            stopNavigation();
        }
    }

    public void stopNavigation() {
        navigating = false;
        waiting = false;
        route.clear();
        index = 0;
        if (isActive()) toggle();
    }

    public String status() {
        if (!navigating || route.isEmpty() || mc.player == null) return "Маршрут не запущен.";
        Point p = route.get(index);
        return String.format("Точка %d/%d: %s | до неё %.0f блоков%s",
            index + 1, route.size(), p, distanceTo(p), waiting ? " | ждём .nextpoint" : "");
    }

    public boolean isNavigating() {
        return navigating;
    }

    // =====================================================================
    //  Жизненный цикл
    // =====================================================================

    private void begin() {
        if (mc.player == null) return;

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            error("На тебе нет элитр (слот нагрудника).");
            route.clear();
            return;
        }

        index = 0;
        if (startNearest.get() && route.size() > 1) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < route.size(); i++) {
                double d = horizontalDistanceTo(route.get(i));
                if (d < best) {
                    best = d;
                    index = i;
                }
            }
        }
        waiting = false;
        navigating = true;
        fireworkTimer = 0;
        takeoffTimer = 0;
        airTicks = 0;
        jumpHeld = false;
        cruiseY = cruiseAltitude.get() > 0 ? cruiseAltitude.get() : mc.player.getY() + 40;

        if (!isActive()) toggle();

        say("Старт. Точка %d/%d: %s", index + 1, route.size(), route.get(index));
    }

    @Override
    public void onDeactivate() {
        releaseJump();
        navigating = false;
        waiting = false;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        navigating = false;
        waiting = false;
        route.clear();
        index = 0;
        if (isActive()) toggle();
    }

    // =====================================================================
    //  Основной цикл
    // =====================================================================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!navigating || mc.player == null || mc.level == null || route.isEmpty()) return;

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            error("Элитры пропали — останавливаюсь.");
            stopNavigation();
            return;
        }

        // --- взлёт ---
        if (!mc.player.isFallFlying()) {
            handleTakeoff();
            return;
        }
        takeoffTimer = 0;
        releaseJump();

        Point target = route.get(index);

        // --- прибытие ---
        if (!waiting && distanceTo(target) <= arriveRadius.get()) {
            onArrive();
            if (!navigating) return;
            target = route.get(index);
        }

        // --- куда смотреть ---
        double yaw = desiredYaw(target);
        double pitch = desiredPitch(target, yaw);

        if (avoidObstacles.get()) pitch = avoidTerrain(yaw, pitch);

        applyRotation(yaw, pitch);

        // --- фейерверки ---
        if (useFireworks.get()) handleFireworks();
    }

    
private void onArrive() {
    Point reached = route.get(index);

    // Сохраняем достигнутую точку, чтобы не возвращаться к ней.
    if (!PointFile.markDeleted(fileName.get(), reached)) {
        warning("Не удалось сохранить достигнутую точку.");
    }

    waiting = true;
    releaseJump();

    say("Точка %d/%d достигнута: %s. Ожидаю NextPoint.",
        index + 1, route.size(), reached);

    // Останавливаем управление полётом, но сохраняем маршрут
    // и состояние ожидания для команды NextPoint.
    navigating = false;
    if (isActive()) toggle();
}

    private double horizontalDistanceTo(Point p) {
        double dx = p.x() - mc.player.getX();
        double dz = p.z() - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double distanceTo(Point p) {
        double h = horizontalDistanceTo(p);
        if (!p.hasY()) return h;
        double dy = p.y() - mc.player.getY();
        return Math.sqrt(h * h + dy * dy);
    }

    /** Азимут на точку. В Minecraft yaw 0 = +Z, 90 = -X. */
    private double bearingTo(Point p) {
        double dx = p.x() - mc.player.getX();
        double dz = p.z() - mc.player.getZ();
        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    private double desiredYaw(Point target) {
        double bearing = bearingTo(target);
        if (!waiting) return bearing;

        // Круг ожидания: у радиуса orbit — летим по касательной (+90°), дальше — к точке, ближе — от неё.
        double r = orbitRadius.get();
        double offset = Mth.clamp((horizontalDistanceTo(target) - r) / r, -1.0, 1.0);
        return bearing + 90.0 * (1.0 - offset);
    }

    private double desiredPitch(Point target, double yaw) {
        double wantY = cruiseY;

        if (target.hasY() && !waiting) {
            // Идём на крейсерской высоте и снижаемся/набираем к Y точки только на подлёте.
            double horiz = horizontalDistanceTo(target);
            double approachDist = Math.max(80.0, Math.abs(cruiseY - target.y()) * 6.0);
            if (horiz < approachDist) wantY = target.y();
        }

        double err = wantY - mc.player.getY();         // >0 — надо выше
        double pitch = -err * 0.5;                      // отрицательный pitch = вверх
        return Mth.clamp(pitch, -maxPitchUp.get(), maxPitchDown.get());
    }

    /** Если по курсу блоки — поднимаем нос. */
    private double avoidTerrain(double yaw, double pitch) {
        Vec3 eye = mc.player.getEyePosition();
        double dist = lookahead.get();

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        // Проверяем и по запланированному направлению, и горизонтально вперёд.
        Vec3 planned = new Vec3(
            -Math.sin(yawRad) * Math.cos(pitchRad),
            -Math.sin(pitchRad),
            Math.cos(yawRad) * Math.cos(pitchRad)
        );
        Vec3 flat = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));

        if (blocked(eye, planned, dist) || blocked(eye, flat, dist)) {
            return -maxPitchUp.get();
        }
        return pitch;
    }

    private boolean blocked(Vec3 from, Vec3 dir, double dist) {
        Vec3 to = from.add(dir.scale(dist));
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        return mc.level.clip(ctx).getType() != HitResult.Type.MISS;
    }

    private void applyRotation(double yaw, double pitch) {
        float curYaw = mc.player.getYRot();
        float curPitch = mc.player.getXRot();

        float maxStep = turnSpeed.get().floatValue();

        float dYaw = Mth.clamp(Mth.wrapDegrees((float) yaw - curYaw), -maxStep, maxStep);
        float dPitch = Mth.clamp((float) pitch - curPitch, -maxStep, maxStep);

        mc.player.setYRot(curYaw + dYaw);
        mc.player.setXRot(Mth.clamp(curPitch + dPitch, -90f, 90f));
    }

    // =====================================================================
    //  Взлёт и фейерверки
    // =====================================================================

    private void handleTakeoff() {
        if (!autoTakeoff.get()) return;

        takeoffTimer++;

        // Не получилось за ~10 секунд — сдаёмся, чтобы не прыгать вечно.
        if (takeoffTimer > 200) {
            releaseJump();
            error("Не удалось взлететь. Останавливаюсь.");
            stopNavigation();
            return;
        }

        // Двойной пробел: нажали -> отпустили -> (в воздухе) нажали ещё раз.
        // Если пробел держали в прошлом тике — отпускаем, чтобы следующее нажатие считалось новым.
        if (jumpHeld) {
            releaseJump();
            return;
        }

        if (mc.player.onGround()) {
            airTicks = 0;
            pressJump();                       // первый пробел — прыжок
        } else {
            airTicks++;
            if (airTicks >= 3 && (airTicks - 3) % 5 == 0) {
                pressJump();                   // второй пробел в воздухе — раскрывает элитры
            }
        }
    }

    private void pressJump() {
        mc.options.keyJump.setDown(true);
        jumpHeld = true;
    }

    private void releaseJump() {
        if (jumpHeld && mc.options != null) mc.options.keyJump.setDown(false);
        jumpHeld = false;
    }

    private void handleFireworks() {
        if (fireworkTimer > 0) {
            fireworkTimer--;
            return;
        }

        double speed = mc.player.getDeltaMovement().length() * 20.0; // блоков/сек
        if (speed >= minSpeed.get()) return;

        FindItemResult rocket = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!rocket.found()) return;

        if (rocket.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            mc.player.swing(InteractionHand.OFF_HAND, mc.player.getOffhandItem().getInteractAnimation(), false);
        } else {
            InvUtils.swap(rocket.slot(), true);

            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getInteractAnimation(), false);

            InvUtils.swapBack();
        }

        fireworkTimer = fireworkCooldown.get();
    }

    // =====================================================================
    //  Рендер
    // =====================================================================

    private static final Color CURRENT = new Color(40, 160, 255, 255);
    private static final Color CURRENT_SIDE = new Color(40, 160, 255, 40);
    private static final Color OTHER = new Color(255, 255, 255, 120);

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (!render.get() || !navigating || route.isEmpty() || mc.player == null) return;

        for (int i = index; i < route.size(); i++) {
            Point p = route.get(i);
            double y = p.hasY() ? p.y() : mc.player.getY();

            if (i == index) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, p.x(), y, p.z(), CURRENT);
                event.renderer.box(p.x() - 1, y - 1, p.z() - 1, p.x() + 1, y + 1, p.z() + 1, CURRENT_SIDE, CURRENT, ShapeMode.Both, 0);
            } else {
                Point prev = route.get(i - 1);
                double prevY = prev.hasY() ? prev.y() : mc.player.getY();
                event.renderer.line(prev.x(), prevY, prev.z(), p.x(), y, p.z(), OTHER);
            }
        }
    }

    // =====================================================================
    //  Чат
    // =====================================================================

    private void say(String message, Object... args) {
        if (chatFeedback.get()) info(message, args);
    }
}

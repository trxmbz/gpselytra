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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Автопилот на элитрах.
 * <p>
 * Фазы полёта к точке:
 * <ol>
 *   <li>CLIMB  — взлёт (двойной пробел) и набор высоты на фейерверках до границ Pitch40;</li>
 *   <li>CRUISE — дальше летит ElytraFly в режиме Pitch40, мы рулим только курсом (yaw);</li>
 *   <li>LANDING — ElytraFly выключается, планируем по глиссаде на блок под точкой;</li>
 *   <li>WALK   — после касания доходим пешком до центра нужного блока;</li>
 *   <li>HOLD   — стоим на земле и ждём .nextpoint (если выключен auto-advance).</li>
 * </ol>
 * Фейерверки тратятся только в CLIMB.
 */
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
        .description("Бинд: остановить маршрут (то же, что .gps stop). Работает пока маршрут активен.")
        .defaultValue(Keybind.none())
        .action(this::userStop)
        .build()
    );

    @SuppressWarnings("unused")
    private final Setting<Keybind> statusKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("status-key")
        .description("Бинд: показать статус в чате (то же, что .gps status). Работает пока маршрут активен.")
        .defaultValue(Keybind.none())
        .action(this::printStatus)
        .build()
    );

    private final Setting<Boolean> autoAdvance = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-advance")
        .description("Сам лететь к следующей точке, когда достиг текущей. Если выключено — садится на точке и ждёт .nextpoint / бинд.")
        .defaultValue(false)
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
        .description("Сам прыгать и раскрывать элитры двойным пробелом, если ты на земле.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoReplaceElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-replace-elytra")
        .description("Если надетые элитры сломаны (или не надеты) — взять целые из инвентаря.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> spawnIfNoElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("spawn-if-no-elytra")
        .description("Если целых элитр не нашлось — остановить маршрут и отправить команду спавна.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> spawnCommand = sgGeneral.add(new StringSetting.Builder()
        .name("spawn-command")
        .description("Команда, которая отправляется, если элитр нет (со слэшем или без).")
        .defaultValue("spawn")
        .visible(spawnIfNoElytra::get)
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
        .description("На каком расстоянии (в блоках, по горизонтали) пролётная точка считается достигнутой.")
        .defaultValue(12)
        .range(2, 100)
        .sliderRange(2, 50)
        .build()
    );

    private final Setting<Double> orbitRadius = sgFlight.add(new DoubleSetting.Builder()
        .name("orbit-radius")
        .description("Радиус круга: ожидание .nextpoint в воздухе и снижение по спирали при заходе на посадку.")
        .defaultValue(25)
        .range(8, 200)
        .sliderRange(8, 100)
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

    // ---- Takeoff ----

    private final Setting<Double> climbPitch = sgTakeoff.add(new DoubleSetting.Builder()
        .name("climb-pitch")
        .description("Угол набора высоты на фейерверках (градусы вверх).")
        .defaultValue(50)
        .range(10, 80)
        .sliderRange(20, 75)
        .build()
    );

    private final Setting<Double> minSpeed = sgTakeoff.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Если скорость ниже (блоков/сек) — запускаем фейерверк.")
        .defaultValue(26)
        .range(5, 60)
        .sliderRange(5, 60)
        .build()
    );

    private final Setting<Integer> fireworkCooldown = sgTakeoff.add(new IntSetting.Builder()
        .name("firework-cooldown")
        .description("Минимальная пауза между фейерверками (тики).")
        .defaultValue(25)
        .range(5, 200)
        .sliderRange(5, 100)
        .build()
    );

    private final Setting<Integer> maxFireworks = sgTakeoff.add(new IntSetting.Builder()
        .name("max-takeoff-fireworks")
        .description("Сколько фейерверков максимум потратить на один набор высоты. Чем ниже границы Pitch40 в ElytraFly, тем меньше нужно.")
        .defaultValue(20)
        .range(1, 64)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> avoidObstacles = sgTakeoff.add(new BoolSetting.Builder()
        .name("avoid-obstacles")
        .description("При наборе высоты задирать нос круче, если впереди блоки.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lookahead = sgTakeoff.add(new IntSetting.Builder()
        .name("obstacle-lookahead")
        .description("Дальность проверки препятствий (блоки).")
        .defaultValue(35)
        .range(10, 120)
        .sliderRange(10, 100)
        .visible(avoidObstacles::get)
        .build()
    );

    // ---- Landing ----

    private final Setting<LandMode> landMode = sgLanding.add(new EnumSetting.Builder<LandMode>()
        .name("land-mode")
        .description("Где садиться: только на последней точке, на каждой или нигде (просто пролетать).")
        .defaultValue(LandMode.EveryPoint)
        .build()
    );

    private final Setting<Double> descentAngle = sgLanding.add(new DoubleSetting.Builder()
        .name("descent-angle")
        .description("Угол планирования при заходе на посадку (градусы вниз). По нему считается, когда начинать снижение.")
        .defaultValue(15)
        .range(5, 30)
        .sliderRange(5, 30)
        .build()
    );

    private final Setting<Double> maxLandPitch = sgLanding.add(new DoubleSetting.Builder()
        .name("max-land-pitch")
        .description("Максимальный угол снижения на прямой. Если для попадания в точку нужно круче — снижается по спирали.")
        .defaultValue(30)
        .range(10, 45)
        .sliderRange(10, 45)
        .build()
    );

    private final Setting<Boolean> walkToBlock = sgLanding.add(new BoolSetting.Builder()
        .name("walk-to-block")
        .description("После касания земли дойти пешком до центра нужного блока.")
        .defaultValue(true)
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
    private boolean waiting = false;      // кружим в воздухе над точкой, ждём .nextpoint
    private Phase phase = Phase.CLIMB;

    private int fireworkTimer = 0;
    private int fireworksUsed = 0;
    private int takeoffTimer = 0;
    private int airTicks = 0;
    private boolean jumpHeld = false;

    private int walkTimer = 0;
    private boolean walking = false;
    private int swapCooldown = 0;

    private boolean ownsElytraFly = false;
    private ElytraFlightModes savedMode = null;

    public ElytraGps() {
        super(ElytraGpsAddon.CATEGORY, "elytra-gps", "Летит на элитрах к координатам или по списку точек (.gps, .nextpoint).");
    }

    // =====================================================================
    //  Публичное API для команд и биндов
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

        if (index + 1 >= route.size()) {
            say("Это была последняя точка — маршрут завершён.");
            stopNavigation();
            return;
        }

        index++;
        waiting = false;

        // Если стояли/садились — начинаем новый взлёт; если летим в CRUISE — просто меняем курс.
        if (phase == Phase.LANDING || phase == Phase.WALK || phase == Phase.HOLD) {
            releaseWalk();
            startClimb();
        }

        say("Следующая точка %d/%d: %s", index + 1, route.size(), route.get(index));
    }

    /** Остановка по бинду / команде — с сообщением. */
    public void userStop() {
        if (!navigating) return;
        stopNavigation();
        info("Маршрут остановлен.");
    }

    public void printStatus() {
        info(status());
    }

    public void stopNavigation() {
        navigating = false;
        waiting = false;
        route.clear();
        index = 0;
        cleanup();
        if (isActive()) toggle();
    }

    public String status() {
        if (!navigating || route.isEmpty() || mc.player == null) return "Маршрут не запущен.";
        Point p = route.get(index);
        return String.format("Точка %d/%d: %s | до неё %.0f блоков | фаза: %s%s",
            index + 1, route.size(), p, horizontalDistanceTo(p), phaseName(), waiting ? " | ждём .nextpoint" : "");
    }

    public boolean isNavigating() {
        return navigating;
    }

    private String phaseName() {
        return switch (phase) {
            case CLIMB -> "взлёт/набор высоты";
            case CRUISE -> "крейсер (Pitch40)";
            case LANDING -> "посадка";
            case WALK -> "иду к блоку";
            case HOLD -> "на земле, жду";
        };
    }

    // =====================================================================
    //  Жизненный цикл
    // =====================================================================

    private void begin() {
        if (mc.player == null) return;

        swapCooldown = 0;
        if (!ensureElytra()) {
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
        startClimb();

        if (!isActive()) toggle();

        say("Старт. Точка %d/%d: %s", index + 1, route.size(), route.get(index));
    }

    private void startClimb() {
        phase = Phase.CLIMB;
        fireworkTimer = 0;
        fireworksUsed = 0;
        takeoffTimer = 0;
        airTicks = 0;
        releaseJump();
    }

    @Override
    public void onDeactivate() {
        navigating = false;
        waiting = false;
        cleanup();
    }

    /** Отпускаем клавиши и возвращаем ElytraFly как было. */
    private void cleanup() {
        releaseJump();
        releaseWalk();
        disableElytraFly();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        navigating = false;
        waiting = false;
        route.clear();
        index = 0;
        jumpHeld = false;
        walking = false;

        // Игрока уже может не быть — отключаем ElytraFly осторожно.
        try {
            ElytraFly ef = Modules.get().get(ElytraFly.class);
            if (ownsElytraFly && ef.isActive()) ef.toggle();
            if (ownsElytraFly && savedMode != null) ef.flightMode.set(savedMode);
        } catch (Throwable ignored) {
        }
        ownsElytraFly = false;
        savedMode = null;

        if (isActive()) toggle();
    }

    // =====================================================================
    //  ElytraFly (Pitch40)
    // =====================================================================

    private ElytraFly elytraFly() {
        return Modules.get().get(ElytraFly.class);
    }

    /** Высота, с которой Pitch40 можно включать (обе границы из настроек ElytraFly). */
    private double climbAltitude() {
        ElytraFly ef = elytraFly();
        return Math.max(ef.pitch40upperBounds.get(), ef.pitch40lowerBounds.get() + 40.0) + 3.0;
    }

    private boolean enableElytraFly() {
        ElytraFly ef = elytraFly();

        if (ef.isActive()) ef.toggle(); // перезапускаем, чтобы Pitch40.onActivate проверил высоту

        if (savedMode == null) savedMode = ef.flightMode.get();
        ef.flightMode.set(ElytraFlightModes.Pitch40);
        ownsElytraFly = true;

        ef.toggle();
        return ef.isActive();
    }

    private void disableElytraFly() {
        if (!ownsElytraFly) return;

        ElytraFly ef = elytraFly();
        if (mc.player != null && ef.isActive()) ef.toggle();
        if (savedMode != null) ef.flightMode.set(savedMode);

        savedMode = null;
        ownsElytraFly = false;
    }

    // =====================================================================
    //  Основной цикл
    // =====================================================================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!navigating || mc.player == null || mc.level == null || route.isEmpty()) return;

        // Проверяем элитры (сломались в полёте — меняем на целые; целых нет — спавн).
        if (swapCooldown > 0) swapCooldown--;
        else if (!ensureElytra()) return;

        switch (phase) {
            case CLIMB -> tickClimb();
            case CRUISE -> tickCruise();
            case LANDING -> tickLanding();
            case WALK -> tickWalk();
            case HOLD -> {
            }
        }
    }

    // -------------------------------------------------------------- ELYTRA

    private static boolean isBroken(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue() <= 1;
    }

    /**
     * Проверяет надетые элитры. Сломаны/не надеты — пробует взять целые из инвентаря.
     * Если целых нет — останавливает маршрут и (опционально) отправляет команду спавна.
     *
     * @return true, если можно продолжать; false, если маршрут остановлен.
     */
    private boolean ensureElytra() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.has(DataComponents.GLIDER) && !isBroken(chest)) return true;

        if (autoReplaceElytra.get() && equipGoodElytra()) {
            swapCooldown = 10; // ждём, пока сервер обновит слот
            say("Поменял элитры на целые.");
            return true;
        }

        error("Нет целых элитр.");
        stopNavigation();

        if (spawnIfNoElytra.get() && mc.getConnection() != null) {
            String command = spawnCommand.get().trim();
            if (command.startsWith("/")) command = command.substring(1);
            if (!command.isEmpty()) {
                info("Отправляю /%s.", command);
                mc.getConnection().sendCommand(command);
            }
        }
        return false;
    }

    /** Берёт из инвентаря самые прочные целые элитры и надевает их. */
    private boolean equipGoodElytra() {
        List<ItemStack> items = mc.player.getInventory().getNonEquipmentItems();

        int best = -1;
        int bestLeft = -1;

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.has(DataComponents.GLIDER) || isBroken(stack)) continue;

            int left = stack.getMaxDamage() - stack.getDamageValue();
            if (left > bestLeft) {
                bestLeft = left;
                best = i;
            }
        }

        if (best == -1) return false;

        InvUtils.move().from(best).toArmor(2);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundContainerClosePacket(0));
        }
        return true;
    }

    // ---------------------------------------------------------------- CLIMB

    private void tickClimb() {
        Point target = route.get(index);

        if (!mc.player.isFallFlying()) {
            disableElytraFly();
            handleTakeoff();
            return;
        }

        takeoffTimer = 0;
        airTicks = 0;
        releaseJump();

        if (mc.player.getY() >= climbAltitude()) {
            startCruise();
            return;
        }

        double yaw = bearingTo(target.x(), target.z());
        double pitch = -climbPitch.get();

        if (avoidObstacles.get()) {
            double yawRad = Math.toRadians(yaw);
            Vec3 flat = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
            if (blocked(mc.player.getEyePosition(), flat, lookahead.get())) pitch = -75;
        }

        applyRotation(yaw, pitch);
        climbFireworks();
    }

    private void startCruise() {
        if (!enableElytraFly()) {
            error("Не удалось включить ElytraFly (Pitch40). Проверь границы pitch40 в его настройках.");
            stopNavigation();
            return;
        }
        phase = Phase.CRUISE;
        waiting = false;
        say("Набрал высоту, лечу через ElytraFly (Pitch40).");
    }

    // --------------------------------------------------------------- CRUISE

    private void tickCruise() {
        Point target = route.get(index);

        if (!elytraFly().isActive()) {
            error("ElytraFly выключили — останавливаюсь.");
            stopNavigation();
            return;
        }

        // Элитры закрылись (задели землю и т.п.) — взлетаем заново.
        if (!mc.player.isFallFlying()) {
            disableElytraFly();
            startClimb();
            return;
        }

        boolean last = index + 1 >= route.size();
        boolean land = landMode.get() == LandMode.EveryPoint || (landMode.get() == LandMode.LastPoint && last);
        double hd = horizontalDistanceTo(target);

        if (land) {
            BlockPos ground = groundBlock(target);
            if (ground != null) {
                double h = mc.player.getY() - (ground.getY() + 1.0);
                double glideDist = h / Math.tan(Math.toRadians(descentAngle.get())) + 40.0;
                if (hd <= glideDist) {
                    startLanding(ground);
                    return;
                }
            }
        } else if (!waiting && hd <= arriveRadius.get()) {
            if (last) {
                say("Достиг последней точки. Маршрут завершён.");
                stopNavigation();
                return;
            }

            if (autoAdvance.get()) {
                index++;
                say("Точка достигнута. Дальше %d/%d: %s", index + 1, route.size(), route.get(index));
                target = route.get(index);
            } else {
                waiting = true;
                say("Точка %d/%d достигнута. Кружу и жду .nextpoint (или бинд).", index + 1, route.size());
            }
        }

        // Pitch ведёт Pitch40, мы рулим только курсом.
        double yaw = waiting ? orbitYaw(target.x(), target.z()) : bearingTo(target.x(), target.z());
        applyYaw(yaw);
    }

    // -------------------------------------------------------------- LANDING

    private void startLanding(BlockPos ground) {
        BlockState state = mc.level.getBlockState(ground);
        if (state.getFluidState().is(FluidTags.LAVA)) {
            error("Под точкой лава — посадку отменяю, маршрут остановлен.");
            stopNavigation();
            return;
        }

        disableElytraFly();
        phase = Phase.LANDING;
        say("Захожу на посадку: блок %d %d %d.", ground.getX(), ground.getY(), ground.getZ());
    }

    private void tickLanding() {
        Point target = route.get(index);

        if (mc.player.onGround() && !mc.player.isFallFlying()) {
            onTouchdown(target);
            return;
        }

        // Элитры закрылись в воздухе — раскрываем снова.
        if (!mc.player.isFallFlying()) {
            handleTakeoff();
            return;
        }
        takeoffTimer = 0;
        airTicks = 0;
        releaseJump();

        double cx = centerX(target);
        double cz = centerZ(target);

        BlockPos ground = groundBlock(target);
        if (ground == null) {
            // Чанк пропал из виду — просто держим курс чуть вниз.
            applyRotation(bearingTo(cx, cz), 5);
            return;
        }

        double h = mc.player.getY() - (ground.getY() + 1.0);   // высота над поверхностью
        double hd = horizontalDistance(cx, cz);
        double slope = Math.toDegrees(Math.atan2(h, Math.max(hd, 0.001)));

        // Слишком круто (высоко над точкой) или проскочили её — кружим со снижением.
        boolean overshoot = slope > maxLandPitch.get() || (hd < 6 && h > 2.5);

        double yaw;
        double pitch;
        if (overshoot) {
            yaw = orbitYaw(cx, cz);
            pitch = descentAngle.get();
        } else {
            yaw = bearingTo(cx, cz);
            pitch = Mth.clamp(slope, -10, maxLandPitch.get());
            if (h < 6) pitch = Mth.clamp(pitch, -10, 8);   // выравнивание перед касанием
        }

        applyRotation(yaw, pitch);
    }

    private void onTouchdown(Point target) {
        double hd = horizontalDistance(centerX(target), centerZ(target));

        if (hd > 25) {
            warning("Приземлился в %.0f блоках от точки — взлетаю снова.", hd);
            startClimb();
            return;
        }

        if (walkToBlock.get() && hd > 0.4) {
            phase = Phase.WALK;
            walkTimer = 0;
            return;
        }

        arrivedOnGround();
    }

    // ----------------------------------------------------------------- WALK

    private void tickWalk() {
        Point target = route.get(index);

        if (!mc.player.onGround()) return; // подбросило — ждём приземления

        double cx = centerX(target);
        double cz = centerZ(target);
        double hd = horizontalDistance(cx, cz);

        walkTimer++;
        if (hd <= 0.4 || walkTimer > 160) {
            releaseWalk();
            arrivedOnGround();
            return;
        }

        double yaw = bearingTo(cx, cz);
        applyRotation(yaw, 10);

        float err = Math.abs(Mth.wrapDegrees((float) yaw - mc.player.getYRot()));
        mc.options.keyUp.setDown(err < 35);
        mc.options.keyJump.setDown(mc.player.horizontalCollision);
        walking = true;
    }

    private void releaseWalk() {
        if (!walking || mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        walking = false;
    }

    private void arrivedOnGround() {
        boolean last = index + 1 >= route.size();

        if (last) {
            say("Приземлился на точку. Маршрут завершён.");
            stopNavigation();
            return;
        }

        if (autoAdvance.get()) {
            index++;
            say("Приземлился. Дальше %d/%d: %s", index + 1, route.size(), route.get(index));
            startClimb();
        } else {
            phase = Phase.HOLD;
            say("Приземлился на точку %d/%d. Жду .nextpoint (или бинд).", index + 1, route.size());
        }
    }

    // =====================================================================
    //  Навигация
    // =====================================================================

    private double horizontalDistance(double x, double z) {
        double dx = x - mc.player.getX();
        double dz = z - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double horizontalDistanceTo(Point p) {
        return horizontalDistance(p.x(), p.z());
    }

    /** Центр блока под точкой. */
    private double centerX(Point p) {
        return Mth.floor(p.x()) + 0.5;
    }

    private double centerZ(Point p) {
        return Mth.floor(p.z()) + 0.5;
    }

    /** Азимут на точку. В Minecraft yaw 0 = +Z, 90 = -X. */
    private double bearingTo(double x, double z) {
        double dx = x - mc.player.getX();
        double dz = z - mc.player.getZ();
        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    /**
     * Курс для круга вокруг точки: у радиуса orbit — по касательной, дальше — к точке, ближе — от неё.
     */
    private double orbitYaw(double x, double z) {
        double bearing = bearingTo(x, z);
        double r = orbitRadius.get();
        double offset = Mth.clamp((horizontalDistance(x, z) - r) / r, -1.0, 1.0);
        return bearing + 90.0 * (1.0 - offset);
    }

    /**
     * Блок, на который надо сесть: самый верхний твёрдый (или жидкий) блок в колонке точки.
     * Возвращает null, если чанк не загружен.
     */
    private BlockPos groundBlock(Point t) {
        int bx = Mth.floor(t.x());
        int bz = Mth.floor(t.z());

        if (!mc.level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) return null;

        int startY = t.hasY() ? Mth.floor(t.y()) + 2 : Mth.floor(mc.player.getY());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = startY; y >= mc.level.getMinY(); y--) {
            pos.set(bx, y, bz);
            BlockState state = mc.level.getBlockState(pos);
            if (!state.getCollisionShape(mc.level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
                return pos.immutable();
            }
        }
        return null;
    }

    private boolean blocked(Vec3 from, Vec3 dir, double dist) {
        Vec3 to = from.add(dir.scale(dist));
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        return mc.level.clip(ctx).getType() != HitResult.Type.MISS;
    }

    private void applyRotation(double yaw, double pitch) {
        float curPitch = mc.player.getXRot();
        float maxStep = turnSpeed.get().floatValue();

        float dPitch = Mth.clamp((float) pitch - curPitch, -maxStep, maxStep);

        applyYaw(yaw);
        mc.player.setXRot(Mth.clamp(curPitch + dPitch, -90f, 90f));
    }

    private void applyYaw(double yaw) {
        float curYaw = mc.player.getYRot();
        float maxStep = turnSpeed.get().floatValue();

        float dYaw = Mth.clamp(Mth.wrapDegrees((float) yaw - curYaw), -maxStep, maxStep);
        mc.player.setYRot(curYaw + dYaw);
    }

    // =====================================================================
    //  Взлёт и фейерверки (фейерверки — только здесь)
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

    private void climbFireworks() {
        if (fireworkTimer > 0) {
            fireworkTimer--;
            return;
        }

        double speed = mc.player.getDeltaMovement().length() * 20.0; // блоков/сек
        if (speed >= minSpeed.get()) return;

        if (fireworksUsed >= maxFireworks.get()) {
            error("Потратил %d фейерверков, а нужной высоты (%.0f) нет. Опусти границы pitch40 в ElytraFly или подними max-takeoff-fireworks.",
                fireworksUsed, climbAltitude());
            stopNavigation();
            return;
        }

        FindItemResult rocket = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!rocket.found()) {
            error("Нет фейерверков в хотбаре/оффхенде для набора высоты.");
            stopNavigation();
            return;
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

        fireworksUsed++;
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

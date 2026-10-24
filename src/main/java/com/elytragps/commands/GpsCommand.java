package com.elytragps.commands;

import com.elytragps.modules.ElytraGps;
import com.elytragps.util.Point;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/**
 * .gps                 — лететь по всем точкам из pointelytra.txt
 * .gps file            — то же самое
 * .gps x z             — лететь к точке (x, z) на крейсерской высоте
 * .gps x y z           — лететь к точке (x, y, z)
 * .gps stop            — остановить маршрут
 * .gps status          — где мы и сколько осталось
 */
public class GpsCommand extends Command {
    public GpsCommand() {
        super("gps", "Летит на элитрах к координатам или по точкам из pointelytra.txt.");
    }

    private static ElytraGps gps() {
        return Modules.get().get(ElytraGps.class);
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        // .gps
        builder.executes(ctx -> {
            gps().startFromFile();
            return SINGLE_SUCCESS;
        });

        // .gps file
        builder.then(literal("file").executes(ctx -> {
            gps().startFromFile();
            return SINGLE_SUCCESS;
        }));

        // .gps stop
        builder.then(literal("stop").executes(ctx -> {
            if (gps().isNavigating()) {
                gps().stopNavigation();
                info("Маршрут остановлен.");
            } else {
                warning("Маршрут и так не запущен.");
            }
            return SINGLE_SUCCESS;
        }));

        // .gps status
        builder.then(literal("status").executes(ctx -> {
            info(gps().status());
            return SINGLE_SUCCESS;
        }));

        // .gps <x> <z>   и   .gps <x> <y> <z>
        builder.then(argument("x", DoubleArgumentType.doubleArg())
            .then(argument("b", DoubleArgumentType.doubleArg())
                // два числа: x z
                .executes(ctx -> {
                    double x = DoubleArgumentType.getDouble(ctx, "x");
                    double z = DoubleArgumentType.getDouble(ctx, "b");
                    gps().startSingle(new Point(x, null, z));
                    return SINGLE_SUCCESS;
                })
                // три числа: x y z
                .then(argument("c", DoubleArgumentType.doubleArg()).executes(ctx -> {
                    double x = DoubleArgumentType.getDouble(ctx, "x");
                    double y = DoubleArgumentType.getDouble(ctx, "b");
                    double z = DoubleArgumentType.getDouble(ctx, "c");
                    gps().startSingle(new Point(x, y, z));
                    return SINGLE_SUCCESS;
                }))
            )
        );
    }
}

package com.elytragps.commands;

import com.elytragps.modules.ElytraGps;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/**
 * .nextpoint — лететь к следующей точке маршрута (то же делает бинд в настройках модуля Elytra GPS).
 */
public class NextPointCommand extends Command {
    public NextPointCommand() {
        super("nextpoint", "Переключиться на следующую точку маршрута .gps.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(ctx -> {
            Modules.get().get(ElytraGps.class).nextPoint();
            return SINGLE_SUCCESS;
        });
    }
}

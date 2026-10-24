package com.elytragps;

import com.elytragps.commands.GpsCommand;
import com.elytragps.commands.NextPointCommand;
import com.elytragps.modules.ElytraGps;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class ElytraGpsAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Elytra GPS");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Elytra GPS");

        Modules.get().add(new ElytraGps());

        Commands.add(new GpsCommand());
        Commands.add(new NextPointCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.elytragps";
    }

    @Override
    public GithubRepo getRepo() {
        // Поменяй на свой репозиторий, если выложишь аддон на GitHub.
        return new GithubRepo("your-name", "elytra-gps");
    }
}

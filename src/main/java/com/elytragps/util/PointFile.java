package com.elytragps.util;

import meteordevelopment.meteorclient.MeteorClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Читает pointelytra.txt.
 * <p>
 * Формат: одна точка на строку, "x z" или "x y z". Разделители — пробел, запятая или ';'.
 * Пустые строки и строки, начинающиеся с # или //, пропускаются.
 * <p>
 * Ищем файл сначала в .minecraft/meteor-client/, потом в .minecraft/.
 */
public final class PointFile {
    private static final Pattern SPLIT = Pattern.compile("[\\s,;]+");

    private PointFile() {
    }

    public record Result(List<Point> points, List<String> errors, Path path, boolean created) {
    }

    public static Path locate(String fileName) {
        Path inMeteor = MeteorClient.FOLDER.toPath().resolve(fileName);
        if (Files.exists(inMeteor)) return inMeteor;

        Path inGame = mc.gameDirectory.toPath().resolve(fileName);
        if (Files.exists(inGame)) return inGame;

        // По умолчанию (и для создания примера) — папка meteor-client.
        return inMeteor;
    }

    public static Result load(String fileName) {
        Path path = locate(fileName);
        List<Point> points = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (!Files.exists(path)) {
            boolean created = createExample(path);
            return new Result(points, errors, path, created);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add("Не удалось прочитать файл: " + e.getMessage());
            return new Result(points, errors, path, false);
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("﻿")) line = line.substring(1); // BOM
            line = line.trim();

            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

            // Комментарий в конце строки
            int cut = line.indexOf('#');
            if (cut >= 0) line = line.substring(0, cut).trim();
            if (line.isEmpty()) continue;

            String[] parts = SPLIT.split(line);
            try {
                if (parts.length == 2) {
                    points.add(new Point(parseNum(parts[0]), null, parseNum(parts[1])));
                } else if (parts.length == 3) {
                    points.add(new Point(parseNum(parts[0]), parseNum(parts[1]), parseNum(parts[2])));
                } else {
                    errors.add("Строка " + (i + 1) + ": ожидалось 2 или 3 числа, найдено " + parts.length);
                }
            } catch (NumberFormatException e) {
                errors.add("Строка " + (i + 1) + ": не число (\"" + line + "\")");
            }
        }

        return new Result(points, errors, path, false);
    }

    private static double parseNum(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static boolean createExample(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, String.join(System.lineSeparator(),
                "# Точки для .gps — одна на строку.",
                "# Формат: x z   или   x y z   (разделитель: пробел, запятая или ;)",
                "# Если y не указан — летим на крейсерской высоте.",
                "# Пример:",
                "# 1000 1000",
                "# 2500 180 -3000",
                ""), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

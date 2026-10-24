
package com.elytragps.util;

import meteordevelopment.meteorclient.MeteorClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;

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

        return inMeteor;
    }

    private static Path deletedPath(String fileName) {
        Path original = locate(fileName);
        return original.resolveSibling("pointdelete.txt");
    }

    public static Result load(String fileName) {
        Path path = locate(fileName);
        List<Point> points = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (!Files.exists(path)) {
            boolean created = createExample(path);
            return new Result(points, errors, path, created);
        }

        Set<String> deleted = loadDeleted(fileName);

        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add("Не удалось прочитать файл: " + e.getMessage());
            return new Result(points, errors, path, false);
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            line = line.trim();

            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

            int cut = line.indexOf('#');
            if (cut >= 0) line = line.substring(0, cut).trim();
            if (line.isEmpty()) continue;

            String[] parts = SPLIT.split(line);

            try {
                Point point;
                if (parts.length == 2) {
                    point = new Point(parseNum(parts[0]), null, parseNum(parts[1]));
                } else if (parts.length == 3) {
                    point = new Point(parseNum(parts[0]), parseNum(parts[1]), parseNum(parts[2]));
                } else {
                    errors.add("Строка " + (i + 1) + ": ожидалось 2 или 3 числа, найдено " + parts.length);
                    continue;
                }

                if (!deleted.contains(pointKey(point))) {
                    points.add(point);
                }
            } catch (NumberFormatException e) {
                errors.add("Строка " + (i + 1) + ": не число (\"" + line + "\")");
            }
        }

        return new Result(points, errors, path, false);
    }

    /**
     * Сохраняет достигнутую точку в pointdelete.txt.
     * Повторная запись той же точки не создаётся.
     */
    public static boolean markDeleted(String fileName, Point point) {
        Path path = deletedPath(fileName);
        String key = pointKey(point);

        try {
            Files.createDirectories(path.getParent());

            Set<String> existing = loadDeleted(fileName);
            if (existing.contains(key)) return true;

            String line = formatPoint(point) + System.lineSeparator();
            Files.writeString(
                path,
                line,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Set<String> loadDeleted(String fileName) {
        Set<String> deleted = new HashSet<>();
        Path path = deletedPath(fileName);

        if (!Files.exists(path)) return deleted;

        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

                String[] parts = SPLIT.split(line);
                try {
                    Point point;
                    if (parts.length == 2) {
                        point = new Point(parseNum(parts[0]), null, parseNum(parts[1]));
                    } else if (parts.length == 3) {
                        point = new Point(parseNum(parts[0]), parseNum(parts[1]), parseNum(parts[2]));
                    } else {
                        continue;
                    }
                    deleted.add(pointKey(point));
                } catch (NumberFormatException ignored) {
                    // Игнорируем некорректные строки в файле посещённых точек.
                }
            }
        } catch (IOException ignored) {
            // Если файл недоступен, не считаем точки посещёнными.
        }

        return deleted;
    }

    private static String pointKey(Point point) {
        return Double.toString(point.x()) + " " +
            (point.hasY() ? Double.toString(point.y()) : "_") + " " +
            Double.toString(point.z());
    }

    private static String formatPoint(Point point) {
        if (point.hasY()) {
            return point.x() + " " + point.y() + " " + point.z();
        }
        return point.x() + " " + point.z();
    }

    private static double parseNum(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static boolean createExample(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, String.join(System.lineSeparator(),
                "# Точки для .gps — одна на строку.",
                "# Формат: x z или x y z.",
                "# Разделитель: пробел, запятая или ;",
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

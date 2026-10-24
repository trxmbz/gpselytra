# Elytra GPS — аддон для Meteor Client (Minecraft 26.3)

Летит на элитрах к координатам или по списку точек из файла.

## Команды (префикс Meteor по умолчанию `.`)

| Команда | Что делает |
|---|---|
| `.gps x z` | лететь к точке на крейсерской высоте |
| `.gps x y z` | лететь к точке с высотой |
| `.gps` или `.gps file` | лететь по всем точкам из `pointelytra.txt` |
| `.nextpoint` | перейти к следующей точке |
| `.gps stop` | остановить маршрут |
| `.gps status` | текущая точка и расстояние |

Бинд на «следующую точку» — в настройках модуля **Elytra GPS → next-point-key** (работает, пока маршрут запущен).

## Файл pointelytra.txt

Кладётся в `.minecraft/meteor-client/` (или в `.minecraft/`). Если файла нет — `.gps` сам создаст пример.

```
# комментарий
1000 1000          # x z
2500 180 -3000     # x y z
-500, 200, 800     # запятые тоже можно
```

## Настройки модуля
- `auto-advance` — сам переходить к следующей точке; выключи, чтобы кружить над точкой до `.nextpoint`
- `auto-takeoff`, `use-fireworks`, `min-speed`, `firework-cooldown`
- `cruise-altitude` (0 = высота старта + 40), `max-climb-pitch`, `max-dive-pitch`, `turn-speed`
- `avoid-obstacles` — задирает нос, если впереди блоки
- `arrive-radius`, `orbit-radius`, `file-name`

## Сборка
Нужен JDK 25. `./gradlew build` → jar в `build/libs/`, кладёшь в `mods/` рядом с Meteor Client 26.3 и Fabric Loader.

# Unseen — Fabric 1.21.1 horror mod

Требования для игры: Minecraft 1.21.1, Fabric Loader >= 0.16.0, Fabric API 0.102.0+1.21.1, Java 21.

## Как получить .jar
**Вариант A (без установки чего-либо):** залей папку на GitHub -> вкладка Actions -> build -> Artifacts -> `unseen-mod`.
**Вариант B (локально):** JDK 21 + Gradle 8.8+, в папке проекта: `gradle build`. Файл: `build/libs/unseen-1.0.0.jar` (не `-sources`).
Закинь jar в `.minecraft/mods` вместе с Fabric API.

## Команды (op, уровень 2)
`/horror status` · `/horror phase <1-3>` · `/horror scare` · `/horror reset` · `/horror on|off`

## Настройка
Тайминги и урон — константы в начале `HorrorManager.java` (P2_START, P3_START, MAX_END, SCARE_DAMAGE).

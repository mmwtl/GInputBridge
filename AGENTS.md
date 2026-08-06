# AGENTS.md

Инструкции для AI-агентов и разработчиков, работающих с репозиторием GInputBridge. Файл действует
для всего репозитория. Если в подкаталоге появится свой `AGENTS.md`, он уточняет эти правила только
для этого поддерева.

## Принципы работы

- Не соглашайся с постановкой или предлагаемым решением автоматически. Проверяй предпосылки по
  коду и конфигурации; слабое или опасное решение называй таким прямо и предлагай более надёжное.
- Отделяй подтверждённые факты от предположений. При неопределённости указывай её и не выдавай
  поведение автомобильной прошивки за проверенное на обычном Android-устройстве.
- Перед изменениями прочитай затронутые классы, их вызывающий код, Gradle-конфигурацию модуля и
  релевантные тесты. Не делай широких рефакторингов в рамках локальной задачи.
- Сохраняй пользовательские изменения в рабочем дереве. Не откатывай и не форматируй несвязанные
  файлы. Не применяй destructive Git-команды без прямого запроса.
- Используй `rg`/`rg --files` для поиска. Не анализируй содержимое `build/`, `.gradle/`, `.kotlin/`
  и APK как исходный код.
- Источник истины по сборке и вариантам — текущие `*.gradle.kts`, `settings.gradle.kts`, version
  catalog и manifests. README, документация и имена старых APK могут отставать.

## Что это за проект

GInputBridge — Android-приложение для автомобильных головных устройств Geely/EcarX. Оно сочетает:

- Compose launcher и launcher overlay;
- обработку аппаратных клавиш и управление media sessions;
- системные overlays, accessibility/notification services и boot hooks;
- интеграцию с OneOS, ECarX и автомобильными API;
- локальный ADB/telnet transport для операций, недоступных обычному приложению;
- versioned Messenger API для AtlasMediaWidget.

Идентификатор приложения и namespace: `com.salat.gbinder`. Основная точка инициализации —
`app/src/main/java/com/salat/gbinder/App.kt`; основная UI activity — `MainActivity`; отдельная
launcher activity — `features/launcher/LauncherEntryActivity.kt`.

Приложение рассчитано на привилегированную и vendor-specific среду. Успешная сборка или запуск на
эмуляторе не доказывает работоспособность OneOS/ECarX, overlays, аппаратных клавиш, QNX, boot и
system-service сценариев.

## Структура репозитория

- `app/` — application module, Compose UI, services, launcher, media bridge, DataStore, ADB/QNX и
  feature-код.
- `core/coroutines/` — Hilt qualifiers/scopes и общая coroutine-инфраструктура.
- `core/stateKeeper/` — общие state/signal flows между приложением и сервисами.
- `core/car/` — доменный интерфейс и реализация работы с автомобильными свойствами.
- `core/remoteConfig/` — Firebase Remote Config и проверка обновлений.
- `core/filedownloader/` — загрузка и очистка файлов.
- `com_geely/`, `adaptapi/`, `ecarx_car/`, `ecarx_fw/` — shadow/stub API vendor-прошивки с
  оригинальными package names и сигнатурами.
- `baselineProfile/` — Macrobenchmark и генерация baseline profile для `:app`.
- `docs/atlas-media-bridge.md` — внешний контракт Messenger media bridge v1.
- `apk/` — архив готовых APK, не исходный код и не источник текущей версии.

В `app` есть крупные legacy-файлы (`App.kt`, `MainActivity.kt`). Для новой изолированной логики
предпочитай существующие `features/`, `repository`, `datastore`, `media/bridge` и core-модули. Не
расщепляй legacy-класс только ради эстетики: такой рефакторинг имеет большой regression surface.

## Toolchain и варианты сборки

Зафиксированная конфигурация:

- JDK 17;
- Gradle Wrapper 8.11.1;
- Android Gradle Plugin 8.10.1;
- Kotlin 2.1.10;
- `compileSdk`/`targetSdk` 35, `minSdk` 26 (`baselineProfile` имеет `minSdk` 28);
- Compose, Coroutines/Flow, Hilt/KSP, DataStore, Firebase.

Не обновляй JDK, Gradle, AGP, Kotlin, Compose BOM или набор SDK попутно: это отдельная миграция с
проверкой всех вариантов.

Flavor dimension `env`:

- `prod` — целевой вариант для головного устройства;
- `emu` — вариант для эмулятора и локальных Android-проверок, version name получает `-emu`.

Build types включают `debug`, minified `release`, а плагины также создают
`nonMinifiedRelease`/`benchmarkRelease`. Связка vendor-модулей зависит от variant/configuration в
`app/build.gradle.kts` и `core/car/build.gradle.kts`. Не заменяй `implementation` на `compileOnly`
или обратно, не проверив compile/runtime classpath обоих flavor и поведение на целевой прошивке.

## Команды сборки и проверки

Запускай команды из корня репозитория. У `gradlew` сейчас нет executable-bit, поэтому используй
`sh gradlew`, а не `./gradlew`.

Быстрая сборка целевого debug-варианта:

```bash
sh gradlew :app:assembleProdDebug
```

Сборка варианта для эмулятора:

```bash
sh gradlew :app:assembleEmuDebug
```

Основные unit tests:

```bash
sh gradlew :app:testProdDebugUnitTest
```

Один JVM test class:

```bash
sh gradlew :app:testProdDebugUnitTest \
  --tests 'com.salat.gbinder.media.bridge.MediaCommandRouterTest'
```

Lint целевого debug-варианта:

```bash
sh gradlew :app:lintProdDebug
```

Разумная локальная проверка большинства изменений в `app`:

```bash
sh gradlew :app:testProdDebugUnitTest :app:lintProdDebug :app:assembleProdDebug
```

Instrumentation tests требуют уже запущенное подходящее устройство/эмулятор:

```bash
sh gradlew :app:connectedEmuDebugAndroidTest
```

Для изменения library-модуля сначала запускай его узкую проверку, например:

```bash
sh gradlew :core:stateKeeper:testDebugUnitTest :core:stateKeeper:lintDebug
```

Если менялись manifests, ресурсы, flavor wiring или vendor-зависимости, собери как минимум оба
debug-варианта:

```bash
sh gradlew :app:assembleProdDebug :app:assembleEmuDebug
```

Не запускай `clean` без причины: он удаляет все кэши сборки проекта и сильно замедляет проверку.
Не используй `lintFix` как общую проверку — он меняет файлы.

## Release и baseline profile

`prepareRelease` сначала генерирует prod release baseline profile, затем собирает
`:app:assembleProdRelease`. По умолчанию baseline profile использует managed device
`mediumTablet30`; альтернативно можно передать `-Pbp.useConnectedDevices=true`.

```bash
sh gradlew prepareRelease
```

Это тяжёлая, device-dependent и потенциально подписываемая операция. Не запускай её для обычной
проверки и не считай обязательной без прямого запроса на release/baseline profile.

Подписание подключается через свойство `secure.signing` только если указанный файл существует.
Шаблон находится в `app/_secure.signing.gradle`; реальные `secure.signing.gradle`, `*.jks`, пароли,
ключи и локальные пути нельзя читать в отчёт, логировать, коммитить или менять. Не подменяй signing
config и не публикуй APK/AAB без прямого запроса.

Номер версии берётся только из `app/build.gradle.kts`. При выпуске `versionCode` должен монотонно
расти, но не повышай его автоматически в функциональной задаче.

## Архитектурные соглашения

- Kotlin formatting — official style, 4 пробела. В проекте не настроен ktlint/detekt; не добавляй
  новый formatter или массовое переформатирование без отдельной задачи.
- Сохраняй существующее разделение: UI/Android adapter -> domain repository/interface -> data
  implementation. Зависимости подключай через существующие Hilt modules/qualifiers.
- Долгие, сетевые, Binder-, file- и shell-операции не должны выполняться на main thread. Используй
  внедрённые coroutine scopes/dispatchers, структурированную отмену и существующие Flow-модели.
- Для Compose поднимай состояние к ViewModel/repository, используй immutable UI state и не запускай
  необратимые side effects прямо во время composition. Учитывай lifecycle overlay `Service`, а не
  только activity.
- Каждый зарегистрированный callback/listener/receiver, Binder connection и coroutine collector
  должен иметь симметричное отключение при смерти service/owner. Automotive services регулярно
  переподключаются после Binder death.
- Не проглатывай исключения без диагностики там, где отказ влияет на пользователя или состояние.
  Не логируй tokens, ключи, пользовательские media metadata, shell credentials или содержимое
  приватных файлов.
- Новые пользовательские строки клади в resources. Поддерживай `values/strings.xml` и
  `values-ru/strings.xml`; если перевод неизвестен, явно сообщи об этом вместо случайного перевода.

## DataStore и совместимость настроек

`DataStoreRepositoryImpl` использует preferences DataStore `settings`. Ключи из `GeneralPrefs`,
`LauncherPrefs`, `NoBackupPrefs` и сериализованные entity являются долговременным форматом, потому
что настройки экспортируются/импортируются через `.gibb`.

- Не переименовывай и не переиспользуй существующий preference key для другого смысла.
- Для изменения типа/семантики проектируй миграцию и обратную совместимость импорта.
- Новое поле должно иметь безопасный default для существующей установки.
- Изменения backup/import проверяй на данных старой версии и на частично заполненном payload.
- Не сбрасывай пользовательские настройки как способ исправить несовместимость.

## Media bridge — публичный протокол

Код в `app/src/main/java/com/salat/gbinder/media/bridge/` и
`docs/atlas-media-bridge.md` образует внешний versioned IPC contract.

- Не меняй числовые `what`, status codes, capability bits, имена Bundle keys или строковые enum в
  существующей версии.
- Добавления должны быть backward-compatible либо требовать новой версии протокола с negotiation.
- При изменении контракта синхронно обновляй `MediaBridgeContract`, сериализацию/парсинг, router,
  `docs/atlas-media-bridge.md` и JVM tests.
- Snapshot должен оставаться атомарным; position ticks намеренно могут обновлять query baseline без
  публикации нового поколения каждую секунду.
- Bitmap не передаётся через Binder. Сохраняй ограничение artwork, FileProvider URI grants и отзыв
  grants при unregister/Binder death.
- `MediaBridgeService` сейчас намеренно exported и открыт без permission/allowlist. Не описывай его
  как защищённый. Любое изменение модели доступа — отдельное API/security решение; любую новую
  входную команду валидируй как недоверенный IPC input.

Минимальная проверка изменений bridge:

```bash
sh gradlew :app:testProdDebugUnitTest \
  --tests 'com.salat.gbinder.media.bridge.*'
```

## Vendor API и автомобильная среда

`com_geely`, `adaptapi`, `ecarx_car`, `ecarx_fw` содержат vendor-compatible классы, многие из них
получены из закрытых/декомпилированных API. Не применяй к ним обычную «чистку» Java/Kotlin:

- сохраняй package, class name, inheritance, Binder Stub/Proxy, method signature, constants и
  Parcelable layout;
- не переименовывай странные symbols и не удаляй кажущийся мёртвым код без проверки ABI;
- не добавляй туда бизнес-логику приложения — размещай adapter в `app` или `core/car`;
- изменение stub допустимо только при наличии подтверждённой сигнатуры целевой прошивки;
- компиляция со stub не подтверждает, что метод существует и разрешён на head unit.

Для vendor-вызовов предусматривай отсутствие сервиса, null/ошибочный Binder result, Binder death,
несовпадение прошивки и повторное подключение. Не маскируй эти сценарии fake-успехом.

## ADB, telnet, QNX и системные операции

Код `adb/`, `features/clusterBackground/`, configurator и launcher умеет выполнять команды, менять
системные настройки, packages и файлы головного устройства. Это high-risk поверхность.

- Не запускай device-mutating ADB/telnet/QNX команды только ради проверки кода.
- Перед реальным запуском однозначно определи target device, команду, права и обратимость.
- Не подставляй необработанные пользовательские строки в shell. Используй строгую валидацию или
  безопасное quoting и проверяй команды с пробелами/кавычками/метасимволами.
- Сохраняй timeout, cancellation и понятные ошибки; соединение с `adbd`/telnet может зависнуть.
- Для операций с системными файлами сначала проверяй существование, формат, свободное место и
  возможность восстановления. Частичная запись не должна оставлять QNX/launcher в сломанном виде.
- Никогда не считай наличие `su`, порта 5555/7777 или vendor content provider гарантированным.

## Manifest, permissions и компоненты

В приложении есть exported activities/receivers/service, accessibility и notification listener,
overlay windows, FileProvider, foreground services и boot initialization. При изменении manifest:

- проверяй merged manifest для затронутого variant;
- явно проверяй `android:exported`, intent filters, authorities и permission boundary;
- не расширяй exported surface без необходимости и валидации внешних extras/URIs;
- сохраняй совместимость существующих broadcast actions, если задача не требует versioned замены;
- учитывай ограничения фонового запуска и foreground service на API 26–35;
- проверяй, что FileProvider path/grant минимален и не открывает лишние каталоги.

## Тестовая стратегия

Выбирай проверки по риску, а не запускай всё механически:

- pure Kotlin/domain/router/state change — JVM unit tests и новый regression test;
- repository/DataStore/serialization — unit test с fake dependencies и проверка старого формата;
- Compose/resources/navigation — unit tests где возможно, lint, сборка и ручной emu smoke test;
- service/manifest/IPC/FileProvider — сборка обоих flavor, instrumentation test и проверка lifecycle;
- vendor/car/input/overlay/boot/ADB/QNX — локальные тесты плюс обязательная отдельная проверка на
  поддерживаемом head unit; укажи, если такой проверки не было;
- release/R8 change — minified build и проверка keep rules только по прямому запросу/необходимости.

Не оставляй новые `ExampleUnitTest`-подобные заглушки. Тесты должны фиксировать поведение и
регрессию. Для coroutine-кода предпочитай детерминированные fake clock/host/repository; не добавляй
реальные задержки и сеть в JVM tests.

## Не редактировать и не коммитить

Без прямой задачи не изменяй и не добавляй:

- `**/build/**`, `.gradle/`, `.kotlin/`, `captures/`;
- `local.properties`, `secure.signing.gradle`, `*.jks`, signing credentials;
- сгенерированные `app/src/emuRelease/generated/`, `app/src/prodRelease/` и baseline profiles;
- APK/AAB и содержимое `apk/`;
- `.DS_Store`, IDE metadata и машинно-зависимые пути.

Новые зависимости добавляй через `gradle/libs.versions.toml`, если нет веской причины делать иначе.
Не добавляй библиотеку для задачи, которую разумно решить стандартным Android/Kotlin API.

## Критерии готовности

Перед завершением задачи:

1. Проверь diff и удали случайные generated/formatting изменения.
2. Выполни минимальный релевантный набор тестов, lint/compile и зафиксируй точные команды.
3. Для изменения публичного поведения обнови README/`docs/` и versioned contract, если применимо.
4. Укажи, что проверено фактически, а что не проверено из-за отсутствия head unit, signing или
   подходящего эмулятора.
5. В итоговом отчёте кратко перечисли изменённые файлы, результат проверок и оставшиеся риски. Не
   заявляй «всё работает», если была только компиляция.

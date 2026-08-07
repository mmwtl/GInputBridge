# Atlas Media Bridge protocol v1

`MediaBridgeService` — full-duplex IPC между GInputBridge и AtlasMediaWidget. Сервис использует
Android `Messenger`; AIDL не нужен, потому что контракт состоит только из однонаправленных команд,
асинхронных результатов и versioned `Bundle` без сложных parcelable-моделей.

Существующий broadcast API GInputBridge не изменён и остаётся доступен для обратной совместимости.
Новые media-команды через broadcast не добавляются.

## Binding и доступ

Клиент привязывается explicit intent:

```kotlin
val intent = Intent("com.salat.gbinder.media.BIND").apply {
    component = ComponentName(
        "com.salat.gbinder",
        "com.salat.gbinder.media.bridge.MediaBridgeService",
    )
}
bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

Сервис `exported` и намеренно не имеет permission, package allowlist или проверки signing
certificate. Любое установленное приложение может выполнить `REGISTER`/`GET_SNAPSHOT`, получать
push snapshots, читать выданный ему artwork URI и отправлять управляющий `COMMAND`.

`Message.sendingUid` используется только для получения package names, которым нужно выдать
временный read grant на artwork URI. Он не используется для контроля доступа.

## Общие правила сообщений

- Текущая версия: `protocolVersion=1`; поддерживаемый диапазон: `[1, 1]`.
- Каждое client message содержит `data["protocolVersion"]: Int`.
- `replyTo` обязателен для `REGISTER`, `GET_SNAPSHOT` и `COMMAND`.
- `requestId: String` обязателен для `COMMAND`, должен быть уникальным среди незавершённых запросов.
- Строковые enum передаются ровно в uppercase, как перечислено ниже.
- Время `timestamp` — Unix epoch в миллисекундах; `updateElapsedRealtime` использует монотонную
  шкалу `SystemClock.elapsedRealtime()`.

При неподдерживаемой версии сервер отвечает `ERROR` со статусом `UNSUPPORTED_VERSION` и полями
`minProtocolVersion`/`maxProtocolVersion`.

## Client → GInputBridge

### `what=1` — `REGISTER`

```text
protocolVersion: Int = 1
requestId: String?       // correlation для REGISTERED, может быть пустым
replyTo: Messenger
```

Регистрация идемпотентна для одного Binder. После `REGISTERED` сервер сразу отправляет текущий
`SNAPSHOT`, затем отправляет новые атомарные snapshots при изменениях. Binder death автоматически
удаляет подписку.

### `what=2` — `UNREGISTER`

```text
protocolVersion: Int = 1
replyTo: Messenger
```

### `what=3` — `GET_SNAPSHOT`

```text
protocolVersion: Int = 1
requestId: String?       // возвращается в SNAPSHOT, если не пуст
replyTo: Messenger       // должен быть зарегистрирован
```

Запрос читает один текущий immutable snapshot. Он не собирает поля несколькими Binder-вызовами.

### `what=4` — `COMMAND`

Общие поля:

```text
protocolVersion: Int = 1
requestId: String        // непустой
command: String
replyTo: Messenger       // должен быть зарегистрирован
```

Команды без дополнительных аргументов:

```text
PLAY | PAUSE | TOGGLE | NEXT | PREVIOUS
```

`SEEK_TO`:

```text
position: Long           // миллисекунды, >= 0
```

`SET_SOURCE`:

```text
source: String           // UNKNOWN|USB|BT|RADIO|ONLINE|OTHER|YUNTING|CPAA
appSource: String?       // OneOS AppSource; UNKNOWN при отсутствии/неизвестном значении
autoplay: Boolean = true
```

Команды выполняются асинхронно. Hardware media keys и IPC используют один `MediaCommandRouter`:
сначала firmware-specific OneOS route для текущего native source, затем выбранная MediaSession,
затем explicit media-button fallback для текущего/default приложения. Для CPAA команды playback
возвращают `NOT_SUPPORTED`, потому что CarPlay владеет media keys; `SET_SOURCE` разрешён.

## GInputBridge → client

Каждое server message содержит `protocolVersion: Int=1`.

### `what=100` — `REGISTERED`

```text
requestId: String
status: Int = 0
minProtocolVersion: Int = 1
maxProtocolVersion: Int = 1
```

### `what=101` — `SNAPSHOT`

| Key | Bundle type | Значение |
|---|---|---|
| `protocolVersion` | `Int` | `1` |
| `generation` | `Long` | Монотонно растёт на каждый опубликованный atomic change |
| `timestamp` | `Long` | Unix epoch ms времени сборки |
| `requestId` | `String?` | Только для ответа на `GET_SNAPSHOT` |
| `backendConnected` | `Boolean` | OneOS MediaCenter backend жив |
| `backendErrorCode` | `Int` | `0 NONE`, `1 CONNECTING`, `2 ONE_OS_DISCONNECTED`, `3 ONE_OS_ERROR` |
| `backendErrorMessage` | `String` | Диагностика; пусто при отсутствии ошибки |
| `audioSource` | `String` | Текущий audio source |
| `appSource` | `String` | Текущий OneOS app source |
| `sources` | `ArrayList<Bundle>` | Полный список source descriptors |
| `ownerPackage` | `String` | Package владельца session/native source |
| `ownerApp` | `String` | Display name или имя native source |
| `mediaId` | `String` | Media ID либо стабильный hash title/artist |
| `title` | `String` | Заголовок |
| `artist` | `String` | Исполнитель/подзаголовок |
| `album` | `String` | Альбом |
| `duration` | `Long` | ms; `-1`, если неизвестно |
| `position` | `Long` | Базовая позиция в ms; `-1`, если неизвестно |
| `updateElapsedRealtime` | `Long` | Монотонное время, к которому относится `position` |
| `speed` | `Float` | Playback speed |
| `playbackState` | `Int` | Полный `android.media.session.PlaybackState.STATE_*` |
| `playbackErrorCode` | `Int` | Числовой код ошибки backend; для framework `MediaSession` всегда `0` |
| `playbackErrorMessage` | `String` | Android playback error text |
| `playbackActions` | `Long` | Raw `PlaybackState.actions` |
| `capabilities` | `Long` | Нормализованная command bitmask |
| `artworkUri` | `String` | GInputBridge `content://`; пусто без обложки |
| `artworkRevision` | `Long` | Растёт при замене/очистке нормализованной обложки |

Каждый элемент `sources` содержит:

```text
id: String
connected: Boolean
available: Boolean
selected: Boolean
capabilities: Long
```

Список всегда содержит восемь v1 identifiers: `UNKNOWN`, `USB`, `BT`, `RADIO`, `ONLINE`, `OTHER`,
`YUNTING`, `CPAA`. `available` означает, что соответствующий OneOS manager/app доступен;
`connected` — что устройство/источник сейчас подключён; `selected` истинен ровно для текущего
source. USB/BT/CPAA обновляются OneOS device callbacks, не polling-циклом.

Для `RADIO` GInputBridge подписывается на OneOS `IRadioStateListener` и переиспользует поля v1:
`title` содержит RDS/DAB service name либо нормализованную частоту, `artist` — ensemble name либо
частоту, `mediaId` стабильно включает band, числовую частоту и service name. FM и DAB передаются в
`MHz` без потери значащих десятичных знаков, AM — в `kHz`. Callback статуса меняет только playback
поля и не стирает уже полученные данные станции. Новых Bundle keys для радио нет.

### Capability bits

| Bit | Hex | Команда |
|---:|---:|---|
| 0 | `0x01` | `PLAY` |
| 1 | `0x02` | `PAUSE` |
| 2 | `0x04` | `TOGGLE` |
| 3 | `0x08` | `NEXT` |
| 4 | `0x10` | `PREVIOUS` |
| 5 | `0x20` | `SEEK_TO` |
| 6 | `0x40` | `SET_SOURCE` |

Клиент должен отключать UI-команду, если соответствующий bit отсутствует. Сервер всё равно
перепроверяет capability и может вернуть `NOT_SUPPORTED`, если firmware/session изменилась после
snapshot.

### Progress

GInputBridge не публикует OneOS position callback каждую секунду. Текущая позиция вычисляется
клиентом только для playing state:

```text
estimated = position + (SystemClock.elapsedRealtime() - updateElapsedRealtime) * speed
estimated = estimated.coerceIn(0, duration) // если duration известен
```

На pause/seek/track/state change приходит новая база. `GET_SNAPSHOT` возвращает свежую базу,
включая последний непубликованный OneOS position tick.

### Artwork

GInputBridge читает доступный `MediaMetadata`/OneOS bitmap или URI, декодирует с downsampling,
ограничивает максимальную сторону 512 px и сохраняет JPEG quality 88 в приватный cache. В Binder
никогда не передаётся `Bitmap`. URI создаётся собственным `FileProvider`; перед отправкой snapshot
сервис вызывает `grantUriPermission` для package names зарегистрировавшегося UID. При
unregister/Binder death grant отзывается. Клиент должен связывать загрузку с
`artworkRevision`/`generation`, чтобы
поздний decode старого трека не перезаписал новый.

### `what=102` — `COMMAND_RESULT`

```text
requestId: String
status: Int
message: String
generation: Long         // generation backend на момент ответа
```

Status:

| Код | Имя | Значение |
|---:|---|---|
| 0 | `OK` | Команда передана выбранному backend/target |
| 1 | `INVALID_REQUEST` | Нет обязательного поля или аргумент вне диапазона |
| 2 | `UNSUPPORTED_VERSION` | Версия вне `[1,1]` |
| 3 | `UNAUTHORIZED` | Зарезервирован для совместимости; открытый v1 его не возвращает |
| 4 | `UNKNOWN_COMMAND` | Неизвестное имя команды |
| 5 | `BACKEND_UNAVAILABLE` | Нет OneOS/session/default target |
| 6 | `NOT_SUPPORTED` | Source/session не поддерживает команду |
| 7 | `FAILED` | Target был выбран, но вызов завершился ошибкой |
| 8 | `NOT_REGISTERED` | `replyTo` сначала должен выполнить `REGISTER` |

`OK` означает успешную передачу команды backend, но не обещает мгновенное изменение playback.
Результат и последующий `SNAPSHOT` — отдельные сообщения.

### `what=103` — `ERROR`

```text
requestId: String
status: Int
message: String
minProtocolVersion: Int? // при version error
maxProtocolVersion: Int? // при version error
```

## Проверки на реальной ГУ

Локальная сборка и unit tests не подтверждают firmware-specific поведение. На целевой Android 11
ГУ обязательно проверить:

1. bind/register/read/command из Atlas и другого test package без permission/signature ограничений;
2. reconnect после kill/restart обоих процессов и Binder death без дублированных callbacks;
3. USB mount/scan/unmount, BT on/connect/disconnect и CP/AA connect/disconnect flags;
4. source/appSource при переключении ONLINE/USB/BT/RADIO/CPAA/YUNTING;
5. PLAY/PAUSE/TOGGLE/NEXT/PREVIOUS для Radio, BT, USB и обычных MediaSession приложений;
6. SEEK_TO для ONLINE/session с `ACTION_SEEK_TO` и `NOT_SUPPORTED` без capability;
7. отсутствие двойного media event от hardware key после перехода на общий router;
8. artwork от Bitmap, readable content URI и отзыв grant после unregister/Binder death;
9. экстраполяцию progress, pause/seek correction и отсутствие ежесекундного Binder traffic;
10. sleep/wake, OneOS service reconnect и корректную очистку stale snapshot при disconnect.

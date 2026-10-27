package com.stasao.gcam

/** English display text for the Russian strings used by the existing UI and recorder state. */
internal object EnglishUi {
    private val exact = mapOf(
        "Левая" to "Left",
        "Правая" to "Right",
        "Перед" to "Front",
        "Зад" to "Rear",
        "Камеры и запись" to "Cameras & recording",
        "Записи" to "Recordings",
        "Автоматическая очистка архива" to "Automatic archive cleanup",
        "Архив записей" to "Recording archive",
        "Доступ выдан: старые записи удаляются автоматически по лимиту." to
            "Access granted: old recordings are deleted automatically to stay within the limit.",
        "Для удаления записей от предыдущих установок нужен однократный доступ ко всем файлам." to
            "Allow all-files access once to delete recordings from previous installations automatically.",
        "Android выдаёт доступ ко всему общему хранилищу. gCam удаляет по лимиту только свои записи в Movies/gCam выбранного накопителя; защищённые файлы сохраняются." to
            "Android grants access to all shared storage. gCam limits automatic cleanup to its recordings in Movies/gCam on the selected drive; protected files are kept.",
        "Разрешить автоматическую очистку" to "Allow automatic cleanup",
        ArchiveAccessRequiredException.MESSAGE to
            "Android blocked deletion of old recordings. Allow all-files access in Automatic archive cleanup, then start recording again.",
        "Недостаточно места в лимите архива или все старые файлы защищены" to
            "Not enough space within the archive limit, or all older files are protected",
        "Запись не запущена" to "Recording did not start",
        "Не удалось" to "Failed",
        "Нет доступа" to "No access",
        "Обновление" to "Update",
        "Обновление ●" to "Update ●",
        "Системный" to "System",
        "Русский" to "Russian",
        "Язык приложения" to "App language",
        "Проверка обновлений…" to "Checking for updates…",
        "Это debug-сборка. Обновления доступны после установки release APK." to
            "This is a debug build. Updates are available after installing a release APK.",
        "Установлена актуальная версия" to "You're up to date",
        "Релиз не найден. Для обновлений репозиторий и Release должны быть публичными." to
            "Release not found. The repository and release must be public to update.",
        "GitHub временно ограничил запросы. Попробуйте позже." to "GitHub temporarily rate-limited requests. Try again later.",
        "Некорректный тег релиза" to "Invalid release tag",
        "Некорректный размер APK" to "Invalid APK size",
        "У APK нет контрольной суммы SHA-256" to "APK has no SHA-256 checksum",
        "Некорректный адрес APK" to "Invalid APK URL",
        "Размер APK превышает заявленный" to "APK exceeds the declared size",
        "APK скачан не полностью" to "APK download is incomplete",
        "Контрольная сумма APK не совпадает" to "APK checksum does not match",
        "Не удалось сохранить APK" to "Could not save APK",
        "Скачанный файл не является APK" to "Downloaded file is not an APK",
        "APK предназначен для другого приложения" to "APK belongs to another app",
        "Версия APK не совпадает с релизом" to "APK version does not match the release",
        "Номер сборки не новее установленного" to "Build number is not newer than the installed version",
        "Подпись APK не совпадает с установленным gCam" to "APK signature does not match the installed gCam",
        "Обновление gCam" to "Update gCam",
        "USB/gCam и Movies/gCam" to "USB/gCam and Movies/gCam",
        "Проверить обновления" to "Check for updates",
        "Скачать обновление" to "Download update",
        "Идёт запись. Перед установкой завершите её, чтобы MP4-файлы закрылись корректно." to
            "Recording is in progress. Stop it before installing so MP4 files are finalized.",
        "Установку подтверждает системный экран Android. При обновлении приложение будет перезапущено." to
            "Confirm installation in the Android system dialog. The app will restart after updating.",
        "Высокая нагрузка" to "High workload",
        "Четыре камеры при 1280×800 могут перегрузить G636 и вызвать пропуски кадров. Рекомендуется 640×400, 15 FPS и 2 Мбит/с на камеру." to
            "Four cameras at 1280×800 may overload the G636 and drop frames. Recommended: 640×400, 15 FPS and 2 Mbps per camera.",
        "Всё равно записывать" to "Record anyway",
        "Назад" to "Back",
        "Запись во внутреннюю память" to "Record to internal storage",
        "Длительная запись четырёх камер создаёт постоянную нагрузку на внутренний накопитель и расходует его ресурс. По возможности используйте USB-накопитель." to
            "Long four-camera recordings continuously write to internal storage and wear it out. Use a USB drive when possible.",
        "Понимаю" to "I understand",
        "Отмена" to "Cancel",
        "Камеры показаны в плавающем окне поверх приложений." to "Cameras are shown in a floating window above other apps.",
        "Подготовка записи…" to "Preparing to record…",
        "Останавливаю превью и запускаю запись…" to "Stopping preview and starting recording…",
        "Почему нет изображения?" to "Why is there no image?",
        "Штатное 360° работает отдельно. Проверьте живые боковые камеры в превью выше, затем путь до нужного экрана." to
            "The factory 360° view is separate. Check the live side cameras above, then the path to the target display.",
        "ZERO-COPY" to "ZERO-COPY",
        "PBO fallback" to "PBO fallback",
        "проверка…" to "checking…",
        "Камеры для записи" to "Cameras to record",
        "Рекомендуемые настройки" to "Recommended settings",
        "Для 3–4 камер: 640×400 · 15 FPS · 2 Мбит/с на камеру" to "For 3–4 cameras: 640×400 · 15 FPS · 2 Mbps per camera",
        "Для 1–2 камер: 960×600 · 20 FPS · 3 Мбит/с на камеру" to "For 1–2 cameras: 960×600 · 20 FPS · 3 Mbps per camera",
        "Применить" to "Apply",
        "Разрешение записи" to "Recording resolution",
        "Частота записи" to "Recording frame rate",
        "Битрейт H.264 на камеру" to "H.264 bitrate per camera",
        "2 Мбит/с" to "2 Mbps",
        "3 Мбит/с" to "3 Mbps",
        "4 Мбит/с" to "4 Mbps",
        "6 Мбит/с" to "6 Mbps",
        "⚠ 4 камеры в максимальном разрешении: возможны сильная нагрузка, падение FPS и пропуски кадров." to
            "⚠ Four cameras at maximum resolution may cause high load, lower FPS and dropped frames.",
        "Длительность сегмента" to "Segment length",
        "1 мин" to "1 min",
        "2 мин" to "2 min",
        "3 мин" to "3 min",
        "5 мин" to "5 min",
        "Куда сохранять видео" to "Video storage",
        "Внутренняя" to "Internal",
        "Флешка для записи" to "Recording USB drive",
        "Подключённые USB-накопители не найдены" to "No connected USB drives found",
        "Обновить список флешек" to "Refresh USB drives",
        "Файлы будут в Movies/gCam на выбранной флешке. Системный выбор папки не требуется. При отключении USB запись не переключится на внутреннюю память." to
            "Files will be saved to Movies/gCam on the selected USB drive. No system folder picker is needed. If USB disconnects, recording will not switch to internal storage.",
        "Длительная запись изнашивает внутренний накопитель. Рекомендуется USB." to
            "Long recordings wear out internal storage. USB is recommended.",
        "Свободное место накопителя недоступно от системы. 512 ГБ не подставляются; лимит можно выставить после выбора доступной флешки." to
            "The system did not report free space. No assumed 512 GB capacity is used; set the limit after choosing an available drive.",
        "Метки на видео" to "Video overlays",
        "Дата и время" to "Date and time",
        "Поверх кадра, без перекодирования на CPU" to "Drawn on the frame without CPU re-encoding",
        "GPS-координаты" to "GPS coordinates",
        "Показываются только при свежем определении местоположения" to "Shown only when a recent location is available",
        "Скорость GPS" to "GPS speed",
        "Не скорость автомобиля по CAN; зависит от GPS" to "GPS speed, not vehicle CAN speed",
        "Нет разрешения на точное местоположение: на видео будет GPS -- / -- KM/H" to
            "Precise location permission is missing: video will show GPS -- / -- KM/H",
        "Автозапуск и индикатор" to "Auto-start & indicators",
        "Автозапуск gCam" to "Auto-start gCam",
        "Поднимать фоновую связь с ANHUD после загрузки системы" to "Start the ANHUD camera link after boot",
        "Автозапуск записи" to "Auto-start recording",
        "Сразу записывать выбранные выше камеры" to "Start recording the selected cameras automatically",
        "Точка: gCam + gBlind" to "Dot: gCam + gBlind",
        "Камера приборки и запись: красный — нет связи; синий — кадр был, запись не нужна; оранжевый — запись ожидается; зелёный — пишет" to
            "Cluster camera & recording: red — no link; blue — frame received, recording not requested; orange — recording expected; green — recording",
        "Точка: gCam + ANHUD" to "Dot: gCam + ANHUD",
        "Камера проекции и запись: красный — нет связи; синий — кадр был, запись не нужна; оранжевый — запись ожидается; зелёный — пишет" to
            "HUD camera & recording: red — no link; blue — frame received, recording not requested; orange — recording expected; green — recording",
        "Точка: gCam + ANHUD + gBlind" to "Dot: gCam + ANHUD + gBlind",
        "Разделена пополам: слева проекция, справа приборка; каждая половина учитывает запись" to
            "Split in half: HUD on the left, cluster on the right; each half reflects recording status",
        "Поверх остальных окон" to "Above other apps",
        "Плавающее окно с четырьмя камерами; его можно перемещать" to "Movable floating view with all four cameras",
        "Нужно разрешение на показ поверх окон" to "Display over other apps permission is required",
        "Плавающая кнопка записи" to "Floating record button",
        "Отдельная кнопка: красный — ошибка камер; синий — готово, запись выключена; оранжевый — USB не готов; зелёный — запись идёт. Нажмите для старта/остановки, перетащите для перемещения" to
            "Separate button: red — camera error; blue — ready, not recording; orange — USB not ready; green — recording. Tap to start or stop, drag to move.",
        "Сбросить положение всех точек" to "Reset all dot positions",
        "Вернёт три индикатора на исходные места, не меняя их включение." to
            "Moves the three dots back to their default positions without changing which are enabled.",
        "Положение плавающего элемента" to "Floating element position",
        "Обе" to "Both",
        "Запись" to "Record",
        "Положение можно задать даже для выключенного элемента; включённый перемещается сразу. Точки также можно перетаскивать пальцем." to
            "You can set the position of a disabled element. Enabled elements move immediately; dots can also be dragged.",
        "● красный — нет связи или сигнала" to "● red — no link or video",
        "● синий — сигнал получен, запись не запланирована" to "● blue — video received, recording not requested",
        "● оранжевый — сигнал получен, но ожидаемая запись не идёт" to "● orange — video received, expected recording is not running",
        "● зелёный — сигнал получен и запись идёт" to "● green — video received and recording is running",
        "Открыть специальные возможности" to "Open Accessibility settings",
        "Спецвозможности — резервный автозапуск для головных устройств, где обычный запуск после загрузки не срабатывает." to
            "Accessibility is a fallback auto-start method for head units where normal boot start does not work.",
        "Доступ ANHUD к камерам" to "ANHUD camera access",
        "ANHUD не установлен. После установки откройте эту страницу снова." to "ANHUD is not installed. Reopen this page after installing it.",
        "Доступ разрешён автоматически: ANHUD подписан тем же ключом, что и gCam." to
            "Access granted automatically: ANHUD and gCam have the same signing key.",
        "Доступ разрешён для установленной версии ANHUD." to "Access granted to the installed ANHUD version.",
        "Предупреждение: подпись ANHUD изменилась. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную." to
            "Warning: ANHUD's signature changed. Automatic access is off; verify the build and approve access manually.",
        "Предупреждение: ANHUD подписан неизвестным ключом. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную." to
            "Warning: ANHUD has an unknown signing key. Automatic access is off; verify the build and approve access manually.",
        "Доступ ANHUD отозван" to "ANHUD access revoked",
        "Доступ ANHUD разрешён" to "ANHUD access granted",
        "ANHUD больше не установлен" to "ANHUD is no longer installed",
        "Отозвать доступ" to "Revoke access",
        "Разрешить ANHUD" to "Allow ANHUD",
        "Разрешение привязано к подписи установленного ANHUD, а не к паролю или ключу внутри него." to
            "Permission is tied to the installed ANHUD signature, not a password or embedded key.",
        "Доступ gBlind к камерам приборки" to "gBlind access to cluster cameras",
        "gBlind не установлен" to "gBlind is not installed",
        "Доступ выдан автоматически: gBlind подписан тем же ключом, что и gCam." to
            "Access granted automatically: gBlind and gCam have the same signing key.",
        "Доступ к камерам приборки разрешён" to "Cluster camera access granted",
        "Предупреждение: подпись gBlind изменилась. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную." to
            "Warning: gBlind's signature changed. Automatic access is off; verify the build and approve access manually.",
        "Предупреждение: gBlind подписан неизвестным ключом. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную." to
            "Warning: gBlind has an unknown signing key. Automatic access is off; verify the build and approve access manually.",
        "Разрешить gBlind" to "Allow gBlind",
        "Остановить запись" to "Stop recording",
        "Начать запись" to "Start recording",
        "выключен" to "off",
        "нет свежего статуса" to "no recent status",
        "связь и кадры есть" to "connected, video received",
        "связь есть, кадры ожидаются" to "connected, waiting for video",
        "нет связи" to "no link",
        "запись идёт" to "recording",
        "запись не идёт · выбрана внутренняя память" to "not recording · internal storage selected",
        "запись не идёт · USB готов" to "not recording · USB ready",
        "Поделиться диагностикой записи" to "Share recording diagnostics",
        "Журнал сохраняется после перезагрузки и автоматически ограничен 2 МБ. Для разбора зависания отправьте его после поездки." to
            "The log survives reboots and is capped at 2 MB. Share it after a trip to investigate freezes.",
        "Подключение…" to "Connecting…",
        "Предупреждение о записи" to "Recording warning",
        "Запись продолжена." to "Recording resumed.",
        "Запись сейчас не идёт или восстанавливается." to "Recording is stopped or recovering.",
        "Поделиться диагностикой" to "Share diagnostics",
        "Выбрать файлы" to "Select files",
        "Выбрать все" to "Select all",
        "Снять выбор" to "Deselect all",
        "Удаление…" to "Deleting…",
        "Чтобы удалить файлы, сначала остановите запись." to "Stop recording before deleting files.",
        "Записей пока нет" to "No recordings yet",
        "Смотреть" to "Watch",
        "Скрыть файлы" to "Hide files",
        "Показать файлы" to "Show files",
        "внутренняя" to "internal",
        " · защищено" to " · protected",
        "Снять защиту" to "Unprotect",
        "Защитить" to "Protect",
        "Удалить" to "Delete",
        "Удалить выбранные файлы?" to "Delete selected files?",
        "Удаление завершено" to "Deletion complete",
        "ОК" to "OK",
        "Закрыть" to "Close",
        "Пауза" to "Pause",
        "Пуск" to "Play",
        "Регистратор остановлен" to "Recorder stopped",
        "Превью закрыто" to "Preview closed",
        "Подключение к QCarCam…" to "Connecting to QCarCam…",
        "Ошибка подключения" to "Connection error",
        "USB не готов" to "USB not ready",
        "Ошибка USB" to "USB error",
        "Запуск камер…" to "Starting cameras…",
        "Проверяю выбранный USB-накопитель…" to "Checking the selected USB drive…",
        "Освобождаю место для записи…" to "Freeing space for recording…",
        "Проверяю стабильность USB" to "Checking USB stability",
        "Камеры не запустились" to "Cameras failed to start",
        "Сервис камер отключился" to "Camera service disconnected",
        "Сервис камер недоступен" to "Camera service unavailable",
        "Не удалось запустить сервис камер" to "Could not start the camera service",
        "Некорректный HUD slot" to "Invalid HUD slot",
        "Нет кадров после перезапуска камер" to "No frames after restarting cameras",
        "Нет ответа регистратора" to "Recorder is not responding",
        "Проверка USB…" to "Checking USB…",
        "Ошибка записи на USB" to "USB recording error",
        "USB не подключён" to "USB not connected",
        "Выберите подключённый USB-накопитель" to "Select a connected USB drive",
        "Не удалось создать файл в Movies/gCam на выбранном накопителе" to
            "Could not create a file in Movies/gCam on the selected drive",
        "Не удалось опубликовать завершённый MP4" to "Could not publish the finalized MP4",
        "Неверная USB-папка" to "Invalid USB folder",
        "Выберите хотя бы одну камеру" to "Select at least one camera",
        "Запись не запущена: недостаточно места в лимите архива или все старые файлы защищены" to
            "Recording did not start: archive limit has insufficient space or all older files are protected",
        "Мало свободной ОЗУ; ожидаю освобождения памяти" to "Low available RAM; waiting for memory",
        "Ожидаю свободную память для восстановления записи…" to "Waiting for memory to recover recording…",
        "Запись всех камер восстановлена" to "Recording on all cameras recovered",
        "Обнаружен сбой записи и перезапуск камер. Проверьте видео и отправьте логи разработчику." to
            "Recording failed and the cameras restarted. Check the videos and send logs to the developer.",
        "Частые сбои записи: больше 5 перезапусков за 15 минут. Проверьте видео и отправьте логи разработчику." to
            "Frequent recording failures: over 5 restarts in 15 minutes. Check the videos and send logs to the developer.",
        "USB-накопитель недоступен. Запись возобновится после его подключения." to
            "USB drive unavailable. Recording will resume when it reconnects.",
        "нет связи или кадра" to "no link or video",
        "связь есть, запись идёт" to "connected, recording",
        "связь есть, запись ожидается" to "connected, recording expected",
        "связь есть, запись не запланирована" to "connected, recording not requested"
    )

    fun translate(text: String): String {
        val translated = exact[text] ?: dynamic(text) ?: text
        return translated.replace(" ГБ", " GB").replace(" МБ", " MB").replace(" КБ", " KB")
            .replace(" · макс. пауза ", " · max gap ").replace(" · пауза ", " · gap ")
            .replace(" мс", " ms")
    }

    private fun dynamic(text: String): String? {
        fun tail(prefix: String) = text.removePrefix(prefix)
        return when {
            text.startsWith("На этом ГУ нет окна выдачи доступа. Разрешение можно выдать через ADB: ") ->
                "This head unit has no access settings screen. Grant access through ADB: ${tail("На этом ГУ нет окна выдачи доступа. Разрешение можно выдать через ADB: ")}"
            text.startsWith("Камеры 360° · v") -> "360° Cameras · v${tail("Камеры 360° · v")}"
            text.startsWith("Доступна версия ") -> "Version ${tail("Доступна версия ")} is available"
            text.startsWith("Ошибка проверки: ") -> "Update check failed: ${translate(tail("Ошибка проверки: "))}"
            text.startsWith("Установлена версия ") -> "Installed version ${tail("Установлена версия ")}"
            text.startsWith("Что нового в ") -> "What's new in ${tail("Что нового в ")}"
            text.startsWith("Загрузка ") -> "Downloading ${tail("Загрузка ")}"
            text.startsWith("APK проверен. Можно установить ") -> "APK verified. You can install ${tail("APK проверен. Можно установить ")}"
            text.startsWith("Ошибка загрузки: ") -> "Download failed: ${translate(tail("Ошибка загрузки: "))}"
            text.startsWith("Установить ") -> "Install ${tail("Установить ")}"
            text.startsWith("Разрешите установку из gCam") -> "Allow installs from gCam, then tap the button again"
            text.startsWith("Не удалось открыть установщик: ") -> "Could not open installer: ${tail("Не удалось открыть установщик: ")}"
            text.startsWith("GitHub вернул HTTP ") -> "GitHub returned HTTP ${tail("GitHub вернул HTTP ")}"
            text.startsWith("В релизе ") -> tail("В релизе ").replace(" нет файла ", " does not contain file ").let { "Release $it" }
            text.startsWith("Не удалось скачать APK: ") -> "Could not download APK: ${tail("Не удалось скачать APK: ")}"
            text.startsWith("Не удалось запустить запись: ") -> "Could not start recording: ${translate(tail("Не удалось запустить запись: "))}"
            text.startsWith("Проекция: ") -> "HUD: ${translate(tail("Проекция: "))}"
            text.startsWith("Приборка: ") -> "Cluster: ${translate(tail("Приборка: "))}"
            text.startsWith("Ошибка воспроизведения ") -> "Playback error ${tail("Ошибка воспроизведения ")}"
            text.startsWith("Размер интерфейса: ") -> "Interface scale: ${tail("Размер интерфейса: ")}"
            text.startsWith("Размер кнопки записи: ") -> "Record button size: ${tail("Размер кнопки записи: ")}"
            text.startsWith("Горизонталь: ") -> "Horizontal: ${tail("Горизонталь: ")}"
            text.startsWith("Вертикаль: ") -> "Vertical: ${tail("Вертикаль: ")}"
            text.startsWith("Запись на: ") -> "Saving to: ${tail("Запись на: ")}"
            text.startsWith("Левая:") -> "Left:${translate(tail("Левая:"))}"
            text.startsWith("Правая:") -> "Right:${translate(tail("Правая:"))}"
            text.startsWith("Перед:") -> "Front:${translate(tail("Перед:"))}"
            text.startsWith("Зад:") -> "Rear:${translate(tail("Зад:"))}"
            text.startsWith("Ранее выбранная флешка ") -> "Previously selected USB drive ${tail("Ранее выбранная флешка ").removeSuffix(" сейчас не подключена")} is not connected"
            text.startsWith("✓ ВЫБРАНА · ") -> "✓ SELECTED · ${tail("✓ ВЫБРАНА · ")}"
            text.startsWith("Выбрать · ") -> "Select · ${tail("Выбрать · ")}"
            text.startsWith("Лимит архива: ") -> tail("Лимит архива: ").replace(" из доступных ", " of available ").replace(" ГБ", " GB").let { "Archive limit: $it" }
            text.startsWith("Нет разрешения на точное местоположение") -> "Precise location permission is missing: video will show GPS -- / -- KM/H"
            text.startsWith("Выбранный накопитель: занято ") -> tail("Выбранный накопитель: занято ").replace(" из ", " of ").replace(" · свободно ", " used · ").let { "Selected drive: $it free" }
            text.startsWith("Выбранный накопитель: свободно ") -> tail("Выбранный накопитель: свободно ").replace("; полный объём система не сообщает", "; total capacity was not reported").let { "Selected drive: $it free" }
            text.startsWith("Записи gCam: ") -> tail("Записи gCam: ").replace(" · максимум = свободно + записи − резерв 2 ГБ", " · maximum = free space + recordings − 2 GB reserve").let { "gCam recordings: $it" }
            text.startsWith("Ориентировочно ") -> tail("Ориентировочно ").replace(" ч записи", " h of recording").let { "Approximately $it" }
            text.startsWith("ANHUD: ") -> "ANHUD: ${translate(tail("ANHUD: "))}"
            text.startsWith("gBlind: ") -> "gBlind: ${translate(tail("gBlind: "))}"
            text.startsWith("Регистратор: ") -> "Recorder: ${translate(tail("Регистратор: "))}"
            text.startsWith("ANHUD не установлен.") -> "ANHUD is not installed. Reopen this page after installing it."
            text.startsWith("Не удалось изменить доступ: ") -> "Could not change access: ${tail("Не удалось изменить доступ: ")}"
            text.startsWith("Не удалось изменить защиту: ") -> "Could not change file protection: ${tail("Не удалось изменить защиту: ")}"
            text.startsWith("ошибка: ") -> "error: ${tail("ошибка: ")}"
            text.startsWith("USB не готов: ") -> "USB not ready: ${translate(tail("USB не готов: "))}"
            text.startsWith("Запись остановлена: ") -> "Recording stopped: ${translate(tail("Запись остановлена: "))}"
            text.startsWith("Запись остановлена") -> "Recording stopped${translate(tail("Запись остановлена"))}"
            text.startsWith("Запись: ") -> "Recording: ${tail("Запись: ").replace(" камер", " cameras")}"
            text.startsWith("Запись с ошибками: ") -> "Recording errors: ${tail("Запись с ошибками: ").replace(" камер", " cameras")}"
            text.startsWith("Запись не запущена: ") -> "Recording did not start: ${translate(tail("Запись не запущена: "))}"
            text.startsWith("Проверка USB: ") -> "USB check: ${translate(tail("Проверка USB: "))}"
            text.startsWith("Ожидаю выбранный USB: ") -> "Waiting for selected USB: ${translate(tail("Ожидаю выбранный USB: "))}"
            text.startsWith("Выберите хотя бы одну камеру") -> "Select at least one camera"
            text.startsWith("USB не подтвердил стабильную запись: ") -> "USB did not sustain stable writing: ${translate(tail("USB не подтвердил стабильную запись: "))}"
            text.startsWith("Не запустились камеры: ") -> "Cameras failed to start: ${tail("Не запустились камеры: ")}"
            text.startsWith("Нет записанных кадров 30 секунд: камера ") -> "No encoded frames for 30 seconds: camera ${tail("Нет записанных кадров 30 секунд: камера ")}"
            text.startsWith("Кодер камеры ") -> "Camera encoder ${tail("Кодер камеры ").replace(" остановился", " stopped")}"
            text.startsWith("Запись всех камер восстановлена после сбоя: ") -> "All camera recordings recovered after a failure: ${tail("Запись всех камер восстановлена после сбоя: ")}"
            text.startsWith("Нет кадров после перезапуска камер") -> "No frames after restarting cameras"
            text.startsWith("Сбой записи: ") -> tail("Сбой записи: ").replace(". Перезапуск камер через ", ". Restarting cameras in ").replace(" с (", " s (").replace(" за 15 мин)", " in 15 min)").replace(". Частые сбои — отправьте диагностику", ". Frequent failures — share diagnostics").let { "Recording failure: $it" }
            text.startsWith("USB-накопитель ") && text.endsWith(" не доступен через медиахранилище Android") ->
                "USB drive ${text.removePrefix("USB-накопитель ").removeSuffix(" не доступен через медиахранилище Android")} is not available through Android MediaStore"
            text.startsWith("Не удалось создать файл в Movies/gCam") -> "Could not create a file in Movies/gCam on the selected drive"
            text.startsWith("Не удалось опубликовать завершённый MP4") -> "Could not publish the finalized MP4"
            text.startsWith("Не удалось создать папку ") -> "Could not create folder ${tail("Не удалось создать папку ").replace(" на USB", " on USB")}"
            text.startsWith("Неверная USB-папка") -> "Invalid USB folder"
            text.startsWith("Откройте gCam и подтвердите доступ ") -> "Open gCam and approve ${tail("Откройте gCam и подтвердите доступ ").replace(" к камерам", " camera access")}"
            text.startsWith("Доступ к камерам gCam разрешён только ANHUD или gBlind") -> "Only ANHUD or gBlind may access gCam cameras"
            text.startsWith("gBlind не может управлять проекцией") -> "gBlind cannot control the HUD"
            text.startsWith("Камера ") -> "Camera ${tail("Камера ")}"
            text.startsWith("Остановить запись; ") -> "Stop recording; ${translate(tail("Остановить запись; "))}"
            text.startsWith("Начать запись; ") -> "Start recording; ${translate(tail("Начать запись; "))}"
            text == "камеры и внутренняя память готовы" -> "cameras and internal storage are ready"
            text == "камеры и USB готовы" -> "cameras and USB are ready"
            text == "нет подтверждённого кадра с камер" -> "no confirmed camera frame"
            text == "Нет ответа регистратора" -> "recorder is not responding"
            text.startsWith("Удалить выбранные (") -> "Delete selected (${tail("Удалить выбранные (")}"
            text.startsWith("Удалить выбранные файлы?") -> "Delete selected files?"
            text.startsWith("USB/gCam и Movies/gCam · ") -> tail("USB/gCam и Movies/gCam · ").replace(" записей / ", " recordings / ").replace(" файлов · ", " files · ").let { "USB/gCam and Movies/gCam · $it" }
            text.contains(" фрагм. · ") -> text.replace(" фрагм. · ", " segments · ")
            text.contains(" · пауза ") -> text.replace(" · пауза ", " · gap ").replace(" мс", " ms")
            text.contains(" · макс. пауза ") -> text.replace(" · макс. пауза ", " · max gap ").replace(" мс", " ms")
            text.contains(" · внутренняя") -> text.replace(" · внутренняя", " · internal")
            text.contains(" · защищено") -> text.replace(" · защищено", " · protected")
            text.startsWith("Удалено ") -> tail("Удалено ").replace(" из ", " of ").replace(" файлов", " files").replace(". Не удалось удалить ", ". Failed to delete ").replace("; они остались выделены.", "; they remain selected.").let { "Deleted $it" }
            text.startsWith("Будут безвозвратно удалены ") -> tail("Будут безвозвратно удалены ").replace(" файлов ", " files ").replace(" из gCam.", " from gCam.").replace(" В выбор входят защищённые файлы: ", " Protected files in selection: ").let { "Permanently delete $it" }
            text.startsWith(" В выбор входят защищённые файлы: ") -> " Protected files in selection: ${tail(" В выбор входят защищённые файлы: ")}"
            text.startsWith("Ошибка записи на USB") -> "USB recording error"
            text.startsWith("USB-накопитель недоступен.") -> "USB drive unavailable. Recording will resume when it reconnects."
            text.endsWith(" КБ") -> "${text.removeSuffix(" КБ")} KB"
            text.endsWith(" МБ") -> "${text.removeSuffix(" МБ")} MB"
            text.endsWith(" ГБ") -> "${text.removeSuffix(" ГБ")} GB"
            else -> null
        }
    }
}

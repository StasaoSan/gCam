# gCam — доступ к камерам 360° Geely G636

> ## Главное: откуда на самом деле брать видеопоток
>
> `Camera2` здесь использовать нельзя: пустой `cameraIdList` (`Available IDs: []`) является
> нормальным поведением этой прошивки. Камеры 360° подключены к отдельному AVM-контроллеру и
> обслуживаются сервисом
> `vendor.qti.automotive.qcarcam@1.0::IQcarCamera/default`, а не Android Camera HAL.
>
> Рабочая и проверенная на машине цепочка:
>
> ```text
> libais_hidl_client.so
>   → qcarcam_initialize()
>   → qcarcam_open(inputId)
>   → qcarcam_s_buffers(3 × ION DMA buffer, 1280×800 UYVY, stride 2560 bytes)
>   → qcarcam_start()
>   → qcarcam_get_frame()
>   → отобразить/обработать buffer[frame.idx]
>   → qcarcam_release_frame(frame.idx)
>   → qcarcam_stop() → qcarcam_close() → qcarcam_uninitialize()
> ```
>
> После установки и после каждой перезагрузки G636 один раз выполнить
> `tools/prepare_ion_access.sh <package>`: AVM принимает только ION DMA-буферы, а `/dev/ion`
> штатно закрыт для UID обычного APK. Для debug package — `com.stasao.gcam.dev`, для release —
> `com.stasao.gcam`.
>
> На G636 подтверждена точная раскладка: `0 — левая`, `1 — правая`, `2 — перед`,
> `3 — зад`. Технический вход `12` показывает то же направление, что `2`; вход `14`
> открывается, но обычно не отдаёт кадры вне активного режима заднего хода/RVC. В UI оставлены
> только четыре физические камеры. Формат — `UYVY 8-bit` (`0x07080102`), размер — `1280×800`, частота —
> `25 fps` (`14`: объявлено `30 fps`). Критически важно: поле `stride` в QCarCam ABI задаётся
> в **байтах**, поэтому для UYVY здесь это `1280 × 2 = 2560`, а не `1280`. Именно неверный
> stride давал `ais_start error 14`. Первый кадр приходит примерно через `240–252 ms`.
>
> Эталонная проверка без приложения:
>
> ```bash
> adb push tools/qcarcam_config_probe.xml /data/local/tmp/qcarcam_config_probe.xml
> adb shell su 0 /system/bin/qcarcam_hidl_test \
>   -config=/data/local/tmp/qcarcam_config_probe.xml \
>   -noDisplay -nonInteractive -nomenu -seconds=1
> ```
>
> Если в выводе есть `Success - First Frame`, весь путь AVM → HIDL → QCarCam работает.
> Готовый XML находится в [`tools/qcarcam_config_probe.xml`](tools/qcarcam_config_probe.xml).

## Что теперь делает приложение

Приложение состоит из одного экрана камер и использует нативный QCarCam-клиент. Диагностика,
монитор, запуск штатного AVM-окна, Camera2/Camera1/EVS fallback и экспорт логов удалены. Поток
запускается автоматически; снизу выбираются `Левая / Правая / Перед / Зад`.

Путь предпросмотра рассчитан на слабый CPU:

```text
QCarCam → 3 ION DMA buffers → 3 GPU staging buffers → UYVY texture → GLES3 shader → TextureView
```

- нет RAW-файлов и дискового I/O;
- нет `Bitmap`, JPEG/PNG и объектов на каждый кадр;
- JNI не копирует кадр в Kotlin/Java;
- передача в GPU идёт через тройную очередь Pixel Buffer Object без блокировки рендера;
- преобразование UYVY → RGB выполняет GPU;
- одновременно в обороте только три буфера, каждый кадр освобождается сразу после отправки GPU.

QCarCam на этой прошивке принимает буферы только из штатного non-secure ION heap
`0x02000000`. `/dev/ion` по умолчанию доступен группе `system`, поэтому после установки и после
каждой перезагрузки машины нужно один раз ограниченно выдать доступ UID приложения:

```bash
tools/prepare_ion_access.sh com.stasao.gcam.dev   # debug APK
# либо: tools/prepare_ion_access.sh com.stasao.gcam
```

Скрипт не делает `/dev/ion` общедоступным: владельцем группы становится только UID выбранного
пакета, режим остаётся `660`. Захват после этого работает внутри приложения без root-команд на
кадр. Для постоянной установки ту же настройку следует перенести в `ueventd.rc`/SELinux policy
целевой прошивки.

Одна передача 2 МБ UYVY в GLES на кадр пока остаётся. Прямой импорт ION через
`GL_EXT_memory_object_fd` проверен на машине, но драйвер Adreno отвергает сырой ION fd без
закрытой Qualcomm/gralloc metadata. Поэтому рабочий путь использует PBO: он снизил измеренную
нагрузку debug-сборки примерно с 34–35% до 25–30% одного CPU-ядра, сохранив 24–26 fps.

## Важное ограничение доступа

`libais_hidl_client.so` — закрытая системная библиотека. Обычное приложение не может загрузить
её напрямую из `/system` из-за linker namespace, поэтому в APK входят device-matched клиент,
QCarCam HIDL-интерфейс и замкнутый набор их platform-зависимостей из исследованной прошивки
G636 (`libais_log`, `libmmosal`, HIDL/Binder/base/utils и другие зависимости).

Эти файлы нельзя считать переносимыми на другую модель или версию прошивки: после обновления
головного устройства их нужно заново извлечь и проверить ABI. SELinux/HIDL ACL всё равно должны
разрешать клиенту соединение; на исследованной машине SELinux был `Permissive`.

Живой тест 4 октября 2026 года подтвердил весь путь `initialize → open → setBuffers → start →
getFrame → releaseFrame` для inputs `0`, `1`, `2`, `3`. Все четыре изображения выведены на экран
с частотой 24–26 fps; последовательное переключение, закрытие и повторный запуск прошли без
падения. Штатный `qcarcam_hidl_test` независимо подтвердил первый кадр input 0 за 252 ms.

## Сборка

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug
```

Проект собирает только `arm64-v8a`, что соответствует G636. Нужны Android SDK и NDK
`28.2.13676358`.

## Инструменты исследования

- [`QCARCAM_NEXT.md`](QCARCAM_NEXT.md) — подробные результаты исследования и ABI;
- [`tools/qcarcam_capture_input.sh`](tools/qcarcam_capture_input.sh) — однократный захват для
  проверки входа;
- [`tools/uyvy_to_png.py`](tools/uyvy_to_png.py) — офлайн-конвертер диагностического UYVY RAW;
Диагностические скрипты предназначены для исследования. Приложение не использует RAW/PNG в
рабочем видеопотоке.

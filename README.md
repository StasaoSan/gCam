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
>   → qcarcam_s_buffers(3 × DMA buffer, 1280×800 UYVY)
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
> `25 fps` (`14`: объявлено `30 fps`). Первый кадр приходит примерно через `240–252 ms`.
>
> Эталонная проверка без приложения:
>
> ```bash
> adb shell su 0 /system/bin/qcarcam_hidl_test \
>   -config=/data/local/tmp/qcarcam_config.xml -noDisplay -t=1
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
QCarCam → 3 переиспользуемых ION DMA buffers → UYVY texture → GLES3 shader → TextureView
```

- нет RAW-файлов и дискового I/O;
- нет `Bitmap`, JPEG/PNG и объектов на каждый кадр;
- JNI не копирует кадр в Kotlin/Java;
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

Одна передача 2 МБ UYVY в GLES на кадр пока остаётся. Следующий уровень оптимизации — прямой
EGLImage/import DMA-BUF, но он требует закрытых gralloc/mapper ABI конкретной прошивки и менее
надёжен, чем текущий NDK-совместимый путь.

## Важное ограничение доступа

`libais_hidl_client.so` — закрытая системная библиотека. Обычное приложение не может загрузить
её напрямую из `/system` из-за linker namespace, поэтому в APK входят device-matched клиент,
QCarCam HIDL-интерфейс и замкнутый набор их platform-зависимостей из исследованной прошивки
G636 (`libais_log`, `libmmosal`, HIDL/Binder/base/utils и другие зависимости).

Эти файлы нельзя считать переносимыми на другую модель или версию прошивки: после обновления
головного устройства их нужно заново извлечь и проверить ABI. SELinux/HIDL ACL всё равно должны
разрешать клиенту соединение; на исследованной машине SELinux был `Permissive`.

Последняя проверка до отключения устройства подтвердила загрузку упакованного HIDL-клиента и
успешное прохождение `initialize → open → setBuffers`; финальный прогон с исправленным ION heap
`0x02000000` ещё нужно выполнить при следующем подключении к машине.

## Сборка

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
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

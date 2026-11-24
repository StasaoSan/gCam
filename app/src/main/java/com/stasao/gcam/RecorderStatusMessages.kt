package com.stasao.gcam

/** Resource adapter for recorder/vendor status messages received as Russian text over IPC. */
internal object RecorderStatusMessages {
    private val exact = mapOf(
        "Левая" to R.string.gcam_left,
        "Правая" to R.string.gcam_right,
        "Перед" to R.string.gcam_front,
        "Зад" to R.string.gcam_rear,
        "Камеры и запись" to R.string.gcam_cameras_recording,
        "Записи" to R.string.gcam_recordings,
        "Автоматическая очистка архива" to R.string.gcam_automatic_archive_cleanup,
        "АРХИВ • ТРЕБУЕТ ВНИМАНИЯ" to R.string.gcam_archive_action_required,
        "Разрешите автоматическую очистку" to R.string.gcam_allow_automatic_cleanup,
        "Без доступа к старым файлам запись может остановиться, когда архив заполнится." to R.string.gcam_without_access_to_older_files_recording_may_stop_when_the_archive,
        "Проверяем доступ…" to R.string.gcam_checking_access,
        "Выдать доступ" to R.string.gcam_grant_access,
        "Данные о свободном месте пока недоступны. Сохранённый лимит остаётся выбранным; объём обновится после подключения накопителя и выдачи доступа." to R.string.gcam_free_space_information_is_unavailable_your_saved_limit_is_retaine,
        "Архив записей" to R.string.gcam_recording_archive,
        "Доступ выдан: старые записи удаляются автоматически по лимиту." to R.string.gcam_access_granted_old_recordings_are_deleted_automatically_to_stay_w,
        "Для удаления записей от предыдущих установок нужен однократный доступ ко всем файлам." to R.string.gcam_allow_all_files_access_once_to_delete_recordings_from_previous_in,
        "Android выдаёт доступ ко всему общему хранилищу. gCam удаляет по лимиту только свои записи в Movies/gCam выбранного накопителя; защищённые файлы сохраняются." to R.string.gcam_android_grants_access_to_all_shared_storage_gcam_limits_automatic,
        "Разрешить автоматическую очистку" to R.string.gcam_allow_automatic_cleanup_2,
        "Android запретил удаление старых записей. Выдайте gCam доступ ко всем файлам в блоке «Автоматическая очистка архива», затем запустите запись снова." to R.string.gcam_android_blocked_deletion_of_old_recordings_allow_all_files_access,
        "Недостаточно места в лимите архива или все старые файлы защищены" to R.string.gcam_not_enough_space_within_the_archive_limit_or_all_older_files_are,
        "Запись не запущена" to R.string.gcam_recording_did_not_start,
        "Не удалось" to R.string.gcam_failed,
        "Нет доступа" to R.string.gcam_no_access,
        "Обновление" to R.string.gcam_update,
        "Обновление ●" to R.string.gcam_update_2,
        "Системный" to R.string.gcam_system,
        "Русский" to R.string.gcam_russian,
        "Язык приложения" to R.string.gcam_app_language,
        "Проверка обновлений…" to R.string.gcam_checking_for_updates,
        "Это debug-сборка. Обновления доступны после установки release APK." to R.string.gcam_this_is_a_debug_build_updates_are_available_after_installing_a_re,
        "Установлена актуальная версия" to R.string.gcam_you_re_up_to_date,
        "Релиз не найден. Для обновлений репозиторий и Release должны быть публичными." to R.string.gcam_release_not_found_the_repository_and_release_must_be_public_to_up,
        "GitHub временно ограничил запросы. Попробуйте позже." to R.string.gcam_github_temporarily_rate_limited_requests_try_again_later,
        "Некорректный тег релиза" to R.string.gcam_invalid_release_tag,
        "Некорректный размер APK" to R.string.gcam_invalid_apk_size,
        "У APK нет контрольной суммы SHA-256" to R.string.gcam_apk_has_no_sha_256_checksum,
        "Некорректный адрес APK" to R.string.gcam_invalid_apk_url,
        "Размер APK превышает заявленный" to R.string.gcam_apk_exceeds_the_declared_size,
        "APK скачан не полностью" to R.string.gcam_apk_download_is_incomplete,
        "Контрольная сумма APK не совпадает" to R.string.gcam_apk_checksum_does_not_match,
        "Не удалось сохранить APK" to R.string.gcam_could_not_save_apk,
        "Скачанный файл не является APK" to R.string.gcam_downloaded_file_is_not_an_apk,
        "APK предназначен для другого приложения" to R.string.gcam_apk_belongs_to_another_app,
        "Версия APK не совпадает с релизом" to R.string.gcam_apk_version_does_not_match_the_release,
        "Номер сборки не новее установленного" to R.string.gcam_build_number_is_not_newer_than_the_installed_version,
        "Подпись APK не совпадает с установленным gCam" to R.string.gcam_apk_signature_does_not_match_the_installed_gcam,
        "Обновление gCam" to R.string.gcam_update_gcam,
        "USB/gCam и Movies/gCam" to R.string.gcam_usb_gcam_and_movies_gcam,
        "Проверить обновления" to R.string.gcam_check_for_updates,
        "Скачать обновление" to R.string.gcam_download_update,
        "Идёт запись. Перед установкой завершите её, чтобы MP4-файлы закрылись корректно." to R.string.gcam_recording_is_in_progress_stop_it_before_installing_so_mp4_files_a,
        "Установку подтверждает системный экран Android. При обновлении приложение будет перезапущено." to R.string.gcam_confirm_installation_in_the_android_system_dialog_the_app_will_re,
        "Высокая нагрузка" to R.string.gcam_high_workload,
        "Четыре камеры при 1280×800 могут перегрузить G636 и вызвать пропуски кадров. Рекомендуется 640×400, 15 FPS и 2 Мбит/с на камеру." to R.string.gcam_four_cameras_at_1280_800_may_overload_the_g636_and_drop_frames_re,
        "Всё равно записывать" to R.string.gcam_record_anyway,
        "Назад" to R.string.gcam_back,
        "Запись во внутреннюю память" to R.string.gcam_record_to_internal_storage,
        "Длительная запись четырёх камер создаёт постоянную нагрузку на внутренний накопитель и расходует его ресурс. По возможности используйте USB-накопитель." to R.string.gcam_long_four_camera_recordings_continuously_write_to_internal_storag,
        "Понимаю" to R.string.gcam_i_understand,
        "Отмена" to R.string.gcam_cancel,
        "Камеры показаны в плавающем окне поверх приложений." to R.string.gcam_cameras_are_shown_in_a_floating_window_above_other_apps,
        "Подготовка записи…" to R.string.gcam_preparing_to_record,
        "Останавливаю превью и запускаю запись…" to R.string.gcam_stopping_preview_and_starting_recording,
        "Почему нет изображения?" to R.string.gcam_why_is_there_no_image,
        "Штатное 360° работает отдельно. Проверьте живые боковые камеры в превью выше, затем путь до нужного экрана." to R.string.gcam_the_factory_360_view_is_separate_check_the_live_side_cameras_abov,
        "ZERO-COPY" to R.string.gcam_zero_copy,
        "PBO fallback" to R.string.gcam_pbo_fallback,
        "проверка…" to R.string.gcam_checking,
        "Камеры для записи" to R.string.gcam_cameras_to_record,
        "Рекомендуемые настройки" to R.string.gcam_recommended_settings,
        "Для 3–4 камер: 640×400 · 15 FPS · 2 Мбит/с на камеру" to R.string.gcam_for_3_4_cameras_640_400_15_fps_2_mbps_per_camera,
        "Для 1–2 камер: 960×600 · 20 FPS · 3 Мбит/с на камеру" to R.string.gcam_for_1_2_cameras_960_600_20_fps_3_mbps_per_camera,
        "Применить" to R.string.gcam_apply,
        "Разрешение записи" to R.string.gcam_recording_resolution,
        "Частота записи" to R.string.gcam_recording_frame_rate,
        "Битрейт H.264 на камеру" to R.string.gcam_h_264_bitrate_per_camera,
        "2 Мбит/с" to R.string.gcam_message_2_mbps,
        "3 Мбит/с" to R.string.gcam_message_3_mbps,
        "4 Мбит/с" to R.string.gcam_message_4_mbps,
        "6 Мбит/с" to R.string.gcam_message_6_mbps,
        "⚠ 4 камеры в максимальном разрешении: возможны сильная нагрузка, падение FPS и пропуски кадров." to R.string.gcam_four_cameras_at_maximum_resolution_may_cause_high_load_lower_fps,
        "Длительность сегмента" to R.string.gcam_segment_length,
        "1 мин" to R.string.gcam_message_1_min,
        "2 мин" to R.string.gcam_message_2_min,
        "3 мин" to R.string.gcam_message_3_min,
        "5 мин" to R.string.gcam_message_5_min,
        "Куда сохранять видео" to R.string.gcam_video_storage,
        "Внутренняя" to R.string.gcam_internal,
        "Флешка для записи" to R.string.gcam_recording_usb_drive,
        "Подключённые USB-накопители не найдены" to R.string.gcam_no_connected_usb_drives_found,
        "Обновить список флешек" to R.string.gcam_refresh_usb_drives,
        "Файлы будут в Movies/gCam на выбранной флешке. Системный выбор папки не требуется. При отключении USB запись не переключится на внутреннюю память." to R.string.gcam_files_will_be_saved_to_movies_gcam_on_the_selected_usb_drive_no_s,
        "Длительная запись изнашивает внутренний накопитель. Рекомендуется USB." to R.string.gcam_long_recordings_wear_out_internal_storage_usb_is_recommended,
        "Свободное место накопителя недоступно от системы. 512 ГБ не подставляются; лимит можно выставить после выбора доступной флешки." to R.string.gcam_the_system_did_not_report_free_space_no_assumed_512_gb_capacity_i,
        "Метки на видео" to R.string.gcam_video_overlays,
        "Дата и время" to R.string.gcam_date_and_time,
        "Поверх кадра, без перекодирования на CPU" to R.string.gcam_drawn_on_the_frame_without_cpu_re_encoding,
        "GPS-координаты" to R.string.gcam_gps_coordinates,
        "Показываются только при свежем определении местоположения" to R.string.gcam_shown_only_when_a_recent_location_is_available,
        "Скорость GPS" to R.string.gcam_gps_speed,
        "Не скорость автомобиля по CAN; зависит от GPS" to R.string.gcam_gps_speed_not_vehicle_can_speed,
        "Нет разрешения на точное местоположение: на видео будет GPS -- / -- KM/H" to R.string.gcam_precise_location_permission_is_missing_video_will_show_gps_km_h,
        "Автозапуск и индикатор" to R.string.gcam_auto_start_indicators,
        "Автозапуск gCam" to R.string.gcam_auto_start_gcam,
        "Поднимать фоновую связь с ANHUD после загрузки системы" to R.string.gcam_start_the_anhud_camera_link_after_boot,
        "Автозапуск записи" to R.string.gcam_auto_start_recording,
        "Сразу записывать выбранные выше камеры" to R.string.gcam_start_recording_the_selected_cameras_automatically,
        "Точка: gCam + gBlind" to R.string.gcam_dot_gcam_gblind,
        "Камера приборки и запись: красный — нет связи; синий — кадр был, запись не нужна; оранжевый — запись ожидается; зелёный — пишет" to R.string.gcam_cluster_camera_recording_red_no_link_blue_frame_received_recordin,
        "Точка: gCam + ANHUD" to R.string.gcam_dot_gcam_anhud,
        "Камера проекции и запись: красный — нет связи; синий — кадр был, запись не нужна; оранжевый — запись ожидается; зелёный — пишет" to R.string.gcam_hud_camera_recording_red_no_link_blue_frame_received_recording_no,
        "Точка: gCam + ANHUD + gBlind" to R.string.gcam_dot_gcam_anhud_gblind,
        "Разделена пополам: слева проекция, справа приборка; каждая половина учитывает запись" to R.string.gcam_split_in_half_hud_on_the_left_cluster_on_the_right_each_half_refl,
        "Поверх остальных окон" to R.string.gcam_above_other_apps,
        "Плавающее окно с четырьмя камерами; его можно перемещать" to R.string.gcam_movable_floating_view_with_all_four_cameras,
        "Нужно разрешение на показ поверх окон" to R.string.gcam_display_over_other_apps_permission_is_required,
        "Плавающая кнопка записи" to R.string.gcam_floating_record_button,
        "Монохромная кнопка записи" to R.string.gcam_monochrome_record_button,
        "Белые значки: треугольник — запись идёт; квадрат — готово; пауза — USB не готов; восклицательный знак — ошибка" to R.string.gcam_white_icons_triangle_recording_square_ready_pause_usb_not_ready_e,
        "Отдельная кнопка: красный — ошибка камер; синий — готово, запись выключена; оранжевый — USB не готов; зелёный — запись идёт. Нажмите для старта/остановки, перетащите для перемещения" to R.string.gcam_separate_button_red_camera_error_blue_ready_not_recording_orange,
        "Сбросить положение всех точек" to R.string.gcam_reset_all_dot_positions,
        "Вернёт три индикатора на исходные места, не меняя их включение." to R.string.gcam_moves_the_three_dots_back_to_their_default_positions_without_chan,
        "Положение плавающего элемента" to R.string.gcam_floating_element_position,
        "Обе" to R.string.gcam_both,
        "Запись" to R.string.gcam_record,
        "Положение можно задать даже для выключенного элемента; включённый перемещается сразу. Точки также можно перетаскивать пальцем." to R.string.gcam_you_can_set_the_position_of_a_disabled_element_enabled_elements_m,
        "● красный — нет связи или сигнала" to R.string.gcam_red_no_link_or_video,
        "● синий — сигнал получен, запись не запланирована" to R.string.gcam_blue_video_received_recording_not_requested,
        "● оранжевый — сигнал получен, но ожидаемая запись не идёт" to R.string.gcam_orange_video_received_expected_recording_is_not_running,
        "● зелёный — сигнал получен и запись идёт" to R.string.gcam_green_video_received_and_recording_is_running,
        "Открыть специальные возможности" to R.string.gcam_open_accessibility_settings,
        "Спецвозможности — резервный автозапуск для головных устройств, где обычный запуск после загрузки не срабатывает." to R.string.gcam_accessibility_is_a_fallback_auto_start_method_for_head_units_wher,
        "Доступ ANHUD к камерам" to R.string.gcam_anhud_camera_access,
        "ANHUD не установлен. После установки откройте эту страницу снова." to R.string.gcam_anhud_is_not_installed_reopen_this_page_after_installing_it,
        "Доступ разрешён автоматически: ANHUD подписан тем же ключом, что и gCam." to R.string.gcam_access_granted_automatically_anhud_and_gcam_have_the_same_signing,
        "Доступ разрешён для установленной версии ANHUD." to R.string.gcam_access_granted_to_the_installed_anhud_version,
        "Предупреждение: подпись ANHUD изменилась. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную." to R.string.gcam_warning_anhud_s_signature_changed_automatic_access_is_off_verify,
        "Предупреждение: ANHUD подписан неизвестным ключом. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную." to R.string.gcam_warning_anhud_has_an_unknown_signing_key_automatic_access_is_off,
        "Доступ ANHUD отозван" to R.string.gcam_anhud_access_revoked,
        "Доступ ANHUD разрешён" to R.string.gcam_anhud_access_granted,
        "ANHUD больше не установлен" to R.string.gcam_anhud_is_no_longer_installed,
        "Отозвать доступ" to R.string.gcam_revoke_access,
        "Разрешить ANHUD" to R.string.gcam_allow_anhud,
        "Разрешение привязано к подписи установленного ANHUD, а не к паролю или ключу внутри него." to R.string.gcam_permission_is_tied_to_the_installed_anhud_signature_not_a_passwor,
        "Доступ gBlind к камерам приборки" to R.string.gcam_gblind_access_to_cluster_cameras,
        "gBlind не установлен" to R.string.gcam_gblind_is_not_installed,
        "Доступ выдан автоматически: gBlind подписан тем же ключом, что и gCam." to R.string.gcam_access_granted_automatically_gblind_and_gcam_have_the_same_signin,
        "Доступ к камерам приборки разрешён" to R.string.gcam_cluster_camera_access_granted,
        "Предупреждение: подпись gBlind изменилась. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную." to R.string.gcam_warning_gblind_s_signature_changed_automatic_access_is_off_verify,
        "Предупреждение: gBlind подписан неизвестным ключом. Автодоступ отключён; проверьте сборку и подтвердите доступ вручную." to R.string.gcam_warning_gblind_has_an_unknown_signing_key_automatic_access_is_off,
        "Разрешить gBlind" to R.string.gcam_allow_gblind,
        "Остановить запись" to R.string.gcam_stop_recording,
        "Начать запись" to R.string.gcam_start_recording,
        "выключен" to R.string.gcam_off,
        "нет свежего статуса" to R.string.gcam_no_recent_status,
        "связь и кадры есть" to R.string.gcam_connected_video_received,
        "связь есть, кадры ожидаются" to R.string.gcam_connected_waiting_for_video,
        "нет связи" to R.string.gcam_no_link,
        "запись идёт" to R.string.gcam_recording,
        "запись не идёт · выбрана внутренняя память" to R.string.gcam_not_recording_internal_storage_selected,
        "запись не идёт · USB готов" to R.string.gcam_not_recording_usb_ready,
        "Поделиться диагностикой записи" to R.string.gcam_share_recording_diagnostics,
        "Журнал сохраняется после перезагрузки и автоматически ограничен 2 МБ. Для разбора зависания отправьте его после поездки." to R.string.gcam_the_log_survives_reboots_and_is_capped_at_2_mb_share_it_after_a_t,
        "Подключение…" to R.string.gcam_connecting,
        "Предупреждение о записи" to R.string.gcam_recording_warning,
        "Запись продолжена." to R.string.gcam_recording_resumed,
        "Запись сейчас не идёт или восстанавливается." to R.string.gcam_recording_is_stopped_or_recovering,
        "Поделиться диагностикой" to R.string.gcam_share_diagnostics,
        "Выбрать файлы" to R.string.gcam_select_files,
        "Выбрать все" to R.string.gcam_select_all,
        "Снять выбор" to R.string.gcam_deselect_all,
        "Удаление…" to R.string.gcam_deleting,
        "Чтобы удалить файлы, сначала остановите запись." to R.string.gcam_stop_recording_before_deleting_files,
        "Записей пока нет" to R.string.gcam_no_recordings_yet,
        "Смотреть" to R.string.gcam_watch,
        "Скрыть файлы" to R.string.gcam_hide_files,
        "Показать файлы" to R.string.gcam_show_files,
        "внутренняя" to R.string.gcam_internal_2,
        " · защищено" to R.string.gcam_protected,
        "Снять защиту" to R.string.gcam_unprotect,
        "Защитить" to R.string.gcam_protect,
        "Удалить" to R.string.gcam_delete,
        "Удалить выбранные файлы?" to R.string.gcam_delete_selected_files,
        "Удаление завершено" to R.string.gcam_deletion_complete,
        "ОК" to R.string.gcam_ok,
        "Закрыть" to R.string.gcam_close,
        "Пауза" to R.string.gcam_pause,
        "Пуск" to R.string.gcam_play,
        "Регистратор остановлен" to R.string.gcam_recorder_stopped,
        "Превью закрыто" to R.string.gcam_preview_closed,
        "Подключение к QCarCam…" to R.string.gcam_connecting_to_qcarcam,
        "Ошибка подключения" to R.string.gcam_connection_error,
        "USB не готов" to R.string.gcam_usb_not_ready,
        "Ошибка USB" to R.string.gcam_usb_error,
        "Запуск камер…" to R.string.gcam_starting_cameras,
        "Проверяю выбранный USB-накопитель…" to R.string.gcam_checking_the_selected_usb_drive,
        "Освобождаю место для записи…" to R.string.gcam_freeing_space_for_recording,
        "Проверяю стабильность USB" to R.string.gcam_checking_usb_stability,
        "Камеры не запустились" to R.string.gcam_cameras_failed_to_start,
        "Сервис камер отключился" to R.string.gcam_camera_service_disconnected,
        "Сервис камер недоступен" to R.string.gcam_camera_service_unavailable,
        "Не удалось запустить сервис камер" to R.string.gcam_could_not_start_the_camera_service,
        "Некорректный HUD slot" to R.string.gcam_invalid_hud_slot,
        "Нет кадров после перезапуска камер" to R.string.gcam_no_frames_after_restarting_cameras,
        "Нет ответа регистратора" to R.string.gcam_recorder_is_not_responding,
        "Проверка USB…" to R.string.gcam_checking_usb,
        "Ошибка записи на USB" to R.string.gcam_usb_recording_error,
        "USB не подключён" to R.string.gcam_usb_not_connected,
        "Выберите подключённый USB-накопитель" to R.string.gcam_select_a_connected_usb_drive,
        "Не удалось создать файл в Movies/gCam на выбранном накопителе" to R.string.gcam_could_not_create_a_file_in_movies_gcam_on_the_selected_drive,
        "Не удалось опубликовать завершённый MP4" to R.string.gcam_could_not_publish_the_finalized_mp4,
        "Неверная USB-папка" to R.string.gcam_invalid_usb_folder,
        "Выберите хотя бы одну камеру" to R.string.gcam_select_at_least_one_camera,
        "Запись не запущена: недостаточно места в лимите архива или все старые файлы защищены" to R.string.gcam_recording_did_not_start_archive_limit_has_insufficient_space_or_a,
        "Мало свободной ОЗУ; ожидаю освобождения памяти" to R.string.gcam_low_available_ram_waiting_for_memory,
        "Ожидаю свободную память для восстановления записи…" to R.string.gcam_waiting_for_memory_to_recover_recording,
        "Запись всех камер восстановлена" to R.string.gcam_recording_on_all_cameras_recovered,
        "Обнаружен сбой записи и перезапуск камер. Проверьте видео и отправьте логи разработчику." to R.string.gcam_recording_failed_and_the_cameras_restarted_check_the_videos_and_s,
        "Частые сбои записи: больше 5 перезапусков за 15 минут. Проверьте видео и отправьте логи разработчику." to R.string.gcam_frequent_recording_failures_over_5_restarts_in_15_minutes_check_t,
        "USB-накопитель недоступен. Запись возобновится после его подключения." to R.string.gcam_usb_drive_unavailable_recording_will_resume_when_it_reconnects,
        "нет связи или кадра" to R.string.gcam_no_link_or_video,
        "связь есть, запись идёт" to R.string.gcam_connected_recording,
        "связь есть, запись ожидается" to R.string.gcam_connected_recording_expected,
        "связь есть, запись не запланирована" to R.string.gcam_connected_recording_not_requested
    )

    fun translate(context: android.content.Context, text: String): String {
        val translated = exact[text]?.let { GCamLanguage.string(context, it) } ?: dynamic(context, text) ?: text
        return translated.replace(" ГБ", " GB").replace(" МБ", " MB").replace(" КБ", " KB")
            .replace(" · макс. пауза ", " · max gap ").replace(" · пауза ", " · gap ")
            .replace(" мс", " ms")
    }

    private fun dynamic(context: android.content.Context, text: String): String? {
        fun tail(prefix: String) = text.removePrefix(prefix)
        return when {
            text.startsWith("На этом ГУ нет окна выдачи доступа. Разрешение можно выдать через ADB: ") ->
                GCamLanguage.string(context, R.string.gcam_this_head_unit_has_no_access_settings_screen_grant_access_through_2, tail("На этом ГУ нет окна выдачи доступа. Разрешение можно выдать через ADB: "))
            text.startsWith("Камеры 360° · v") -> GCamLanguage.string(context, R.string.gcam_message_360_cameras_v_1_s_2, tail("Камеры 360° · v"))
            text.startsWith("Доступна версия ") -> GCamLanguage.string(context, R.string.gcam_version_1_s_is_available_2, tail("Доступна версия "))
            text.startsWith("Ошибка проверки: ") -> GCamLanguage.string(context, R.string.gcam_update_check_failed_1_s_2, translate(context, tail("Ошибка проверки: ")))
            text.startsWith("Установлена версия ") -> GCamLanguage.string(context, R.string.gcam_installed_version_1_s_2, tail("Установлена версия "))
            text.startsWith("Что нового в ") -> GCamLanguage.string(context, R.string.gcam_what_s_new_in_1_s_2, tail("Что нового в "))
            text.startsWith("Загрузка ") -> GCamLanguage.string(context, R.string.gcam_downloading_1_s_2, tail("Загрузка "))
            text.startsWith("APK проверен. Можно установить ") -> GCamLanguage.string(context, R.string.gcam_apk_verified_you_can_install_1_s_2, tail("APK проверен. Можно установить "))
            text.startsWith("Ошибка загрузки: ") -> GCamLanguage.string(context, R.string.gcam_download_failed_1_s_2, translate(context, tail("Ошибка загрузки: ")))
            text.startsWith("Установить ") -> GCamLanguage.string(context, R.string.gcam_install_1_s_2, tail("Установить "))
            text.startsWith("Разрешите установку из gCam") -> GCamLanguage.string(context, R.string.gcam_allow_installs_from_gcam_then_tap_the_button_again_2)
            text.startsWith("Не удалось открыть установщик: ") -> GCamLanguage.string(context, R.string.gcam_could_not_open_installer_1_s_2, tail("Не удалось открыть установщик: "))
            text.startsWith("GitHub вернул HTTP ") -> GCamLanguage.string(context, R.string.gcam_github_returned_http_1_s, tail("GitHub вернул HTTP "))
            text.startsWith("В релизе ") -> tail("В релизе ").replace(" нет файла ", GCamLanguage.string(context, R.string.gcam_does_not_contain_file)).let { GCamLanguage.string(context, R.string.gcam_release_1_s, it) }
            text.startsWith("Не удалось скачать APK: ") -> GCamLanguage.string(context, R.string.gcam_could_not_download_apk_1_s, tail("Не удалось скачать APK: "))
            text.startsWith("Не удалось запустить запись: ") -> GCamLanguage.string(context, R.string.gcam_could_not_start_recording_1_s_2, translate(context, tail("Не удалось запустить запись: ")))
            text.startsWith("Проекция: ") -> GCamLanguage.string(context, R.string.gcam_hud_1_s_2, translate(context, tail("Проекция: ")))
            text.startsWith("Приборка: ") -> GCamLanguage.string(context, R.string.gcam_cluster_1_s_2, translate(context, tail("Приборка: ")))
            text.startsWith("Ошибка воспроизведения ") -> GCamLanguage.string(context, R.string.gcam_playback_error_1_s, tail("Ошибка воспроизведения "))
            text.startsWith("Размер интерфейса: ") -> GCamLanguage.string(context, R.string.gcam_interface_scale_1_s_2, tail("Размер интерфейса: "))
            text.startsWith("Размер кнопки записи: ") -> GCamLanguage.string(context, R.string.gcam_record_button_size_1_s, tail("Размер кнопки записи: "))
            text.startsWith("Горизонталь: ") -> GCamLanguage.string(context, R.string.gcam_horizontal_1_s_2, tail("Горизонталь: "))
            text.startsWith("Вертикаль: ") -> GCamLanguage.string(context, R.string.gcam_vertical_1_s_2, tail("Вертикаль: "))
            text.startsWith("Запись на: ") -> GCamLanguage.string(context, R.string.gcam_saving_to_1_s, tail("Запись на: "))
            text.startsWith("Левая:") -> GCamLanguage.string(context, R.string.gcam_left_1_s, translate(context, tail("Левая:")))
            text.startsWith("Правая:") -> GCamLanguage.string(context, R.string.gcam_right_1_s, translate(context, tail("Правая:")))
            text.startsWith("Перед:") -> GCamLanguage.string(context, R.string.gcam_front_1_s, translate(context, tail("Перед:")))
            text.startsWith("Зад:") -> GCamLanguage.string(context, R.string.gcam_rear_1_s, translate(context, tail("Зад:")))
            text.startsWith("Ранее выбранная флешка ") -> GCamLanguage.string(context, R.string.gcam_previously_selected_usb_drive_1_s_is_not_connected_2, tail("Ранее выбранная флешка ").removeSuffix(" сейчас не подключена"))
            text.startsWith("✓ ВЫБРАНА · ") -> GCamLanguage.string(context, R.string.gcam_selected_1_s, tail("✓ ВЫБРАНА · "))
            text.startsWith("Выбрать · ") -> GCamLanguage.string(context, R.string.gcam_select_1_s, tail("Выбрать · "))
            text.startsWith("Лимит архива: ") -> tail("Лимит архива: ").replace(" из доступных ", GCamLanguage.string(context, R.string.gcam_of_available)).replace(" ГБ", " GB").let { GCamLanguage.string(context, R.string.gcam_archive_limit_1_s, it) }
            text.startsWith("Нет разрешения на точное местоположение") -> GCamLanguage.string(context, R.string.gcam_precise_location_permission_is_missing_video_will_show_gps_km_h_2)
            text.startsWith("Выбранный накопитель: занято ") -> tail("Выбранный накопитель: занято ").replace(" из ", " of ").replace(" · свободно ", GCamLanguage.string(context, R.string.gcam_used)).let { GCamLanguage.string(context, R.string.gcam_selected_drive_1_s_free, it) }
            text.startsWith("Выбранный накопитель: свободно ") -> tail("Выбранный накопитель: свободно ").replace("; полный объём система не сообщает", GCamLanguage.string(context, R.string.gcam_total_capacity_was_not_reported)).let { GCamLanguage.string(context, R.string.gcam_selected_drive_1_s_free, it) }
            text.startsWith("Записи gCam: ") -> tail("Записи gCam: ").replace(" · максимум = свободно + записи − резерв 2 ГБ", GCamLanguage.string(context, R.string.gcam_maximum_free_space_recordings_2_gb_reserve)).let { GCamLanguage.string(context, R.string.gcam_gcam_recordings_1_s, it) }
            text.startsWith("Ориентировочно ") -> tail("Ориентировочно ").replace(" ч записи", GCamLanguage.string(context, R.string.gcam_h_of_recording)).let { GCamLanguage.string(context, R.string.gcam_approximately_1_s, it) }
            text.startsWith(GCamLanguage.string(context, R.string.gcam_anhud)) -> GCamLanguage.string(context, R.string.gcam_anhud_1_s, translate(context, tail("ANHUD: ")))
            text.startsWith(GCamLanguage.string(context, R.string.gcam_gblind)) -> GCamLanguage.string(context, R.string.gcam_gblind_1_s, translate(context, tail("gBlind: ")))
            text.startsWith("Регистратор: ") -> GCamLanguage.string(context, R.string.gcam_recorder_1_s_2, translate(context, tail("Регистратор: ")))
            text.startsWith("ANHUD не установлен.") -> GCamLanguage.string(context, R.string.gcam_anhud_is_not_installed_reopen_this_page_after_installing_it_2)
            text.startsWith("Не удалось изменить доступ: ") -> GCamLanguage.string(context, R.string.gcam_could_not_change_access_1_s_2, tail("Не удалось изменить доступ: "))
            text.startsWith("Не удалось изменить защиту: ") -> GCamLanguage.string(context, R.string.gcam_could_not_change_file_protection_1_s_2, tail("Не удалось изменить защиту: "))
            text.startsWith("ошибка: ") -> GCamLanguage.string(context, R.string.gcam_error_1_s_2, tail("ошибка: "))
            text.startsWith("USB не готов: ") -> GCamLanguage.string(context, R.string.gcam_usb_not_ready_1_s_2, translate(context, tail("USB не готов: ")))
            text.startsWith("Запись остановлена: ") -> GCamLanguage.string(context, R.string.gcam_recording_stopped_1_s, translate(context, tail("Запись остановлена: ")))
            text.startsWith("Запись остановлена") -> GCamLanguage.string(context, R.string.gcam_recording_stopped_1_s_2, translate(context, tail("Запись остановлена")))
            text.startsWith("Запись: ") -> GCamLanguage.string(context, R.string.gcam_recording_1_s, tail("Запись: ").replace(" камер", " cameras"))
            text.startsWith("Запись с ошибками: ") -> GCamLanguage.string(context, R.string.gcam_recording_errors_1_s, tail("Запись с ошибками: ").replace(" камер", " cameras"))
            text.startsWith("Запись не запущена: ") -> GCamLanguage.string(context, R.string.gcam_recording_did_not_start_1_s, translate(context, tail("Запись не запущена: ")))
            text.startsWith("Проверка USB: ") -> GCamLanguage.string(context, R.string.gcam_usb_check_1_s, translate(context, tail("Проверка USB: ")))
            text.startsWith("Ожидаю выбранный USB: ") -> GCamLanguage.string(context, R.string.gcam_waiting_for_selected_usb_1_s, translate(context, tail("Ожидаю выбранный USB: ")))
            text.startsWith("Выберите хотя бы одну камеру") -> GCamLanguage.string(context, R.string.gcam_select_at_least_one_camera_2)
            text.startsWith("USB не подтвердил стабильную запись: ") -> GCamLanguage.string(context, R.string.gcam_usb_did_not_sustain_stable_writing_1_s, translate(context, tail("USB не подтвердил стабильную запись: ")))
            text.startsWith("Не запустились камеры: ") -> GCamLanguage.string(context, R.string.gcam_cameras_failed_to_start_1_s, tail("Не запустились камеры: "))
            text.startsWith("Нет записанных кадров 30 секунд: камера ") -> GCamLanguage.string(context, R.string.gcam_no_encoded_frames_for_30_seconds_camera_1_s, tail("Нет записанных кадров 30 секунд: камера "))
            text.startsWith("Кодер камеры ") -> GCamLanguage.string(context, R.string.gcam_camera_encoder_1_s, tail("Кодер камеры ").replace(" остановился", " stopped"))
            text.startsWith("Запись всех камер восстановлена после сбоя: ") -> GCamLanguage.string(context, R.string.gcam_all_camera_recordings_recovered_after_a_failure_1_s, tail("Запись всех камер восстановлена после сбоя: "))
            text.startsWith("Нет кадров после перезапуска камер") -> GCamLanguage.string(context, R.string.gcam_no_frames_after_restarting_cameras_2)
            text.startsWith("Сбой записи: ") -> tail("Сбой записи: ").replace(". Перезапуск камер через ", GCamLanguage.string(context, R.string.gcam_restarting_cameras_in)).replace(" с (", " s (").replace(" за 15 мин)", GCamLanguage.string(context, R.string.gcam_in_15_min)).replace(". Частые сбои — отправьте диагностику", GCamLanguage.string(context, R.string.gcam_frequent_failures_share_diagnostics)).let { GCamLanguage.string(context, R.string.gcam_recording_failure_1_s, it) }
            text.startsWith("USB-накопитель ") && text.endsWith(" не доступен через медиахранилище Android") ->
                GCamLanguage.string(context, R.string.gcam_usb_drive_1_s_is_not_available_through_android_mediastore, text.removePrefix("USB-накопитель ").removeSuffix(" не доступен через медиахранилище Android"))
            text.startsWith("Не удалось создать файл в Movies/gCam") -> GCamLanguage.string(context, R.string.gcam_could_not_create_a_file_in_movies_gcam_on_the_selected_drive_2)
            text.startsWith("Не удалось опубликовать завершённый MP4") -> GCamLanguage.string(context, R.string.gcam_could_not_publish_the_finalized_mp4_2)
            text.startsWith("Не удалось создать папку ") -> GCamLanguage.string(context, R.string.gcam_could_not_create_folder_1_s, tail("Не удалось создать папку ").replace(" на USB", " on USB"))
            text.startsWith("Неверная USB-папка") -> GCamLanguage.string(context, R.string.gcam_invalid_usb_folder_2)
            text.startsWith("Откройте gCam и подтвердите доступ ") -> GCamLanguage.string(context, R.string.gcam_open_gcam_and_approve_1_s, tail("Откройте gCam и подтвердите доступ ").replace(" к камерам", " camera access"))
            text.startsWith("Доступ к камерам gCam разрешён только ANHUD или gBlind") -> GCamLanguage.string(context, R.string.gcam_only_anhud_or_gblind_may_access_gcam_cameras)
            text.startsWith("gBlind не может управлять проекцией") -> GCamLanguage.string(context, R.string.gcam_gblind_cannot_control_the_hud)
            text.startsWith("Камера ") -> GCamLanguage.string(context, R.string.gcam_camera_1_s_2, tail("Камера "))
            text.startsWith("Остановить запись; ") -> GCamLanguage.string(context, R.string.gcam_stop_recording_1_s, translate(context, tail("Остановить запись; ")))
            text.startsWith("Начать запись; ") -> GCamLanguage.string(context, R.string.gcam_start_recording_1_s, translate(context, tail("Начать запись; ")))
            text == "камеры и внутренняя память готовы" -> GCamLanguage.string(context, R.string.gcam_cameras_and_internal_storage_are_ready)
            text == "камеры и USB готовы" -> GCamLanguage.string(context, R.string.gcam_cameras_and_usb_are_ready)
            text == "нет подтверждённого кадра с камер" -> GCamLanguage.string(context, R.string.gcam_no_confirmed_camera_frame)
            text == "Нет ответа регистратора" -> GCamLanguage.string(context, R.string.gcam_recorder_is_not_responding_2)
            text.startsWith("Удалить выбранные (") -> GCamLanguage.string(context, R.string.gcam_delete_selected_1_s_2, tail("Удалить выбранные ("))
            text.startsWith("Удалить выбранные файлы?") -> GCamLanguage.string(context, R.string.gcam_delete_selected_files_2)
            text.startsWith("USB/gCam и Movies/gCam · ") -> tail("USB/gCam и Movies/gCam · ").replace(" записей / ", GCamLanguage.string(context, R.string.gcam_recordings_2)).replace(" файлов · ", GCamLanguage.string(context, R.string.gcam_files)).let { GCamLanguage.string(context, R.string.gcam_usb_gcam_and_movies_gcam_1_s, it) }
            text.contains(" фрагм. · ") -> text.replace(" фрагм. · ", GCamLanguage.string(context, R.string.gcam_segments))
            text.contains(" · пауза ") -> text.replace(" · пауза ", GCamLanguage.string(context, R.string.gcam_gap)).replace(" мс", " ms")
            text.contains(" · макс. пауза ") -> text.replace(" · макс. пауза ", GCamLanguage.string(context, R.string.gcam_max_gap)).replace(" мс", " ms")
            text.contains(" · внутренняя") -> text.replace(" · внутренняя", GCamLanguage.string(context, R.string.gcam_internal_3))
            text.contains(" · защищено") -> text.replace(" · защищено", GCamLanguage.string(context, R.string.gcam_protected_2))
            text.startsWith("Удалено ") -> tail("Удалено ").replace(" из ", " of ").replace(" файлов", GCamLanguage.string(context, R.string.gcam_files_2)).replace(". Не удалось удалить ", GCamLanguage.string(context, R.string.gcam_failed_to_delete)).replace("; они остались выделены.", GCamLanguage.string(context, R.string.gcam_they_remain_selected)).let { GCamLanguage.string(context, R.string.gcam_deleted_1_s, it) }
            text.startsWith("Будут безвозвратно удалены ") -> tail("Будут безвозвратно удалены ").replace(" файлов ", GCamLanguage.string(context, R.string.gcam_files_3)).replace(" из gCam.", GCamLanguage.string(context, R.string.gcam_from_gcam)).replace(" В выбор входят защищённые файлы: ", GCamLanguage.string(context, R.string.gcam_protected_files_in_selection)).let { GCamLanguage.string(context, R.string.gcam_permanently_delete_1_s, it) }
            text.startsWith(" В выбор входят защищённые файлы: ") -> GCamLanguage.string(context, R.string.gcam_protected_files_in_selection_1_s_2, tail(" В выбор входят защищённые файлы: "))
            text.startsWith("Ошибка записи на USB") -> GCamLanguage.string(context, R.string.gcam_usb_recording_error_2)
            text.startsWith("USB-накопитель недоступен.") -> GCamLanguage.string(context, R.string.gcam_usb_drive_unavailable_recording_will_resume_when_it_reconnects_2)
            text.endsWith(" КБ") -> GCamLanguage.string(context, R.string.gcam_message_1_s_kb_2, text.removeSuffix(" КБ"))
            text.endsWith(" МБ") -> GCamLanguage.string(context, R.string.gcam_message_1_s_mb, text.removeSuffix(" МБ"))
            text.endsWith(" ГБ") -> GCamLanguage.string(context, R.string.gcam_message_1_s_gb_2, text.removeSuffix(" ГБ"))
            else -> null
        }
    }
}

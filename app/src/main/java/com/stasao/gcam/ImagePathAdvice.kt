package com.stasao.gcam

/** Explains the first observable break between gCam and another display app. */
internal object ImagePathAdvice {
    data class State(
        val installed: Boolean,
        val access: Boolean,
        val reportFresh: Boolean,
        val enabled: Boolean,
        val connected: Boolean,
        val video: Boolean,
        val error: String?,
    )

    fun explain(app: String, state: State, english: Boolean = false): String = when {
        english && !state.installed -> "$app is not installed. Install and open it."
        english && !state.access -> "$app has no gCam access. Approve it in the access section below."
        english && !state.reportFresh -> "$app is not reporting camera activity. Open $app and enable camera display in its settings."
        english && !state.enabled -> "Camera display is disabled in $app. Enable it in that app's settings."
        english && !state.error.isNullOrBlank() -> "$app reports an error: ${EnglishUi.translate(state.error.orEmpty())}"
        english && !state.connected -> "$app is running, but is not connected to the camera or display. Open its status screen."
        english && !state.video -> "$app is connected, but no frames have been confirmed. Use a turn signal or open its preview."
        english -> "$app receives frames. If nothing appears on the display, check its display mode and window position."
        !state.installed -> "$app не установлен. Установите приложение и откройте его."
        !state.access -> "Доступ $app к gCam не разрешён. Подтвердите его в разделе доступа ниже."
        !state.reportFresh -> "$app не сообщает о работе камер. Откройте $app и включите вывод камер в его настройках."
        !state.enabled -> "Вывод камер в $app сейчас выключен. Включите его в настройках приложения."
        !state.error.isNullOrBlank() -> "$app сообщает об ошибке: ${state.error}"
        !state.connected -> "$app запущен, но связь с камерой или экраном вывода не установлена. Откройте его экран состояния."
        !state.video -> "$app подключён, но кадры ещё не подтверждены. Включите поворотник или откройте превью в приложении."
        else -> "$app получает кадры. Если изображения нет на экране вывода, проверьте режим показа и положение окна в $app."
    }
}

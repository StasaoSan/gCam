package com.stasao.gcam

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishUiTest {
    @Test fun translatesStaticUiAndDynamicStatus() {
        assertEquals("Cameras & recording", EnglishUi.translate("Камеры и запись"))
        assertEquals("Archive limit: 20 GB of available 40 GB",
            EnglishUi.translate("Лимит архива: 20 ГБ из доступных 40 ГБ"))
        assertEquals("Recorder: not recording · USB ready",
            EnglishUi.translate("Регистратор: запись не идёт · USB готов"))
        assertEquals("Start recording; USB not ready",
            EnglishUi.translate("Начать запись; USB не готов"))
        assertEquals("Recording: 4/4 cameras", EnglishUi.translate("Запись: 4/4 камер"))
        assertEquals("USB not ready: Invalid USB folder",
            EnglishUi.translate("USB не готов: Неверная USB-папка"))
        assertEquals("Recording stopped: Android blocked deletion of old recordings. Allow all-files access in Automatic archive cleanup, then start recording again.",
            EnglishUi.translate("Запись остановлена: ${ArchiveAccessRequiredException.MESSAGE}"))
    }

    @Test fun leavesUnknownExternalTextUntouched() {
        assertEquals("vendor qcarcam timeout", EnglishUi.translate("vendor qcarcam timeout"))
    }
}

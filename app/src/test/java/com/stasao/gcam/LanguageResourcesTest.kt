package com.stasao.gcam

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class LanguageResourcesTest {
    private fun strings(locale: String): Map<String, String> {
        val file = File("src/main/res/$locale/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val values = (0 until nodes.length).map { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        assertEquals("Duplicate string resource in $locale", values.size, values.toMap().size)
        return values.toMap()
    }

    @Test fun translationsCoverTheSameKeysAndFormattingArguments() {
        val english = strings("values-en")
        val russian = strings("values-ru")
        val fallback = strings("values")
        assertEquals(english.keys, russian.keys)
        assertEquals(english, fallback)
        val arguments = Regex("%[0-9]+[$][a-zA-Z]")
        english.forEach { (key, text) ->
            assertFalse("Russian text in English resource $key", Regex("[А-Яа-яЁё]").containsMatchIn(text))
            assertEquals("Formatting arguments differ for $key",
                arguments.findAll(text).map { it.value }.toList().sorted(),
                arguments.findAll(russian.getValue(key)).map { it.value }.toList().sorted())
        }
    }
}

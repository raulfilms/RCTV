package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextScanTest {

    private fun regexValue(text: String, marker: String): String? =
        Regex(Regex.escape(marker) + "([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)

    @Test
    fun sameAsTheRegexItReplaces() {
        val marker = "\"INNERTUBE_API_KEY\":\""
        val pages = listOf(
            "", "no key here", "{\"INNERTUBE_API_KEY\":\"AIzaSy123\",\"x\":1}",
            "{\"INNERTUBE_API_KEY\":\"\",\"INNERTUBE_API_KEY\":\"second\"}",
            "{\"INNERTUBE_API_KEY\":\"unterminated", "\"INNERTUBE_API_KEY\":\"a\"",
            "x\"INNERTUBE_API_KEY\":\"multi\nline\" tail", "\"INNERTUBE_API_KEY\":\"\"\"INNERTUBE_API_KEY\":\"z\"",
            "\"INNERTUBE_API_KEY\":\"" + "k".repeat(5000) + "\" " + "filler ".repeat(20000)
        )
        pages.forEach { page -> assertEquals(page.take(60), regexValue(page, marker), TextScan.quotedValueAfter(page, marker)) }
    }
}

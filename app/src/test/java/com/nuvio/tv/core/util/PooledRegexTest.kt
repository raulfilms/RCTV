package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PooledRegexTest {

    private val cases = listOf(
        "\\b(2160p|4k|uhd)\\b" to setOf(RegexOption.IGNORE_CASE),
        "(?<![0-9])(19\\d{2}|20\\d{2})(?![0-9])" to emptySet(),
        "[._]+" to emptySet(),
        "\\s+" to emptySet(),
        "^\\s*\\[[^\\]]{1,40}\\]\\s*" to emptySet(),
        "(\\d+(?:[.,]\\d+)?)\\s?(GB|GiB|MB|MiB|TB)\\b" to setOf(RegexOption.IGNORE_CASE),
        "[\\x{1F1E6}-\\x{1F1FF}]{2}" to emptySet(),
        "x*" to emptySet(),
        "(a)|(b)" to emptySet(),
        "\"INNERTUBE_API_KEY\":\"([^\"]+)\"" to emptySet()
    )
    private val inputs = listOf(
        "", "x", "Movie.2024.2160p.UHD.BluRay.x265", "a b  c\t d", "[TGx] Name (1999) 4K", "Size 58.3 GB · 1,4 gb",
        "🇺🇸🇲🇽 audio 🇪🇸", "xxaxxbxx", "ab", "{\"INNERTUBE_API_KEY\":\"\",\"INNERTUBE_API_KEY\":\"k123\"}", "1899 2024 20245 1999x"
    )

    @Test
    fun sameAnswersAsRegex() {
        for ((pattern, options) in cases) {
            val regex = Regex(pattern, options)
            val pooled = PooledRegex(pattern, options)
            for (input in inputs) {
                val label = "$pattern on \"$input\""
                assertEquals(label, regex.containsMatchIn(input), pooled.containsMatchIn(input))
                assertEquals(label, regex.matches(input), pooled.matches(input))
                assertEquals(label, regex.find(input)?.let { Triple(it.value, it.range, it.groupValues) },
                    pooled.find(input)?.let { Triple(it.value, it.range, it.groupValues) })
                assertEquals(label, regex.findAll(input).map { Triple(it.value, it.range, it.groupValues) }.toList(),
                    pooled.findAll(input).map { Triple(it.value, it.range, it.groupValues) }.toList())
                assertEquals(label, regex.replace(input, " "), pooled.replace(input, " "))
                assertEquals(label, regex.replace(input, "<$0>"), pooled.replace(input, "<$0>"))
                assertEquals(label, regex.replace(input) { "[" + it.value + "]" }, pooled.replace(input) { "[" + it.value + "]" })
                if (input.isNotEmpty()) {
                    assertEquals(label, regex.find(input, 1)?.range, pooled.find(input, 1)?.range)
                }
            }
        }
    }

    @Test
    fun aResultNeverChangesAfterTheMatcherIsReused() {
        val pooled = PooledRegex("(\\d+)")
        val first = pooled.find("abc 123")!!
        pooled.find("999 zz")
        assertEquals("123", first.value)
        assertEquals(listOf("123", "123"), first.groupValues)
    }

    @Test
    fun manyThreadsAtOnce() {
        val pooled = PooledRegex("(\\d+)x(\\d+)")
        val pool = Executors.newFixedThreadPool(8)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        repeat(2_000) { i ->
            pool.execute {
                val match = pooled.find("size ${i}x${i + 1} px")
                if (match?.groupValues != listOf("${i}x${i + 1}", "$i", "${i + 1}")) failures.incrementAndGet()
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        assertEquals(0, failures.get())
    }
}

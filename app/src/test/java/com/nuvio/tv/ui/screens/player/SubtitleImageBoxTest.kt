package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleImageBoxTest {

    /** A picture drawn from text: '#' a letter's pixel, '.' nothing. */
    private fun lines(vararg rows: String): List<SubtitleImageBox.Line> {
        val width = rows.maxOf { it.length }
        return SubtitleImageBox.linesOf(width, rows.size) { y, row ->
            for (x in 0 until width) row[x] = if (rows[y].getOrNull(x) == '#') 0xFFFFFFFF.toInt() else 0
        }
    }

    @Test
    fun `each line of letters is found, with its own width`() {
        val found = lines(
            "..........",
            "..####....",
            "..#..#....",
            "..........",
            "..........",
            "..........",
            "..........",
            ".#######..",
            ".#.....#..",
            ".........."
        )
        assertEquals(
            listOf(SubtitleImageBox.Line(top = 1, bottom = 2, left = 2, right = 5), SubtitleImageBox.Line(top = 7, bottom = 8, left = 1, right = 7)),
            found
        )
    }

    @Test
    fun `an accent a few rows over its letter is the same line`() {
        val found = lines(
            "...#...",
            ".......",
            ".......",
            "..###..",
            "..#.#.."
        )
        assertEquals(listOf(SubtitleImageBox.Line(top = 0, bottom = 4, left = 2, right = 4)), found)
    }

    @Test
    fun `soft edges and empty pictures have no lines`() {
        assertEquals(emptyList<SubtitleImageBox.Line>(), SubtitleImageBox.linesOf(3, 2) { _, row -> row.fill(0x10FFFFFF) })
        assertEquals(emptyList<SubtitleImageBox.Line>(), SubtitleImageBox.linesOf(0, 0) { _, _ -> })
    }

    @Test
    fun `the room around the letters follows the tallest line`() {
        assertEquals(3, SubtitleImageBox.paddingFor(listOf(SubtitleImageBox.Line(0, 9, 0, 10))))
        assertEquals(10, SubtitleImageBox.paddingFor(listOf(SubtitleImageBox.Line(0, 59, 0, 10), SubtitleImageBox.Line(80, 99, 0, 10))))
        assertEquals(24, SubtitleImageBox.paddingFor(listOf(SubtitleImageBox.Line(0, 299, 0, 10))))
    }
}

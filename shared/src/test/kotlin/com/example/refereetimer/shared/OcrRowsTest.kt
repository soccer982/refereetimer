package com.example.refereetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRowsTest {

    /** Builds what ML Kit typically returns for a table: each column as its own block of lines. */
    private fun table(x0: Int, y0: Int, rows: List<List<String>>, colX: List<Int>): List<OcrLine> =
        rows.flatMapIndexed { r, cells ->
            cells.mapIndexedNotNull { c, text ->
                if (text.isEmpty()) null
                else OcrLine(text, x0 + colX[c], y0 + r * 30 + (c % 2) * 3, x0 + colX[c] + text.length * 9, y0 + r * 30 + 20 + (c % 2) * 3)
            }
        }.sortedBy { it.left }   // column by column, like ML Kit blocks

    private val homeRows = listOf(
        listOf("1", "52416-849506", "10", "Carter Cahall", "04/14"),
        listOf("2", "75331-761126", "21", "Joseph Ciminicione", "10/13"),
        listOf("3", "87580-169085 (CPP)", "", "Declan Davis", "10/14"),
        listOf("4", "15650-612953", "13", "Nicholas Ganun", "02/15"),
        listOf("5", "51092-902288", "8", "Lorenzo Gonzalez", "08/13")
    )
    private val awayRows = listOf(
        listOf("1", "72606-624206", "6", "Michael Brennan", "08/13"),
        listOf("2", "16414-749750", "23", "James Cardinale", "02/15"),
        listOf("3", "89029-616845", "8", "Carmelo Casillas", "12/13"),
        listOf("4", "13939-706337 (CPP)", "", "Austin Cook", "10/13")
    )
    private val cols = listOf(0, 30, 160, 200, 380)

    @Test fun rebuildsRowsFromColumnBlocks() {
        val lines = listOf(OcrLine("H NEW JERSEY SURF", 0, 0, 200, 20)) + table(0, 40, homeRows, cols)
        val card = RosterCardParser.parseLines(OcrRows.rows(lines))
        assertEquals(listOf(10, 21, null, 13, 8), card.home.players.map { it.jersey })
        assertEquals("Declan Davis", card.home.players[2].name)
    }

    @Test fun sideBySideTablesAreReadSeparately() {
        val lines = listOf(OcrLine("EDP League Fall 2026   Game #6893   Marlton Memorial Sports Complex", 0, 0, 1000, 20)) +
            OcrLine("H NEW JERSEY SURF", 0, 40, 180, 60) + table(0, 70, homeRows, cols) +
            OcrLine("A LACEY SC CEDAR STARS", 560, 40, 760, 60) + table(560, 70, awayRows, cols)
        val card = RosterCardParser.parseLines(OcrRows.rows(lines))
        assertEquals(5, card.home.players.size)
        assertEquals(4, card.away.players.size)
        assertEquals(listOf(6, 23, 8, null), card.away.players.map { it.jersey })
        assertEquals("6893", card.info.gameNumber)
    }

    @Test fun tiltedPhotoIsStraightened() {
        val flat = listOf(OcrLine("H NEW JERSEY SURF", 0, 0, 200, 20)) + table(0, 40, homeRows, cols)
        // Tilt everything by 3 degrees, then straighten it back
        val tilted = OcrRows.deskew(flat, -3.0)
        val card = RosterCardParser.parseLines(OcrRows.rows(OcrRows.deskew(tilted, 3.0)))
        assertEquals(listOf(10, 21, null, 13, 8), card.home.players.map { it.jersey })
        // Without straightening a 3° tilt mixes rows up on a wide table
        assertEquals(5, card.home.players.size)
    }

    @Test fun commonMisreadsAreFixed() {
        val card = RosterCardParser.parseText("""
            H NEW JERSEY SURF
            1 52416-8495O6 1O Carter Cahall O4/14
            2 75331-761126 2l Joseph Ciminicione 10/13
        """.trimIndent())
        assertEquals(listOf(10, 21), card.home.players.map { it.jersey })
        assertEquals("52416-849506", card.home.players[0].playerId)
        assertEquals("04/14", card.home.players[0].dob)
    }
}

package com.example.refereetimer.shared

import com.example.refereetimer.shared.models.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterCardParserTest {

    /** The EDP Fall 2026 card, laid out the way the printed GotSport card reads row by row. */
    private val rawCard = """
        EDP League Fall 2026
        system.gotsport.com/scoring
        Event Code: EDPFall26 PIN: 6655
        09/19/26 04:00PM EDT
        Printed: 09/18/26 12:25PM UTC
        Marlton Memorial Sports Complex: M2 Turf Field
        Male U13 - 13B Central Atlantic Flight 4 White
        Game #6893
        H NEW JERSEY SURF NJ SOUTH MARLTON 2013/14 BOYS
        # Player ID Name DOB
        1 52416-849506 10 Carter Cahall 04/14
        2 75331-761126 21 Joseph Ciminicione 10/13
        3 87580-169085 (CPP) Declan Davis 10/14
        4 15650-612953 13 Nicholas Ganun 02/15
        5 51092-902288 8 Lorenzo Gonzalez 08/13
        6 15650-612953 24 Francizco Gonzalez 04/16
        7 83886-138621 9 Mason Goodwin 03/14
        8 43377-860551 2 Brody Gross 05/14
        9 95752-055724 16 Nick Joyce 07/14
        10 17892-933743 35 Jameson Phoenix 06/14
        11 83884-457623 1 Andrew Richardson 12/14
        12 67324-887474 30 Harsh Shah 06/14
        13 26731-020585 15 Jeffrey Storch 11/13
        14 66549-958309 Parker Storch 08/15
        15 99240-780962 29 Liam Zedek 10/14
        16 69779-492623 23 Ethan Zheng 02/14
        Coach Marcus Littlefield 94641-433161
        A LACEY SC CEDAR STARS ACADEMY OCEAN 2014B
        # Player ID Name DOB
        1 72606-624206 6 Michael Brennan 08/13
        2 16414-749750 23 James Cardinale 02/15
        3 89029-616845 8 Carmelo Casillas 12/13
        4 13939-706337 (CPP) Austin Cook 10/13
        5 49806-234890 5 Jaxon Deyonker 09/13
        6 21151-506560 17 Davin Estelle 07/14
        7 34738-186106 10 Max Groesch 10/14
        8 81598-560351 12 Lukas Haskell 07/14
        9 76900-796700 27 Cole Haynes 12/14
        10 14910-583091 7 Connor Mooney 07/14
        11 24077-158899 2 Jonathan Morales Alarcon 05/14
        12 37495-187082 30 Ernst Ottoson 07/15
        13 31207-790457 1 Kyle Palheta 04/14
        14 19676-895939 11 Gannon Pisano 04/14
        15 91242-684621 18 Jack Ranahan 06/14
        16 91078-599026 9 Landon Stoltzfus 05/14
        17 38831-002238 19 Jason Van de Water 05/14
        Coach Steve Torre 59623-512359
        Asst. Coach Brian Stoltzfus 38006-316715
    """.trimIndent()

    /** The cleaned-up list format (as pasted in chat / from an AI summary). */
    private val formattedList = """
        1. Parsed Roster & Match Data
        * Match Metadata
           * League / System: EDP League Fall 2026 (`[system.gotsport.com/scoring](https://system.gotsport.com/scoring)`)
           * Event Code & PIN: `EDPFall26` | PIN: `6655`
           * Date & Time: `09/19/26` at `04:00PM EDT` (Printed `09/18/26 12:25PM UTC`)
           * Venue / Field: `Marlton Memorial Sports Complex: M2 Turf Field`
           * Age / Flight: `Male U13 - 13B Central Atlantic Flight 4 White`
           * Game #: `6893`
        * Home Team (`H`): NEW JERSEY SURF NJ SOUTH MARLTON 2013/14 BOYS
           * Primary Coach: `Marcus Littlefield` (ID: `94641-433161`)
           * Roster (16 players):
              1. `#10` — `Carter Cahall` | ID: `52416-849506` | DOB (`MM/YY`): `04/14`
              2. `#21` — `Joseph Ciminicione` | ID: `75331-761126` | DOB: `10/13`
              3. (no #) — `Declan Davis` | ID: `87580-169085 (CPP)` | DOB: `10/14`
              4. `#13` — `Nicholas Ganun` | ID: `15650-612953` | DOB: `02/15`
              5. `#8` — `Lorenzo Gonzalez` | ID: `51092-902288` | DOB: `08/13`
              6. `#24` — `Francisco Gonzalez` (printed as "Francizco Gonzalez") | ID: `15650-612953` | DOB: `04/16`
              7. `#9` — `Mason Goodwin` | ID: `83886-138621` | DOB: `03/14`
              8. `#2` — `Brody Gross` | ID: `43377-860551` | DOB: `05/14`
              9. `#16` — `Nick Joyce` | ID: `95752-055724` | DOB: `07/14`
              10. `#35` — `Jameson Phoenix` | ID: `17892-933743` | DOB: `06/14`
              11. `#1` — `Andrew Richardson` | ID: `83884-457623` | DOB: `12/14`
              12. `#30` — `Harsh Shah` | ID: `67324-887474` | DOB: `06/14`
              13. `#15` — `Jeffrey Storch` | ID: `26731-020585` | DOB: `11/13`
              14. (no #) — `Parker Storch` | ID: `66549-958309` | DOB: `08/15`
              15. `#29` — `Liam Zedek` | ID: `99240-780962` | DOB: `10/14`
              16. `#23` — `Ethan Zheng` | ID: `69779-492623` | DOB: `02/14`
        * Away Team (`A`): LACEY SC CEDAR STARS ACADEMY OCEAN 2014B
           * Primary Coach: `Steve Torre` (ID: `59623-512359`)
           * Assistant Coach 1: `Brian Stoltzfus` (ID: `38006-316715`)
           * Roster (17 players):
              1. `#6` — `Michael Brennan` | ID: `72606-624206` | DOB: `08/13`
              2. `#23` — `James Cardinale` | ID: `16414-749750` | DOB: `02/15`
              3. `#8` — `Carmelo Casillas` (partially crossed/marked) | ID: `89029-616845` | DOB: `12/13`
              4. (no #) — `Austin Cook` | ID: `13939-706337 (CPP)` | DOB: `10/13`
              5. `#5` — `Jaxon Deyonker` | ID: `49806-234890` | DOB: `09/13`
              6. `#17` — `Davin Estelle` | ID: `21151-506560` | DOB: `07/14`
              7. `#10` — `Max Groesch` | ID: `34738-186106` | DOB: `10/14`
              8. `#12` — `Lukas Haskell` | ID: `81598-560351` | DOB: `07/14`
              9. `#27` — `Cole Haynes` | ID: `76900-796700` | DOB: `12/14`
              10. `#7` — `Connor Mooney` | ID: `14910-583091` | DOB: `07/14`
              11. `#2` — `Jonathan Morales Alarcon` | ID: `24077-158899` | DOB: `05/14`
              12. `#30` — `Ernst Ottoson` | ID: `37495-187082` | DOB: `07/15`
              13. `#1` — `Kyle Palheta` | ID: `31207-790457` | DOB: `04/14`
              14. `#11` — `Gannon Pisano` | ID: `19676-895939` | DOB: `04/14`
              15. `#18` — `Jack Ranahan` | ID: `91242-684621` | DOB: `06/14`
              16. `#9` — `Landon Stoltzfus` | ID: `91078-599026` | DOB: `05/14`
              17. `#19` — `Jason Van de Water` | ID: `38831-002238` | DOB: `05/14`
    """.trimIndent()

    private fun checkEdpCard(card: RosterCard, francisco: String) {
        val info = card.info
        assertEquals("EDP League Fall 2026", info.league)
        assertEquals("EDPFall26", info.eventCode)
        assertEquals("6655", info.pin)
        assertEquals("6893", info.gameNumber)
        assertEquals("09/19/2026", info.date)
        assertEquals("04:00 PM", info.kickoff)
        assertEquals("EDT", info.timeZone)
        assertEquals("Marlton Memorial Sports Complex: M2 Turf Field", info.venue)
        assertEquals("Male U13 - 13B Central Atlantic Flight 4 White", info.ageGroup)

        val home = card.home
        assertEquals("NEW JERSEY SURF NJ SOUTH MARLTON 2013/14 BOYS", home.teamName)
        assertEquals(16, home.players.size)
        assertEquals(listOf("Marcus Littlefield"), home.coachLabels())
        assertEquals("94641-433161", home.coaches[0].id)

        val carter = home.players[0]
        assertEquals(10, carter.jersey); assertEquals("Carter Cahall", carter.name)
        assertEquals("52416-849506", carter.playerId); assertEquals("04/14", carter.dob); assertFalse(carter.uncertain)

        val declan = home.players[2]
        assertNull(declan.jersey); assertEquals("Declan Davis", declan.name); assertTrue(declan.cpp)
        assertEquals("87580-169085", declan.playerId)

        assertEquals(francisco, home.players[5].name); assertEquals(24, home.players[5].jersey)
        assertNull(home.players[13].jersey); assertEquals("Parker Storch", home.players[13].name)
        assertEquals(listOf(10, 21, null, 13, 8, 24, 9, 2, 16, 35, 1, 30, 15, null, 29, 23), home.players.map { it.jersey })

        val away = card.away
        assertEquals("LACEY SC CEDAR STARS ACADEMY OCEAN 2014B", away.teamName)
        assertEquals(17, away.players.size)
        assertEquals(listOf("Steve Torre", "Brian Stoltzfus (Asst. Coach)"), away.coachLabels())
        assertEquals(listOf(6, 23, 8, null, 5, 17, 10, 12, 27, 7, 2, 30, 1, 11, 18, 9, 19), away.players.map { it.jersey })
        assertEquals("Jonathan Morales Alarcon", away.players[10].name)
        assertEquals("Jason Van de Water", away.players[16].name)
        assertEquals("Carmelo Casillas", away.players[2].name)
        assertTrue(away.players[3].cpp)
        assertTrue(card.unassigned.players.isEmpty())
    }

    @Test fun rawGotSportCard() = checkEdpCard(RosterCardParser.parseText(rawCard), "Francizco Gonzalez")

    @Test fun formattedList() = checkEdpCard(RosterCardParser.parseText(formattedList), "Francisco Gonzalez")

    @Test fun duplicateIdIsFlagged() {
        val players = RosterCardParser.parseText(rawCard).home.toPlayers()
        val issues = RosterCheck.issues(players)
        assertTrue(issues[3]!!.any { it.contains("player ID") })     // Ganun
        assertTrue(issues[5]!!.any { it.contains("player ID") })     // Gonzalez
        assertTrue(issues[2]!!.contains("No jersey number"))          // Declan Davis
        assertFalse(issues.containsKey(0))
    }

    @Test fun simpleList() {
        val card = RosterCardParser.parseText("""
            Eagles FC U12
            10 Carter Cahall
            21 Joseph Ciminicione
            1 ANDREW RICHARDSON
            7 O'NEIL MCDONALD-SMITH
        """.trimIndent())
        val p = card.unassigned.players
        assertEquals(listOf(10, 21, 1, 7), p.map { it.jersey })
        assertEquals("Andrew Richardson", p[2].name)
        assertEquals("O'Neil McDonald-Smith", p[3].name)
    }

    @Test fun pdfColumnsInOtherOrder() {
        // Copying from a PDF can put the name first and the number after the ID
        val card = RosterCardParser.parseText("""
            H NEW JERSEY SURF
            Cahall, Carter 52416-849506 10 04/14
            Ciminicione, Joseph 75331-761126 21 10/13
            Davis, Declan 87580-169085 CPP 10/14
        """.trimIndent())
        assertEquals(listOf(10, 21, null), card.home.players.map { it.jersey })
        assertEquals(listOf("Carter Cahall", "Joseph Ciminicione", "Declan Davis"), card.home.players.map { it.name })
    }

    @Test fun croppedTableWithoutHeading() {
        val card = RosterCardParser.parseText("""
            1 72606-624206 6 Michael Brennan 08/13
            2 16414-749750 23 James Cardinale 02/15
            3 13939-706337 Austin Cook 10/13
        """.trimIndent())
        assertFalse(card.hasSides)
        assertEquals(listOf(6, 23, null), card.merged().players.map { it.jersey })
    }

    @Test fun toPlayerUsesNoNumberMarker() {
        val p = RosterEntry(null, "Declan Davis", cpp = true).toPlayer()
        assertEquals(Player.NO_NUMBER, p.jerseyNumber); assertTrue(p.flagged); assertEquals("#?", p.tag)
    }
}

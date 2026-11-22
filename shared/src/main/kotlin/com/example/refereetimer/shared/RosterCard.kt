package com.example.refereetimer.shared

import com.example.refereetimer.shared.models.Player

/**
 * NEW: reads a game-day roster card (GotSport / EDP / SJSL style) from text recognition or pasted text.
 *
 * Works with:
 *   • the printed GotSport card: "H TEAM NAME" / "A TEAM NAME" sections, rows like
 *     "1  52416-849506  10  Carter Cahall  04/14" (row #, player ID, jersey, name, DOB MM/YY)
 *   • text copied out of the card PDF (columns may come through in any order)
 *   • a cleaned-up list: "#10 — Carter Cahall | ID: 52416-849506 | DOB: 04/14", "(no #) — Declan Davis …"
 *   • a simple list: "10 Carter Cahall"
 * Match details printed on the card (event code, PIN, game #, date, field, age group) are read too.
 */
data class RosterEntry(
    val jersey: Int?,                 // null = no number on the card
    val name: String,
    val playerId: String = "",
    val dob: String = "",
    val cpp: Boolean = false,
    val uncertain: Boolean = false    // the number or name may have been misread
) {
    fun toPlayer(): Player = Player(
        jerseyNumber = jersey ?: Player.NO_NUMBER, name = name,
        flagged = uncertain || jersey == null, playerId = playerId, dob = dob, cpp = cpp
    )
}

data class RosterCoach(val name: String, val role: String = "Coach", val id: String = "") {
    /** How a coach is stored on a Team: "Marcus Littlefield" or "Brian Stoltzfus (Asst. Coach)". */
    val label: String get() = if (role.equals("Coach", true) || role.isBlank()) name else "$name ($role)"
}

data class RosterSection(
    val teamName: String = "",
    val coaches: List<RosterCoach> = emptyList(),
    val players: List<RosterEntry> = emptyList()
) {
    val isEmpty: Boolean get() = players.isEmpty() && coaches.isEmpty() && teamName.isBlank()
    fun toPlayers(): List<Player> = players.map { it.toPlayer() }
    fun coachLabels(): List<String> = coaches.map { it.label }
}

/** Match details printed on the card. Blank = not found. */
data class CardInfo(
    val league: String = "",
    val eventCode: String = "",
    val pin: String = "",
    val gameNumber: String = "",
    val date: String = "",        // MM/dd/yyyy
    val kickoff: String = "",     // "04:00 PM"
    val timeZone: String = "",    // "EDT"
    val venue: String = "",
    val ageGroup: String = ""     // "Male U13 - 13B Central Atlantic Flight 4 White"
) {
    val isEmpty: Boolean get() = listOf(league, eventCode, pin, gameNumber, date, kickoff, venue, ageGroup).all { it.isBlank() }

    fun summary(): List<Pair<String, String>> = listOf(
        "League" to league, "Event code" to eventCode, "PIN" to pin, "Game #" to gameNumber,
        "Date" to date, "Kickoff" to listOf(kickoff, timeZone).filter { it.isNotBlank() }.joinToString(" "),
        "Field" to venue, "Age / Flight" to ageGroup
    ).filter { it.second.isNotBlank() }
}

data class RosterCard(
    val info: CardInfo = CardInfo(),
    val home: RosterSection = RosterSection(),
    val away: RosterSection = RosterSection(),
    /** Rows found before any Home/Away heading (e.g. a crop of just the player table). */
    val unassigned: RosterSection = RosterSection()
) {
    val playerCount: Int get() = home.players.size + away.players.size + unassigned.players.size
    val hasSides: Boolean get() = home.players.isNotEmpty() || away.players.isNotEmpty()

    /** Everything found, as one team: used when the picture was cropped to a single team. */
    fun merged(): RosterSection {
        val parts = listOf(unassigned, home, away)
        return RosterSection(
            teamName = parts.map { it.teamName }.firstOrNull { it.isNotBlank() } ?: "",
            coaches = parts.flatMap { it.coaches }.distinctBy { it.name.lowercase() },
            players = parts.flatMap { it.players }
        )
    }
}

object RosterCardParser {

    private enum class Side { HOME, AWAY, NONE }

    // ── Patterns ──
    private val ID_RE = Regex("""\b(\d{4,6})\s?[-–—]\s?(\d{5,7})\b""")
    private val ID_NO_DASH_RE = Regex("""\b(\d{5})(\d{6})\b""")
    private val DOB_RE = Regex("""(?<![\d/])(\d{1,2})\s?/\s?(\d{2}|\d{4})(?![\d/])""")
    private val FULL_DATE_RE = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{2}|\d{4})\b""")
    private val CPP_RE = Regex("""(?i)\(?\s*\bC\.?P\.?P\b\.?\s*\)?""")
    private val NO_NUMBER_RE = Regex("""(?i)\(?\s*no\s*(?:#|number|jersey)\s*\)?""")
    private val HASH_NUM_RE = Regex("""#\s?(\d{1,2})\b""")
    private val LIST_INDEX_RE = Regex("""^(\d{1,2})[.)]\s+""")
    private val TIME_RE = Regex("""(?i)\b(\d{1,2}):(\d{2})\s?([AP])\.?\s?M\.?\b(?:\s+([A-Z]{2,4})\b)?""")
    private val AGE_RE = Regex("""(?i)\b(male|female|boys|girls|men|women|coed)\b[^\n]*?\bU\s?-?\d{1,2}\b""")
    private val COACH_RE = Regex("""(?i)\b(coach|manager|trainer|staff|asst\.?|assistant)\b""")
    private val COLUMN_HEADER_RE = Regex("""(?i)^(?:#|no\.?|num|jersey|player|name|id|dob|birth|pass|mm/yy|\s|[|/()]|date of birth|player id)+$""")

    /** Label words that mean "this line is match information, not a player". */
    private val META_LABEL_RE = Regex(
        """(?i)\b(league|system|event\s*code|pin|game\s*(?:#|no|number)|date|time|venue|field|location|""" +
            """flight|division|printed|gotsport|scoring|referee|official|score|half|result|signature|roster\s*\(|""" +
            """match\s*metadata|parsed)\b"""
    )

    fun parseText(text: String): RosterCard = parseLines(text.lines())

    fun parseLines(rawLines: List<String>): RosterCard {
        val lines = rawLines.map { normalise(it) }.filter { it.isNotBlank() }
        val info = parseInfo(lines)

        val builders = mapOf(Side.HOME to Builder(), Side.AWAY to Builder(), Side.NONE to Builder())
        var side = Side.NONE
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val header = sideHeader(line)
            if (header != null) {
                side = header.first
                var name = header.second
                // "H" or "Home Team" on its own line: the team name is on the next line
                if (name.isBlank() && i + 1 < lines.size && looksLikeTeamName(lines[i + 1])) { name = lines[i + 1]; i++ }
                if (name.isNotBlank() && builders.getValue(side).teamName.isBlank()) builders.getValue(side).teamName = tidyTeam(name)
                i++; continue
            }
            val b = builders.getValue(side)
            val coach = parseCoach(line)
            if (coach != null) b.coaches += coach
            else if (!isMetaLine(line)) parseRow(line)?.let { b.rows += it }
            i++
        }

        return RosterCard(
            info = info,
            home = builders.getValue(Side.HOME).build(),
            away = builders.getValue(Side.AWAY).build(),
            unassigned = builders.getValue(Side.NONE).build()
        )
    }

    // ── Cleaning ──

    /** Removes markdown, bullets and table bars so every source looks alike. */
    private fun normalise(s: String): String {
        var t = s.replace('`', ' ').replace("**", " ").replace("__", " ")
            .replace('|', ' ').replace('\t', ' ').replace('•', ' ').replace('·', ' ')
            .replace('“', '"').replace('”', '"').replace('’', '\'')
        t = t.replace(Regex("""^\s*[-*+]\s+"""), "")
        t = t.replace(Regex("""\s+[—–]\s+"""), "  ").replace(Regex("""^[—–-]\s*"""), "")
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    private fun isMetaLine(line: String): Boolean {
        if (META_LABEL_RE.containsMatchIn(line) && !ID_RE.containsMatchIn(line)) return true
        if (AGE_RE.containsMatchIn(line) && !ID_RE.containsMatchIn(line)) return true
        if (FULL_DATE_RE.containsMatchIn(line)) return true
        if (Regex("""(?i)https?://|www\.|\.com\b""").containsMatchIn(line)) return true
        return false
    }

    // ── Home / Away headings ──

    private fun sideHeader(line: String): Pair<Side, String>? {
        if (ID_RE.containsMatchIn(line) || DOB_RE.containsMatchIn(line)) return null
        // "Home Team (H): NAME", "Home: NAME", "HOME TEAM", "Visitor - NAME"
        Regex("""(?i)^(home|away|visitors?|visiting|guests?)(?:\s+team)?\s*(?:\(\s*[HA]\s*\))?\s*[:\-–]?\s*(.*)$""").find(line)?.let { m ->
            val word = m.groupValues[1].lowercase()
            val rest = m.groupValues[2].trim()
            // "Home team colours: red" etc. aren't headings
            if (rest.isNotEmpty() && !looksLikeTeamName(rest)) return null
            return (if (word == "home") Side.HOME else Side.AWAY) to rest
        }
        // GotSport card: "H NEW JERSEY SURF …" / "A LACEY SC …" (or a lone "H" / "A")
        Regex("""^([HA])(?:\s*[:\-–.)]\s*|\s+)(.+)$""").find(line)?.let { m ->
            val rest = m.groupValues[2].trim()
            if (!looksLikeTeamName(rest) || !mostlyUpper(rest)) return null
            return (if (m.groupValues[1] == "H") Side.HOME else Side.AWAY) to rest
        }
        if (line == "H" || line == "A") return (if (line == "H") Side.HOME else Side.AWAY) to ""
        // "NAME (H)" / "NAME (Away)"
        Regex("""(?i)^(.{3,}?)\s*\(\s*(h|home|a|away)\s*\)$""").find(line)?.let { m ->
            if (!looksLikeTeamName(m.groupValues[1])) return null
            return (if (m.groupValues[2].lowercase().startsWith("h")) Side.HOME else Side.AWAY) to m.groupValues[1]
        }
        return null
    }

    private fun looksLikeTeamName(s: String): Boolean {
        val t = s.trim()
        if (t.length < 3 || ID_RE.containsMatchIn(t) || COACH_RE.containsMatchIn(t)) return false
        if (Regex("""(?i)^roster\b""").containsMatchIn(t)) return false
        return t.count { it.isLetter() } >= 3
    }

    private fun mostlyUpper(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.count { it.isUpperCase() } >= letters.length * 0.7
    }

    private fun tidyTeam(s: String): String =
        s.replace(Regex("""(?i)\(\s*[HA]\s*\)"""), " ").replace(Regex("""^[:\-–\s]+|[:\-–\s]+$"""), "")
            .replace(Regex("""\s+"""), " ").trim()

    // ── Coaches ──

    private fun parseCoach(line: String): RosterCoach? {
        if (!COACH_RE.containsMatchIn(line) || line.contains("referee", true)) return null
        if (DOB_RE.containsMatchIn(line.replace(ID_RE, " "))) return null      // a player row that happens to say "Assistant"
        val id = ID_RE.find(line)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" } ?: ""
        val lower = line.lowercase()
        val role = when {
            Regex("""\b(asst|assistant)\b""").containsMatchIn(lower) -> "Asst. Coach"
            "manager" in lower -> "Manager"
            "trainer" in lower -> "Trainer"
            else -> "Coach"
        }
        val name = line.replace(ID_RE, " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""(?i)\b(primary|head|asst\.?|assistant|coach(es)?|manager|trainer|staff|team|id|no\.?)\b"""), " ")
            .replace(Regex("""[^A-Za-z\s.'-]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().trim('-', '.', ' ')
        if (name.split(" ").count { it.length >= 2 } < 2) return null
        return RosterCoach(fixCase(name), role, id)
    }

    // ── Player rows ──

    private class RawRow(
        val listIndex: Int?, val hashJersey: Int?, val noNumber: Boolean, val bare: List<Int>,
        val name: String, val id: String, val dob: String, val cpp: Boolean, val odd: Boolean
    )

    private fun parseRow(line: String): RawRow? {
        if (COLUMN_HEADER_RE.matches(line)) return null
        var t = fixDigits(line)

        val idMatch = ID_RE.find(t) ?: ID_NO_DASH_RE.find(t)
        val id = idMatch?.let { "${it.groupValues[1]}-${it.groupValues[2]}" } ?: ""
        if (idMatch != null) t = t.removeRange(idMatch.range).let { " $it " }

        val cpp = CPP_RE.containsMatchIn(t)
        t = t.replace(CPP_RE, " ")

        val noNumber = NO_NUMBER_RE.containsMatchIn(t)
        t = t.replace(NO_NUMBER_RE, " ")

        // DOB: the last MM/YY on the line
        val dobMatch = DOB_RE.findAll(t).lastOrNull()?.takeIf { it.groupValues[1].toInt() in 1..12 }
        val dob = dobMatch?.let { "${it.groupValues[1].padStart(2, '0')}/${it.groupValues[2]}" } ?: ""
        if (dobMatch != null) t = t.removeRange(dobMatch.range)

        // Notes in brackets: "(printed as …)", "(partially crossed)", "(MM/YY)"
        t = t.replace(Regex("""\([^)]*\)"""), " ").replace(Regex("""\([^)]*$"""), " ")

        val listIndex = LIST_INDEX_RE.find(t.trim())?.groupValues?.get(1)?.toInt()
        if (listIndex != null) t = t.trim().replaceFirst(LIST_INDEX_RE, " ")

        val hash = HASH_NUM_RE.find(t)?.groupValues?.get(1)?.toInt()
        t = t.replace(HASH_NUM_RE, " ")

        // Labels
        t = t.replace(Regex("""(?i)\b(id|dob|jersey|no\.?|number|player|pass|mm/yy)\b\s*:?"""), " ")

        val bare = Regex("""(?<![\w/])(\d{1,3})(?![\w/])""").findAll(t).map { it.groupValues[1].toInt() }.toList()
        t = t.replace(Regex("""\d+"""), " ")

        // "Cahall, Carter" → "Carter Cahall"
        val words0 = t.replace(Regex("""[^A-Za-z\s.,'-]"""), " ").replace(Regex("""\s+"""), " ").trim().trim(',', '.', '-', ' ')
        val nameRaw = Regex("""^([A-Za-z'.-]+(?:\s[A-Za-z'.-]+)?),\s*([A-Za-z'.-]+(?:\s[A-Za-z'.-]+)*)$""").find(words0)
            ?.let { "${it.groupValues[2]} ${it.groupValues[1]}" } ?: words0.replace(",", " ")
        val name = fixCase(nameRaw.replace(Regex("""\s+"""), " ").trim())

        val nameWords = name.split(" ").filter { it.count(Char::isLetter) >= 2 }
        if (nameWords.isEmpty()) return null
        val hasEvidence = id.isNotEmpty() || dob.isNotEmpty() || hash != null || noNumber || bare.isNotEmpty()
        if (!hasEvidence) return null
        // A single word with nothing but a number is usually a heading ("Roster 16")
        if (nameWords.size < 2 && id.isEmpty() && dob.isEmpty()) return null

        val odd = Regex("""[^A-Za-z\s.'-]""").containsMatchIn(nameRaw) || nameWords.any { it.length == 1 }
        return RawRow(listIndex, hash, noNumber, bare.filter { it in 0..99 }, name, id, dob, cpp, odd)
    }

    /**
     * Text recognition often reads 0 as O, 1 as l or I, 5 as S, 8 as B inside numbers.
     * Fixes only tokens that are mostly digits already ("52416-8495O6", "1O", "O4/14").
     */
    private fun fixDigits(line: String): String = line.split(" ").joinToString(" ") { tok ->
        val core = tok.trim('(', ')', ',', '.', '#')
        val digits = core.count { it.isDigit() }
        val fixable = core.count { it in "OoIl|SB" }
        if (core.isNotEmpty() && digits >= 1 && fixable in 1..2 && digits >= fixable &&
            core.all { it.isDigit() || it in "OoIl|SB-/" }) {
            tok.replace(core, core.map { when (it) { 'O', 'o' -> '0'; 'I', 'l', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; else -> it } }.joinToString(""))
        } else tok
    }

    /** Decides, for a whole team, whether the first number on each row is a row counter or the jersey. */
    private fun resolve(rows: List<RawRow>): List<RosterEntry> {
        val needsGuess = rows.filter { it.hashJersey == null && !it.noNumber }
        val withTwo = needsGuess.count { it.bare.size >= 2 }
        var seq = 0
        var seqHits = 0
        needsGuess.forEach { r -> val f = r.bare.firstOrNull(); if (f != null && f in (seq + 1)..(seq + 3)) { seqHits++; seq = f } }
        val rowCounter = needsGuess.isNotEmpty() &&
            (withTwo >= (needsGuess.size + 1) / 2 || (needsGuess.size >= 4 && seqHits >= needsGuess.size * 0.8 && needsGuess.any { it.id.isNotEmpty() }))

        var last = 0
        return rows.map { r ->
            var uncertain = r.odd
            val jersey: Int? = when {
                r.hashJersey != null -> r.hashJersey
                r.noNumber -> null
                r.bare.isEmpty() -> null
                !rowCounter -> { if (r.bare.size > 1) uncertain = true; r.bare.first() }
                else -> {
                    val idx = r.bare.indexOfFirst { it in (last + 1)..(last + 3) }
                    if (idx >= 0) last = r.bare[idx]
                    val rest = r.bare.filterIndexed { k, _ -> k != (if (idx >= 0) idx else 0) }
                    if (idx < 0) uncertain = true
                    if (rest.size > 1) uncertain = true
                    rest.firstOrNull()
                }
            }
            RosterEntry(jersey, r.name, r.id, r.dob, r.cpp, uncertain)
        }
    }

    private class Builder {
        var teamName = ""
        val coaches = mutableListOf<RosterCoach>()
        val rows = mutableListOf<RawRow>()
        fun build() = RosterSection(teamName, coaches.distinctBy { it.name.lowercase() }, resolve(rows))
    }

    /** ALL CAPS / all lower → "Title Case"; mixed case (e.g. "Van de Water") is kept as printed. */
    private fun fixCase(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return s
        val allUpper = letters.all { it.isUpperCase() }
        val allLower = letters.all { it.isLowerCase() }
        if (!allUpper && !allLower) return s
        return s.lowercase().split(" ").joinToString(" ") { w ->
            w.split("-").joinToString("-") { part ->
                part.split("'").joinToString("'") { p -> p.replaceFirstChar { it.uppercase() } }
            }.let { if (it.startsWith("Mc") && it.length > 2) "Mc" + it[2].uppercase() + it.drop(3) else it }
        }
    }

    // ── Match information ──

    private fun parseInfo(lines: List<String>): CardInfo {
        val all = lines.joinToString("\n")
        fun labelled(label: String): String? = lines.firstNotNullOfOrNull { l ->
            Regex("""(?i)^\s*(?:$label)\s*[:\-–]\s*(.+)$""").find(l)?.groupValues?.get(1)?.trim()
        }

        val eventCode = Regex("""(?i)event\s*code\b[^:\n]*?[:#]?\s*([A-Za-z0-9]{4,})""").find(all)?.groupValues?.get(1)
            ?.takeIf { !it.equals("pin", true) } ?: ""
        val pin = Regex("""(?i)\bPIN\b\s*[:#]?\s*(\d{3,8})\b""").find(all)?.groupValues?.get(1) ?: ""
        val game = Regex("""(?i)\b(?:game|match)\s*(?:#|no\.?|number|num)\s*:?\s*#?\s*(\d{2,7})\b""").find(all)?.groupValues?.get(1) ?: ""

        // Kickoff = the first date+time that isn't the "Printed" stamp
        var date = ""; var kickoff = ""; var tz = ""
        for (l in lines) {
            val cleaned = l.replace(Regex("""(?i)\(?\s*printed\b[^)]*\)?"""), " ")
            if (Regex("""(?i)^\s*printed\b""").containsMatchIn(l)) continue
            val d = FULL_DATE_RE.find(cleaned) ?: continue
            date = formatDate(d)
            TIME_RE.find(cleaned)?.let { m ->
                kickoff = "${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]} ${m.groupValues[3].uppercase()}M"
                tz = m.groupValues[4].takeIf { it.isNotBlank() && it.uppercase() !in setOf("AT", "ON") } ?: ""
            }
            break
        }
        if (kickoff.isEmpty()) {
            lines.firstOrNull { !it.contains("printed", true) && TIME_RE.containsMatchIn(it) }?.let { l ->
                TIME_RE.find(l)?.let { m -> kickoff = "${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]} ${m.groupValues[3].uppercase()}M"
                    tz = m.groupValues[4] }
            }
        }

        val venue = labelled("venue\\s*/\\s*field|venue|field|location|site|facility")
            ?: lines.firstOrNull { l ->
                Regex("""(?i)\b(complex|park|field|stadium|turf|school|sportsplex|fields)\b""").containsMatchIn(l) &&
                    !ID_RE.containsMatchIn(l) && sideHeader(l) == null && !AGE_RE.containsMatchIn(l)
            } ?: ""

        val age = labelled("age\\s*/\\s*flight|age\\s*group|division|flight|age")
            ?: lines.firstNotNullOfOrNull { l -> AGE_RE.find(l)?.let { l.substring(it.range.first).trim() } } ?: ""

        val league = labelled("league\\s*/\\s*system|league|competition|system")?.let { stripUrl(it) }
            ?: lines.firstOrNull { l ->
                Regex("""(?i)\b(league|cup|tournament|showcase|premier)\b""").containsMatchIn(l) &&
                    !l.contains(":") && sideHeader(l) == null && !ID_RE.containsMatchIn(l)
            }?.let { stripUrl(it) } ?: ""

        return CardInfo(
            league = league, eventCode = eventCode, pin = pin, gameNumber = game,
            date = date, kickoff = kickoff, timeZone = tz.uppercase(),
            venue = venue.replace(Regex("""\s*\(.*$"""), "").trim(), ageGroup = age.trim()
        )
    }

    private fun stripUrl(s: String) =
        s.replace(Regex("""\(?\[?[\w./:-]*(?:gotsport|https?://|www\.)[^)\s]*\]?\)?(?:\([^)]*\))?"""), " ")
            .replace(Regex("""\s+"""), " ").trim().trim('(', ')', '-', ' ')

    private fun formatDate(m: MatchResult): String {
        var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
        return "${m.groupValues[1].padStart(2, '0')}/${m.groupValues[2].padStart(2, '0')}/$y"
    }
}

/**
 * NEW: problems worth a second look before the roster goes to the watch.
 * Keyed by position in the list; an empty map means the roster looks clean.
 */
object RosterCheck {
    fun issues(players: List<Player>): Map<Int, List<String>> {
        val out = mutableMapOf<Int, MutableList<String>>()
        fun add(i: Int, msg: String) { out.getOrPut(i) { mutableListOf() }.let { if (msg !in it) it += msg } }

        val numbered = players.withIndex().filter { it.value.hasNumber && !it.value.isTeamOfficial }
        numbered.groupBy { it.value.jerseyNumber }.filter { it.value.size > 1 }.forEach { (n, list) ->
            list.forEach { add(it.index, "Number #$n used twice") }
        }
        players.withIndex().filter { it.value.playerId.isNotBlank() }.groupBy { it.value.playerId }
            .filter { it.value.size > 1 }.forEach { (_, list) -> list.forEach { add(it.index, "Same player ID as another player") } }
        players.forEachIndexed { i, p ->
            if (!p.hasNumber) add(i, "No jersey number")
            else if (p.jerseyNumber !in 0..99) add(i, "Number out of range")
            if (p.name.isBlank() || p.name.length < 3) add(i, "Name missing")
            if (p.flagged && p.hasNumber && out[i].isNullOrEmpty()) add(i, "Check number and name")
        }
        return out
    }
}

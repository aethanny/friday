package com.friday.app.ai

import com.friday.app.data.db.Repeat
import com.friday.app.domain.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

enum class Lang { EN, MS }
enum class CommandKind { REMINDER, TASK, NOTE, UNKNOWN }
enum class PlaceEvent { LEAVE, ARRIVE }

data class Parsed(
    val kind: CommandKind,
    val title: String,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMin: Int? = null,
    val repeat: Repeat = Repeat.NONE,
    val repeatDays: Int = 0,
    val important: Boolean = false,
    /** Normalised place name: "home", "work", "campus" or whatever the user said. */
    val place: String? = null,
    val placeEvent: PlaceEvent? = null,
    val lang: Lang = Lang.EN,
    /** "STORE", "PHARMACY" or "SUPERMARKET" for "next time I'm at a …" errands; no saved place involved. */
    val placeType: String? = null,
) {
    /** "Remind me…" without a clock time: FRIDAY has to ask when. */
    val needsTime: Boolean get() = kind == CommandKind.REMINDER && time == null && placeEvent == null && placeType == null
}

/**
 * Deterministic English/Malay command parser. It is not a language model: it recognises
 * common reminder, task and note phrasings and leaves everything else as UNKNOWN.
 */
class CommandParser(private val clock: () -> LocalDateTime = LocalDateTime::now) {

    private enum class Period(val default: LocalTime, val pm: Boolean?) {
        MORNING(LocalTime.of(9, 0), false),
        NOON(LocalTime.NOON, null),
        AFTERNOON(LocalTime.of(13, 0), true),
        PETANG(LocalTime.of(16, 0), true),
        EVENING(LocalTime.of(19, 0), true),
        NIGHT(LocalTime.of(20, 0), true),
    }

    fun parse(input: String): Parsed {
        val now = clock()
        val today = now.toLocalDate()
        val lang = detectLang(input)
        if (NOTE.containsMatchIn(input.trim())) return Parsed(CommandKind.NOTE, input.trim(), lang = lang)

        var s = " " + input.trim().trimEnd('.', '!', '?', ' ') + " "
        fun take(regex: Regex): MatchResult? {
            val m = regex.find(s) ?: return null
            s = s.replaceRange(m.range, " ")
            return m
        }

        val explicitReminder = take(REMIND) != null
        val important = take(IMPORTANT) != null

        // Errands first: "when I'm at a store" would otherwise be read as a saved place called "a".
        val placeType = take(ERRAND)?.let { errandType(it.groupValues.last()) }
        var place: String? = null
        var placeEvent: PlaceEvent? = null
        take(LEAVE)?.let { placeEvent = PlaceEvent.LEAVE; place = normalisePlace(it.groupValues.last()) }
        if (placeEvent == null) take(ARRIVE)?.let { placeEvent = PlaceEvent.ARRIVE; place = normalisePlace(it.groupValues.last()) }

        var repeat = Repeat.NONE
        var repeatDays = 0
        if (take(EVERY_WEEKDAY) != null) repeat = Repeat.WEEKDAYS
        else if (take(DAILY) != null) repeat = Repeat.DAILY
        else if (take(MONTHLY) != null) repeat = Repeat.MONTHLY
        else {
            val days = take(EVERY_DAYS)
            if (days != null) {
                repeat = Repeat.WEEKLY
                WEEKDAY_WORD.findAll(days.value).forEach { d -> weekday(d.groupValues[1])?.let { repeatDays = repeatDays or Schedule.dayBit(it) } }
            } else if (take(WEEKLY) != null) repeat = Repeat.WEEKLY
        }

        val duration = take(DURATION)?.let { minutes(it.groupValues[1], it.groupValues[2]) }

        var date: LocalDate? = null
        var time: LocalTime? = null
        val relative = take(IN_RELATIVE) ?: take(RELATIVE_AFTER)
        if (relative != null) {
            val at = now.plusMinutes(minutes(relative.groupValues[1], relative.groupValues[2]).toLong())
            date = at.toLocalDate(); time = at.toLocalTime().withSecond(0).withNano(0)
        }
        if (date == null) take(IN_DAYS)?.let { date = today.plusDays(it.groupValues[1].ifBlank { it.groupValues[2] }.toLong()) }

        // Look at the period word first (it decides am/pm) but remove it only after the times are read.
        val periodWord = PERIOD.find(s)?.value?.lowercase()
        val period = periodWord?.let(::periodOf)

        if (date == null) date = takeDate(::take, today)

        if (time == null) {
            take(TIME_AMPM)?.let { m -> time = hm(m.groupValues[1], m.groupValues[2], pm = m.groupValues[3].lowercase().startsWith("p")) }
                ?: take(TIME_MS_PERIOD)?.let { m -> time = malayTime(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].lowercase()) }
                ?: (take(TIME_24) ?: take(TIME_BARE))?.let { m ->
                    time = resolveHour(m.groupValues[1], m.groupValues[2], period)
                    val t = time
                    // A bare hour that already passed today most likely means the evening.
                    if (t != null && date == null && period == null && t.hour in 1..11 && !m.groupValues[1].startsWith("0") &&
                        LocalDateTime.of(today, t).isBefore(now)
                    ) time = t.plusHours(12)
                }
        }
        if (time == null && take(MIDNIGHT) != null) {
            time = LocalTime.MIDNIGHT
            if (date == null) date = today.plusDays(1)
        }
        take(PERIOD)
        if (time == null && period != null) time = period.default
        if (date == null && periodWord != null && Regex("this|tonight| ini| ni").containsMatchIn(" $periodWord")) date = today

        // Fill in the date from the time or the repeat rule.
        if (repeat == Repeat.WEEKLY && repeatDays != 0 && date == null) {
            var d = today
            while (repeatDays and Schedule.dayBit(d.dayOfWeek) == 0 ||
                (d == today && time != null && !LocalDateTime.of(d, time).isAfter(now))
            ) d = d.plusDays(1)
            date = d
        }
        if (time != null && date == null) {
            date = if (LocalDateTime.of(today, time).isAfter(now)) today else today.plusDays(1)
        }
        if (repeat != Repeat.NONE && date == null) date = today
        if (repeat == Repeat.WEEKLY && repeatDays == 0) date?.let { repeatDays = Schedule.dayBit(it.dayOfWeek) }

        val title = cleanTitle(s)
        val kind = when {
            explicitReminder || placeEvent != null || placeType != null -> CommandKind.REMINDER
            time != null || date != null || repeat != Repeat.NONE -> CommandKind.TASK
            else -> CommandKind.UNKNOWN
        }
        return Parsed(kind, title, date, time, duration, repeat, repeatDays, important, place, placeEvent, lang, placeType)
    }

    private fun takeDate(take: (Regex) -> MatchResult?, today: LocalDate): LocalDate? {
        if (take(DAY_AFTER) != null) return today.plusDays(2)
        if (take(TOMORROW) != null) return today.plusDays(1)
        if (take(TODAY) != null) return today
        take(ON_WEEKDAY)?.let { m ->
            val day = weekday(m.groupValues[2]) ?: return null
            val next = m.groupValues[1].lowercase().trim() == "next" || m.groupValues[3].isNotBlank()
            return if (next) today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(day))
            else today.with(TemporalAdjusters.nextOrSame(day))
        }
        take(DAY_MONTH)?.let { return dated(it.groupValues[1].toInt(), month(it.groupValues[2]), today) }
        take(MONTH_DAY)?.let { return dated(it.groupValues[2].toInt(), month(it.groupValues[1]), today) }
        take(NUMERIC_DATE)?.let { m ->
            val y = m.groupValues[3].takeIf { it.isNotBlank() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            return runCatching {
                if (y != null) LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
                else dated(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today)
            }.getOrNull()
        }
        if (take(WEEKEND) != null) return if (today.dayOfWeek >= DayOfWeek.SATURDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        if (take(NEXT_WEEK) != null) return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        if (take(NEXT_MONTH) != null) return today.plusMonths(1)
        return null
    }

    private fun periodOf(w: String): Period = when {
        w.contains("morning") || w.contains("pagi") -> Period.MORNING
        w.contains("noon") || w.contains("midday") || w.contains("tengah") || w.contains("tgh") -> Period.NOON
        w.contains("afternoon") -> Period.AFTERNOON
        w.contains("petang") -> Period.PETANG
        w.contains("evening") -> Period.EVENING
        else -> Period.NIGHT
    }

    /** Hour without am/pm: the period word decides, otherwise 1–6 means afternoon. */
    private fun resolveHour(hRaw: String, mRaw: String, period: Period?): LocalTime? {
        val h = hRaw.toInt(); val m = mRaw.ifBlank { "0" }.toInt()
        if (h > 23 || m > 59) return null
        if (h > 12 || (hRaw.length == 2 && hRaw.startsWith("0"))) return LocalTime.of(h, m)
        val hour = when (period?.pm) {
            true -> if (h == 12) 12 else h + 12
            false -> if (h == 12) 0 else h
            null -> if (period == Period.NOON) (if (h in 1..5) h + 12 else h) else if (h in 1..6) h + 12 else h
        }
        return LocalTime.of(hour % 24, m)
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        repeat(3) {
            t = t.replace(LEADING_FILLER, "").replace(TRAILING_FILLER, "").trim()
        }
        t = t.trim(',', ';', ':', '-', ' ', '·', '•')
        return t.replaceFirstChar { it.uppercase() }
    }

    private fun hm(h: String, m: String, pm: Boolean): LocalTime? {
        val hour = h.toInt(); val min = m.ifBlank { "0" }.toInt()
        if (hour !in 1..12 || min > 59) return null
        return LocalTime.of(if (pm) (if (hour == 12) 12 else hour + 12) else (if (hour == 12) 0 else hour), min)
    }

    private fun malayTime(h: Int, m: String, p: String): LocalTime? {
        val min = m.ifBlank { "0" }.toInt()
        if (h !in 0..23 || min > 59) return null
        if (h > 12) return LocalTime.of(h, min)
        val hour = when {
            p.startsWith("pag") || p == "pg" -> if (h == 12) 0 else h
            p.startsWith("teng") || p.startsWith("tgh") -> if (h in 1..5) h + 12 else h
            p.startsWith("pet") || p == "ptg" -> if (h == 12) 12 else h + 12
            else -> when { // malam
                h == 12 -> 0
                h <= 4 -> h
                else -> h + 12
            }
        }
        return LocalTime.of(hour % 24, min)
    }

    private fun minutes(n: String, unit: String): Int {
        val v = when (n.lowercase().trim()) {
            "a", "an", "one", "satu", "se" -> 1.0
            "half an", "half a", "setengah", "sejam" -> 0.5
            else -> n.replace(',', '.').toDoubleOrNull() ?: 1.0
        }
        val u = unit.lowercase()
        val perUnit = if (u.startsWith("h") || u.startsWith("j")) 60 else 1
        return (v * perUnit).toInt().coerceAtLeast(1)
    }

    private fun dated(day: Int, month: Int?, today: LocalDate): LocalDate? {
        if (month == null) return null
        val d = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (d.isBefore(today)) d.plusYears(1) else d
    }

    private fun errandType(w: String) = when (w.lowercase()) {
        "store", "shop", "kedai" -> "STORE"
        "pharmacy", "chemist", "farmasi" -> "PHARMACY"
        else -> "SUPERMARKET"
    }

    private fun normalisePlace(p: String): String = when (p.lowercase().trim()) {
        "home", "house", "rumah" -> "home"
        "work", "office", "pejabat", "tempat kerja", "kerja" -> "work"
        "campus", "kampus", "uni", "university", "universiti" -> "campus"
        else -> p.lowercase().trim()
    }

    companion object {
        private val I = setOf(RegexOption.IGNORE_CASE)
        private const val B = "(?<![\\p{L}\\d])"
        private const val E = "(?![\\p{L}\\d])"

        private const val DAY_WORDS = "monday|mon|tuesday|tues|tue|wednesday|thursday|thurs|thur|thu|friday|fri|saturday|sunday|isnin|selasa|rabu|khamis|jumaat|jumat|sabtu|ahad"
        private const val MONTH_WORDS = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec|januari|februari|mac|mei|julai|ogos|oktober|disember"

        val NOTE = Regex("^(note|nota|idea|i have an idea|i've got an idea|i got an idea|saya ada idea|aku ada idea|ada idea|remember this|save this|simpan ni|simpan ini)$E", I)
        val REMIND = Regex("${B}(remind me|remind|reminder|don'?t let me forget|don'?t forget|ingatkan saya|ingatkan aku|ingatkan|jangan lupa)$E(\\s+(to|that|about|untuk|supaya|nak|yang))?$E", I)
        val IMPORTANT = Regex("$B(important|urgent|penting|segera)$E!?", I)
        val LEAVE = Regex("$B(when i leave|when i get out of|when i exit|when leaving|bila (?:saya |aku )?(?:keluar|tinggalkan)(?: dari)?|masa (?:saya |aku )?keluar(?: dari)?)\\s+(?:the |my )?(home|house|rumah|work|office|pejabat|campus|kampus|[\\p{L}]+)$E", I)
        val ERRAND = Regex("$B(?:next time i(?:'m| am) at|when i(?:'m| am) at|when i go to|when i get to|at the next|bila (?:saya |aku )?(?:pergi|sampai|ke)(?: ke)?|nanti kat)\\s+(?:an? |the |any |sebuah )?(store|shop|kedai|pharmacy|chemist|farmasi|supermarket|grocery store|grocery|pasar raya)$E", I)
        val ARRIVE = Regex("$B(when i get to|when i get|when i arrive at|when i arrive|when i reach|when i'm at|bila (?:saya |aku )?(?:sampai|tiba|balik)(?: ke| di)?|masa (?:saya |aku )?sampai(?: ke| di)?)\\s+(?:the |my )?(home|house|rumah|work|office|pejabat|campus|kampus|[\\p{L}]+)$E", I)
        val EVERY_WEEKDAY = Regex("$B(every weekday|on weekdays|weekdays|setiap hari bekerja|hari bekerja|hari kerja)$E", I)
        val DAILY = Regex("$B(every ?day|everyday|daily|each day|setiap hari|tiap hari|tiap-tiap hari|every(?= (?:morning|afternoon|evening|night))|setiap(?= (?:pagi|petang|malam)))$E", I)
        val MONTHLY = Regex("$B(every month|monthly|setiap bulan|tiap bulan)$E", I)
        val WEEKLY = Regex("$B(every week|weekly|setiap minggu|tiap minggu)$E", I)
        val EVERY_DAYS = Regex("$B(?:(?:every|each|setiap|tiap|tiap-tiap)\\s+(?:hari\\s+)?(?:$DAY_WORDS)s?(?:\\s*(?:,|and|dan|&)\\s*(?:$DAY_WORDS)s?)*|on\\s+(?:mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays))$E", I)
        val WEEKDAY_WORD = Regex("$B($DAY_WORDS)s?$E", I)
        val DURATION = Regex("$B(?:for|selama)\\s+(\\d+(?:[.,]\\d+)?|an?|one|half an|half a|satu)\\s*(hours?|hrs?|h|minutes?|mins?|m|jam|minit)$E", I)
        val IN_RELATIVE = Regex("$B(?:in|dalam|lagi)\\s+(\\d+(?:[.,]\\d+)?|an?|one|half an|satu|setengah)\\s*(hours?|hrs?|minutes?|mins?|jam|minit)$E(?:\\s+(?:lagi|from now))?", I)
        val RELATIVE_AFTER = Regex("$B(\\d+(?:[.,]\\d+)?)\\s*(jam|minit|minutes?|mins?|hours?)\\s+(?:lagi|from now)$E", I)
        val IN_DAYS = Regex("$B(?:in|dalam)\\s+(\\d+)\\s*(?:days?|hari)$E(?:\\s+lagi)?|$B(\\d+)\\s*hari\\s+lagi$E", I)
        val PERIOD = Regex("$B(this morning|in the morning|morning|pagi ini|pagi ni|pagi|noon|midday|tengah hari|tengahari|tgh hari|this afternoon|in the afternoon|afternoon|petang ini|petang ni|petang|this evening|in the evening|evening|tonight|at night|night|malam ini|malam ni|malam)$E", I)
        val DAY_AFTER = Regex("$B(the day after tomorrow|day after tomorrow|lusa)$E", I)
        val TOMORROW = Regex("$B(tomorrow|tmrw|tmr|esok|besok)$E", I)
        val TODAY = Regex("$B(today|hari ini|harini|hari ni)$E", I)
        val WEEKEND = Regex("$B(?:this |on the |by the |over the )?(weekend|hujung minggu(?: ini| ni)?)$E", I)
        val NEXT_WEEK = Regex("$B(next week|minggu depan)$E", I)
        val NEXT_MONTH = Regex("$B(next month|bulan depan)$E", I)
        val ON_WEEKDAY = Regex("$B(?:(on|by|before|this|next|until|sebelum|pada|hari|by this)\\s+)?($DAY_WORDS)(\\s+(?:depan|next week))?$E", I)
        val DAY_MONTH = Regex("$B(?:on |by |before |pada |sebelum )?(\\d{1,2})(?:st|nd|rd|th)?\\s+($MONTH_WORDS)$E", I)
        val MONTH_DAY = Regex("$B(?:on |by |before )?($MONTH_WORDS)\\s+(\\d{1,2})(?:st|nd|rd|th)?$E", I)
        val NUMERIC_DATE = Regex("$B(?:on |by |before |pada |sebelum )?(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?$E", I)
        val TIME_AMPM = Regex("$B(?:at |by |around |@ ?|pukul |pkl |jam )?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)(?![\\p{L}])", I)
        val TIME_MS_PERIOD = Regex("$B(?:pukul |pkl |jam |at )?(\\d{1,2})(?:[:.](\\d{2}))?\\s+(pagi|pg|tengah hari|tengahari|tgh hari|petang|ptg|malam|mlm)$E", I)
        val TIME_24 = Regex("$B(?:at |by |pukul |pkl |jam )?([01]?\\d|2[0-3])[:.]([0-5]\\d)$E", I)
        val TIME_BARE = Regex("$B(?:at|@|pukul|pkl|jam)\\s*(\\d{1,2})(?:[:.](\\d{2}))?$E", I)
        val MIDNIGHT = Regex("$B(?:at )?(midnight|tengah malam)$E", I)

        val LEADING_FILLER = Regex("^(?:(?:and|to|that|about|for|me|i need to|i have to|i must|i should|need to|have to|i want to|please|pls|tolong|saya|aku|nak|kena|perlu|untuk|supaya|yang|on|at|by|pada|pukul|sebelum|dan)\\s+)+", I)
        val TRAILING_FILLER = Regex("(?:\\s+(?:and|at|on|by|for|before|this|the|to|in|pada|pukul|jam|sebelum|untuk|dan|ini|ni|nanti|please|pls))+$", I)

        fun weekday(w: String): DayOfWeek? = when (w.lowercase()) {
            "monday", "mon", "isnin" -> DayOfWeek.MONDAY
            "tuesday", "tue", "tues", "selasa" -> DayOfWeek.TUESDAY
            "wednesday", "rabu" -> DayOfWeek.WEDNESDAY
            "thursday", "thu", "thur", "thurs", "khamis" -> DayOfWeek.THURSDAY
            "friday", "fri", "jumaat", "jumat" -> DayOfWeek.FRIDAY
            "saturday", "sabtu" -> DayOfWeek.SATURDAY
            "sunday", "ahad" -> DayOfWeek.SUNDAY
            else -> null
        }

        fun month(w: String): Int? = when (w.lowercase().take(3)) {
            "jan" -> 1; "feb" -> 2; "mar", "mac" -> 3; "apr" -> 4; "may", "mei" -> 5; "jun" -> 6
            "jul" -> 7; "aug", "ogo" -> 8; "sep" -> 9; "oct", "okt" -> 10; "nov" -> 11; "dec", "dis" -> 12
            else -> null
        }

        private val MS_WORDS = Regex("$B(ingatkan|saya|aku|esok|besok|lusa|pukul|pkl|petang|ptg|malam|mlm|pagi|tengah|hari|minggu|bulan|isnin|selasa|rabu|khamis|jumaat|sabtu|ahad|setiap|untuk|nak|kena|beli|jangan|lupa|nanti|sebelum|minit|dalam|lagi|bila|keluar|sampai|rumah|tolong|apa|buat|ada|banyak|sangat|perlu|dengan|ke|dan|ini|ni|tak|boleh|siap|mula|habis)$E", I)
        private val EN_WORDS = Regex("$B(remind|me|tomorrow|today|at|pm|am|every|before|minutes?|the|to|my|what|do|have|need|call|buy|finish|when|leave|get|i|is|on|and|for|too|many|things|start|focus|forgot|was|doing)$E", I)

        fun detectLang(text: String): Lang {
            val ms = MS_WORDS.findAll(text).count()
            val en = EN_WORDS.findAll(text).count()
            return if (ms > 0 && ms >= en) Lang.MS else Lang.EN
        }
    }
}

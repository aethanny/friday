package com.friday.app

import com.friday.app.ai.CommandKind
import com.friday.app.ai.CommandParser
import com.friday.app.ai.Lang
import com.friday.app.ai.PlaceEvent
import com.friday.app.data.db.Repeat
import com.friday.app.domain.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Clock fixed at Wednesday 18 Nov 2026, 10:00. */
class CommandParserTest {
    private val now = LocalDateTime.of(2026, 11, 18, 10, 0)
    private val parser = CommandParser { now }
    private fun d(day: Int, month: Int = 11) = LocalDate.of(2026, month, day)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test fun reminderWithDateAndTime() {
        val p = parser.parse("Remind me tomorrow at 9 AM to submit my assignment")
        assertEquals(CommandKind.REMINDER, p.kind)
        assertEquals("Submit my assignment", p.title)
        assertEquals(d(19), p.date)
        assertEquals(t(9), p.time)
        assertFalse(p.needsTime)
    }

    @Test fun weeklyReminderWithoutTimeAsksForTime() {
        val p = parser.parse("Remind me every Monday to clean my room")
        assertEquals(CommandKind.REMINDER, p.kind)
        assertEquals("Clean my room", p.title)
        assertEquals(Repeat.WEEKLY, p.repeat)
        assertEquals(Schedule.dayBit(DayOfWeek.MONDAY), p.repeatDays)
        assertEquals(d(23), p.date)
        assertTrue(p.needsTime)
    }

    @Test fun eveningReminderToday() {
        val p = parser.parse("Remind me at 8 PM to call my mother")
        assertEquals("Call my mother", p.title)
        assertEquals(d(18), p.date)
        assertEquals(t(20), p.time)
    }

    @Test fun locationReminder() {
        val p = parser.parse("Remind me to bring my charger when I leave home")
        assertEquals(CommandKind.REMINDER, p.kind)
        assertEquals("Bring my charger", p.title)
        assertEquals(PlaceEvent.LEAVE, p.placeEvent)
        assertEquals("home", p.place)
        assertFalse(p.needsTime)
    }

    @Test fun errandEnglish() { val p = parser.parse("remind me to buy batteries next time I'm at a store"); assertEquals("Buy batteries", p.title); assertEquals("STORE", p.placeType); assertEquals(CommandKind.REMINDER, p.kind); assertFalse(p.needsTime) }
    @Test fun errandPharmacy() { assertEquals("PHARMACY", parser.parse("buy panadol when I'm at a pharmacy").placeType) }
    @Test fun errandMalay() { val p = parser.parse("ingatkan beli susu bila aku pergi pasar raya"); assertEquals("SUPERMARKET", p.placeType); assertEquals("Beli susu", p.title) }
    @Test fun errandAtTheNext() { assertEquals("PHARMACY", parser.parse("remind me to get vitamins at the next chemist").placeType) }
    @Test fun errandCurlyApostrophe() { assertEquals("STORE", parser.parse("remind me to buy tape next time I’m at a store").placeType) }
    @Test fun savedPlaceStillWins() { val p = parser.parse("remind me to call mum when I get home"); assertNull(p.placeType); assertEquals(PlaceEvent.ARRIVE, p.placeEvent) }

    @Test fun arriveAtCampus() {
        val p = parser.parse("Remind me when I get to campus to return the book")
        assertEquals(PlaceEvent.ARRIVE, p.placeEvent)
        assertEquals("campus", p.place)
        assertEquals("Return the book", p.title)
    }

    @Test fun clockTimeWithoutRemindMeIsTimedTask() {
        val p = parser.parse("Call my lecturer at 3 PM")
        assertEquals(CommandKind.TASK, p.kind)
        assertEquals("Call my lecturer", p.title)
        assertEquals(d(18), p.date)
        assertEquals(t(15), p.time)
    }

    @Test fun weekendIsSaturday() {
        val p = parser.parse("Buy shampoo this weekend")
        assertEquals(CommandKind.TASK, p.kind)
        assertEquals("Buy shampoo", p.title)
        assertEquals(d(21), p.date)
        assertNull(p.time)
    }

    @Test fun beforeFriday() {
        val p = parser.parse("Finish the poster before Friday")
        assertEquals("Finish the poster", p.title)
        assertEquals(d(20), p.date)
    }

    @Test fun ideaGoesToNotes() {
        assertEquals(CommandKind.NOTE, parser.parse("I have an idea for a new website").kind)
    }

    @Test fun reminderWithoutTimeNeedsTime() {
        val p = parser.parse("Remind me to pay my internet bill tomorrow")
        assertEquals("Pay my internet bill", p.title)
        assertEquals(d(19), p.date)
        assertTrue(p.needsTime)
    }

    @Test fun malayReminder() {
        val p = parser.parse("Ingatkan saya call mak esok pukul 8 malam")
        assertEquals(Lang.MS, p.lang)
        assertEquals(CommandKind.REMINDER, p.kind)
        assertEquals("Call mak", p.title)
        assertEquals(d(19), p.date)
        assertEquals(t(20), p.time)
    }

    @Test fun mixedMalayEnglishWithHalfHour() {
        val p = parser.parse("remind me esok pukul 3.30 petang hantar assignment")
        assertEquals(Lang.MS, p.lang)
        assertEquals("Hantar assignment", p.title)
        assertEquals(t(15, 30), p.time)
        assertEquals(d(19), p.date)
    }

    @Test fun malayNextWeekFriday() {
        val p = parser.parse("Jumpa doktor jumaat depan pukul 10 pagi")
        assertEquals("Jumpa doktor", p.title)
        assertEquals(d(27), p.date)
        assertEquals(t(10), p.time)
    }

    @Test fun durationAndTime() {
        val p = parser.parse("TikTok LIVE at 9pm for 1 hour")
        assertEquals("TikTok LIVE", p.title)
        assertEquals(t(21), p.time)
        assertEquals(60, p.durationMin)
    }

    @Test fun relativeMinutes() {
        val p = parser.parse("Remind me in 20 minutes to stretch")
        assertEquals("Stretch", p.title)
        assertEquals(d(18), p.date)
        assertEquals(t(10, 20), p.time)
    }

    @Test fun unknownStaysUnknown() {
        assertEquals(CommandKind.UNKNOWN, parser.parse("Ask my lecturer about the assignment").kind)
    }

    @Test fun bareHourAlreadyPassedMeansEvening() {
        val p = parser.parse("Call lecturer at 9")
        assertEquals(t(21), p.time)
        assertEquals(d(18), p.date)
    }

    @Test fun dailyAtSevenStartsTomorrow() {
        val p = parser.parse("Remind me every day at 7am to take vitamins")
        assertEquals(Repeat.DAILY, p.repeat)
        assertEquals("Take vitamins", p.title)
        assertEquals(t(7), p.time)
        assertEquals(d(19), p.date)
    }

    @Test fun importantWithNumericDate() {
        val p = parser.parse("Important: submit report by 25/12")
        assertTrue(p.important)
        assertEquals("Submit report", p.title)
        assertEquals(d(25, 12), p.date)
    }

    @Test fun tomorrowMorningPeriod() {
        val p = parser.parse("Remind me to water the plants tomorrow morning")
        assertEquals("Water the plants", p.title)
        assertEquals(d(19), p.date)
        assertEquals(t(9), p.time)
    }

    @Test fun malayEveningPeriodWithoutNumber() {
        val p = parser.parse("ingatkan saya beli barang dapur petang ni")
        assertEquals(Lang.MS, p.lang)
        assertEquals("Beli barang dapur", p.title)
        assertEquals(d(18), p.date)
        assertEquals(t(16), p.time)
    }

    @Test fun eventWithSimpleTitle() {
        val p = parser.parse("I've license lesson at 28 Nov, 8.00 am")
        assertEquals(CommandKind.TASK, p.kind)
        assertEquals("License lesson", p.title)
        assertEquals(d(28), p.date)
        assertEquals(t(8), p.time)
        assertTrue(p.steps.isEmpty())
    }

    @Test fun eventWithThingsToDoBeforeGoing() {
        val p = parser.parse("I have license lesson on 28 Nov 8am, remind me before I go to bring my IC and L-plate and check all the switches in my house")
        assertEquals(CommandKind.TASK, p.kind)
        assertEquals("License lesson", p.title)
        assertEquals(d(28), p.date)
        assertEquals(t(8), p.time)
        assertEquals(listOf("Bring my IC and L-plate", "Check all the switches in my house"), p.steps)
    }

    @Test fun leadingReminderIsNotSplit() {
        val p = parser.parse("Remind me at 5pm to call mum")
        assertEquals(CommandKind.REMINDER, p.kind)
        assertEquals("Call mum", p.title)
        assertTrue(p.steps.isEmpty())
    }

    @Test fun malayEventWithChecklist() {
        val p = parser.parse("Saya ada kelas memandu 28 Nov pukul 8 pagi, ingatkan saya bawa IC dan tutup semua suis")
        assertEquals("Kelas memandu", p.title)
        assertEquals(t(8), p.time)
        assertEquals(listOf("Bawa IC", "Tutup semua suis"), p.steps)
    }

    @Test fun prepLeadAnswers() {
        assertEquals(15, parser.parseLead("15 min"))
        assertEquals(60, parser.parseLead("1 h"))
        assertEquals(90, parser.parseLead("1.5 hours before"))
        assertEquals(30, parser.parseLead("30 minit"))
        assertEquals(90, parser.parseLead("1 h 30 min"))
        assertEquals(null, parser.parseLead("buy milk in 2 hours"))

    }

    @Test fun spokenPrepAnswers() {
        assertEquals(120, parser.parseLead("two hours"))
        assertEquals(30, parser.parseLead("thirty minutes"))
        assertEquals(45, parser.parseLead("maybe forty-five minutes"))
        assertEquals(15, parser.parseLead("lima belas minit"))
        assertEquals(120, parser.parseLead("dua jam lah"))
        assertEquals(60, parser.parseLead("okay", suggested = 60))
        assertEquals(90, parser.parseLead("boleh", suggested = 90))
        assertEquals(null, parser.parseLead("okay")) // nothing was suggested
        assertEquals(0, parser.parseLead("no thanks"))
        assertEquals(0, parser.parseLead("No alarm"))
        assertEquals(0, parser.parseLead("Tiada penggera"))
        assertEquals(null, parser.parseLead("buy milk tomorrow"))
    }
}

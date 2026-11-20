# Meaningful Reminders + Memory + Place Awareness (v1.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix known logic bugs, then give FRIDAY a memory of the user, better AI reasoning, smarter reminder timing, meaningful reminder text, and place awareness that shapes reminders.

**Architecture:** Everything that decides *when* and *whether* a reminder appears stays on the phone in `ReminderEngine` plus small pure helpers in `domain/` (unit-testable). The online AI only writes chat replies, memory entries and pre-written reminder lines; nothing waits on it at fire time. One Room auto-migration (v1→v2) adds the new tables/columns without touching existing data.

**Tech Stack:** Kotlin, Compose, Room 2.8.5 (AutoMigration), DataStore, OkHttp 5, kotlinx.serialization, Play services location 21.4.0 (geofencing + ActivityRecognition), OpenStreetMap Overpass API.

**Spec:** `docs/superpowers/specs/2026-11-20-meaningful-reminders-design.md`

## Global Constraints

- Room schema change only via `AutoMigration(from = 1, to = 2)`; never `fallbackToDestructiveMigration`. Export schema `2.json`.
- Device tests run with `am instrument` only, never `connectedAndroidTest` (wipes the user's real data).
- Notification body max 2 lines (≤ 140 chars total); max 2 action buttons; max 1 follow-up nudge.
- No reminder ever waits on a network call. AI lines are prepared ahead; offline must behave at least as well as v1.0.
- Data sent to the AI: v1.0 data + memory list. Never notes, never subtask text, never locations.
- Only data sent to OpenStreetMap: one rounded (4 decimals) lat/lng per detected stop.
- Every user-visible string in `values/strings.xml` AND `values-ms/strings.xml`. Tone supportive, never shaming.
- Place awareness defaults OFF.
- Unit tests: `.\gradlew.bat testDebugUnitTest`. Build: `.\gradlew.bat assembleDebug`.
- Commit after each task; message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Focus ends early / is extended** while reminders are held → held reminders arrive right after the session ends (not at the old planned end), and re-hold to the new end if extended. Test in Task 5.
2. **Focus and a hold place both want Do Not Disturb** → leaving one must not switch the phone back to loud while the other still needs quiet. Test in Task 9 (`DndReasonsTest`).
3. **Leave detection never fires** (GPS off, phone died) → held reminders still arrive at most 3 h later. Test in Task 9.
4. **Upgrade with real data** → all v1 tasks/cues/notes survive the v1→v2 migration. Test in Task 2.
5. **AI offline or returns junk for reminder lines** → reminders use offline wording; a stale AI line for a previous occurrence is never shown. Test in Task 7.

## File Structure

| File | Responsibility |
|---|---|
| `domain/Logic.kt` (modify) | Monthly repeat anchor fix |
| `domain/Smart.kt` (create) | Pure helpers: `Habits`, `ReminderWhy`, `PlaceKinds`, `DndReasons` |
| `data/db/Entities.kt`, `Daos.kt`, `FridayDatabase.kt` (modify) | `MemoryEntity`, `SnoozeLogEntity`, new cue columns, `MemoryDao`, v2 |
| `data/Repositories.kt` (modify) | `MemoryRepository`, bug fixes, `onSaved` hook |
| `reminders/Reminders.kt` (modify) | hold / release / skip / move / snooze log |
| `reminders/Notifier.kt` (modify) | 2-line body, 2 buttons, bundling, place notifications |
| `ai/Online.kt` (modify) | memory in prompt, `REMEMBER`, `think`, `prepareLines` |
| `ai/CommandParser.kt`, `ai/Assistant.kt` (modify) | errand ("next time I'm at a pharmacy") reminders |
| `location/Awareness.kt` (create) | stillness → lookup → visit state → place actions |
| `focus/Focus.kt` (modify) | `Dnd` reasons, release held reminders on end |
| `ui/settings/MemoryScreen.kt` (create) | "What FRIDAY knows about me" |

---

### Task 1: Bug sweep fixes

Bugs found while reading v1.0 (record each in `docs/DECISIONS.md` under a new "v1.1 fixes" heading):

- **B1** A snoozed *place* reminder loses its snooze alarm whenever `ReminderScheduler.sync` runs (every app start), because `trigger == null` → `cancel()`.
- **B2** Monthly repeat drifts: due Jan 31 → Feb 28 → Mar 28 forever.
- **B3** Repeating tasks keep `postponeCount` forever, so one old postponement keeps them "postponed" (ranking + reason) for every future occurrence.
- **B4** `AiActions.validate` checks `CREATE_REMINDER` without `trim()`, so `" CREATE_REMINDER"` with no time creates a timeless task.
- **B5** Deleting a task with a place reminder (incl. Undo of a created one) leaves its geofence registered until the next sync.

**Files:**
- Modify: `app/src/main/java/com/friday/app/domain/Logic.kt` (`Schedule.step`), `data/Repositories.kt` (`save`, `complete`, `delete`), `reminders/Reminders.kt` (`ReminderScheduler.sync`), `ai/Online.kt` (`AiActions.validate`)
- Test: `app/src/test/java/com/friday/app/LogicTest.kt`, `app/src/test/java/com/friday/app/OnlineAiTest.kt`, `app/src/androidTest/java/com/friday/app/DeviceTests.kt`

**Interfaces:**
- Produces: for `Repeat.MONTHLY`, `TaskEntity.repeatDays` holds the anchor day-of-month (1–31); 0 means "use current dueDate's day" (old tasks).

- [ ] **Step 1: Write failing tests**

```kotlin
// LogicTest
@Test fun monthlyKeepsAnchorDay() {
    val t = task(due = LocalDate.of(2027, 1, 31), repeat = Repeat.MONTHLY, days = 31)
    val feb = Schedule.nextOccurrence(t, LocalDate.of(2027, 1, 31))
    assertEquals(LocalDate.of(2027, 2, 28), feb)
    assertEquals(LocalDate.of(2027, 3, 31), Schedule.nextOccurrence(t.copy(dueDate = feb), feb!!))
}
// OnlineAiTest
@Test fun reminderWithoutTimeRejectedEvenWithSpaces() {
    assertNull(AiActions.validate(AiAction(type = " CREATE_REMINDER ", title = "x"), emptySet(), LocalDate.of(2026, 11, 20)))
}
// DeviceTests
@Test fun snoozedPlaceReminderSurvivesResync()      // addPlaceCue, set snoozedUntil = now+10min via engine.snooze, scheduler.syncAll(false); assert PendingIntent FLAG_NO_CREATE for ACTION_FIRE/cue id is non-null
@Test fun completingRepeatingTaskResetsPostpones() // save daily task, postpone(id,1) twice, complete(id); assertEquals(0, get(id)!!.postponeCount)
@Test fun monthlySaveStoresAnchorDay()              // save MONTHLY with dueDate day 31, repeatDays 0 → stored repeatDays == 31
```

- [ ] **Step 2: Run** `.\gradlew.bat testDebugUnitTest` → the two unit tests FAIL.
- [ ] **Step 3: Fix**
  - B1: in `sync`, when `trigger == null`: if `cue.snoozedUntil != null && cue.snoozedUntil > now` → `set(cue.id, cue.snoozedUntil)` else `cancel(cue.id)`.
  - B2: `TaskRepository.save` sets `repeatDays = dueDate.dayOfMonth` for MONTHLY when `repeatDays == 0`. `step` for MONTHLY: `val m = d.plusMonths(1); m.withDayOfMonth(minOf(anchor, m.lengthOfMonth()))` where `anchor = if (days in 1..31) days else d.dayOfMonth`.
  - B3: `complete()` repeating branch updates with `postponeCount = 0`.
  - B4: compute `val type = a.type.trim().uppercase(Locale.ROOT)` once and use it everywhere in `validate`.
  - B5: `delete()` calls `scheduler.syncPlaces()` (new: `suspend fun syncPlaces() = places.registerAll()`) when any deleted cue has `placeId != null`.
- [ ] **Step 4: Run** unit tests → PASS. Build and run device tests (README commands) → PASS.
- [ ] **Step 5: Commit** `Fix v1.0 logic bugs: place snooze, monthly drift, stale postpones, AI type check, geofence cleanup`

---

### Task 2: Schema v2 (memory, snooze log, smart cue columns)

**Files:**
- Modify: `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/FridayDatabase.kt`, `app/build.gradle.kts` + `gradle/libs.versions.toml` (add `androidTestImplementation(libs.androidx.room.testing)` = `androidx.room:room-testing` at the `room` version, and `sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")` so `MigrationTestHelper(instrumentation, FridayDatabase::class.java)` finds `1.json`)
- Create: `app/src/androidTest/java/com/friday/app/MigrationTest.kt`

**Interfaces (Produces):**
```kotlin
enum class MemorySource { USER, CHAT, HABIT }
@Entity(tableName = "memories") data class MemoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val text: String, val source: MemorySource, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "snooze_log", foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = CASCADE)], indices = [Index("taskId")])
data class SnoozeLogEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val at: Long, val targetMin: Int /* minute of day snoozed to */)
// CueEntity new columns (all with @ColumnInfo(defaultValue) so AutoMigration works):
val smartText: String? = null      // AI-prepared line
val smartKey: Long? = null         // occurrence key smartText was written for
val held: Boolean = false          // waiting for focus end / leaving a hold place
val placeType: String? = null      // errand reminders: "STORE" | "PHARMACY" | "SUPERMARKET"
@Dao interface MemoryDao { fun observeAll(): Flow<List<MemoryEntity>>; suspend fun all(): List<MemoryEntity>; @Insert suspend fun insert(m: MemoryEntity): Long; @Update suspend fun update(m: MemoryEntity); @Delete suspend fun delete(m: MemoryEntity); @Query("SELECT * FROM memories WHERE id = :id") suspend fun get(id: Long): MemoryEntity? }
// TaskDao additions:
@Insert suspend fun logSnooze(e: SnoozeLogEntity)
@Query("SELECT * FROM snooze_log WHERE taskId = :taskId AND at >= :since ORDER BY at DESC") suspend fun snoozesFor(taskId: Long, since: Long): List<SnoozeLogEntity>
@Query("DELETE FROM snooze_log WHERE taskId = :taskId") suspend fun clearSnoozes(taskId: Long)
@Query("SELECT * FROM cues WHERE held = 1") suspend fun heldCues(): List<CueEntity>
// FridayDatabase: version = 2, autoMigrations = [AutoMigration(from = 1, to = 2)], abstract fun memories(): MemoryDao
```

- [ ] **Step 1: Write failing test** `MigrationTest.v1DataSurvives`: create v1 DB via helper, insert one task (id 7, title "Gym", dueDate epochDay, dueTime 1140), one cue, one note with raw SQL; `runMigrationsAndValidate(name, 2, true)`; assert task row, cue row (`held = 0`, `smartText IS NULL`), note row still present, and `memories` table exists (empty).
- [ ] **Step 2: Run** `.\gradlew.bat assembleDebugAndroidTest` → compile FAIL (no v2).
- [ ] **Step 3: Implement** the interfaces above; build generates `schemas/.../2.json` (commit it).
- [ ] **Step 4: Run** device tests via `am instrument` → PASS (MigrationTest + existing).
- [ ] **Step 5: Commit** `Add schema v2: memories, snooze log, smart reminder columns`

---

### Task 3: Memory repository + "What FRIDAY knows about me" screen

**Files:**
- Modify: `data/Repositories.kt` (add `MemoryRepository`), `FridayApp.kt` (`val memory = MemoryRepository(db)`), `ui/FridayRoot.kt` (route `settings/memory`), `ui/settings/SettingsScreen.kt` (row in Assistant section), strings EN/MS, privacy "delete all data" path must clear memories (check `SettingsViewModel`)
- Create: `ui/settings/MemoryScreen.kt`
- Test: `DeviceTests.kt`

**Interfaces (Produces):**
```kotlin
class MemoryRepository(db: FridayDatabase) {
    fun observeAll(): Flow<List<MemoryEntity>>
    suspend fun all(): List<MemoryEntity>
    /** Trims to 200 chars; returns null if blank or a case-insensitive duplicate exists, or when 100 memories exist. */
    suspend fun add(text: String, source: MemorySource): Long?
    suspend fun edit(m: MemoryEntity, text: String)
    suspend fun delete(m: MemoryEntity)
    suspend fun restore(m: MemoryEntity)
}
```

- [ ] **Step 1: Failing device tests** `memoryDedupesAndTrims` (add "Hates long messages" twice, second differs only in case → second returns null; 250-char text stored as 200) and `memoryCap` (100 adds succeed, 101st returns null).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** repo + screen: list of memories (source shown as small caption "You" / "From chat" / "Habit"), tap to edit in a sheet, swipe or trash icon to delete with Undo snackbar (existing `Toaster` pattern), text field + Add at top. Use existing `Components.kt` rows/sheets; empty state: "Nothing yet. Tell FRIDAY things like “I crash after 10 PM”."
- [ ] **Step 4: Run** tests → PASS; `assembleDebug` OK.
- [ ] **Step 5: Commit** `Add FRIDAY memory and its settings screen`

---

### Task 4: AI uses memory, can REMEMBER, and thinks first

**Files:**
- Modify: `ai/Online.kt` (`AiReply`, `Action`, `AiActions.validate`, `OnlineAssistant.apply`, `systemPrompt`, constructor gets `memory: MemoryRepository`), `ai/Assistant.kt` (`Undo.DeleteMemory(id: Long)` + handling in `undo`), `FridayApp.kt`, strings (`remembered_line` = "Remembered: %1$s" / MS "Diingat: %1$s")
- Test: `OnlineAiTest.kt`

**Interfaces (Produces):**
```kotlin
@Serializable data class AiReply(val think: String = "", val reply: String = "", val actions: List<AiAction> = emptyList())
sealed interface Action { …; data class Remember(val text: String) : Action }   // from {"type":"REMEMBER","text":str}, text cleaned to ≤200
```

- [ ] **Step 1: Failing tests**
```kotlin
@Test fun rememberValidated() {
    assertEquals(Action.Remember("Gym after 7 PM"), AiActions.validate(AiAction(type = "REMEMBER", text = "  Gym after 7 PM "), emptySet(), today))
    assertNull(AiActions.validate(AiAction(type = "REMEMBER", text = "  "), emptySet(), today))
}
@Test fun thinkIsParsedButNotReply() {
    val r = AiActions.parse("""{"think":"user is tired","reply":"Rest first.","actions":[]}""")!!
    assertEquals("Rest first.", r.reply)
}
```
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**
  - `apply`: `Action.Remember` → `memory.add(text, CHAT)`; if id != null add `Undo.DeleteMemory(id)` and append `"\n\n" + remembered_line` to the reply text. `think` is never shown or stored.
  - `systemPrompt` additions (keep existing rules):
    - "What you know about the user:\n" + memories as `- text` lines (or "(nothing yet)").
    - Tasks postponed ≥2 times listed with count; last focus session title + outcome if within 24 h.
    - Output: `{"think": string, "reply": string, "actions": [...]}` — "think first, privately: what time it is, what they're doing, their energy, what they keep avoiding, what you know about them; then reply with ONE recommendation, not a menu."
    - `{"type":"REMEMBER","text":str}`: "a lasting fact about the user's routine or preferences they told you. Never health, diagnosis, medication, religion or relationships unless they explicitly ask you to remember it. Don't remember one-off tasks."
    - "Follow what you know (e.g. if they hate long messages, be shorter)."
- [ ] **Step 4: Run** unit tests → PASS. **Step 5: Commit** `AI remembers the user and thinks before replying`

---

### Task 5: Smart timing — hold during focus, skip if started, bundle

**Files:**
- Modify: `reminders/Reminders.kt` (`ReminderEngine.fire`, new `releaseHeld`, `acknowledge`), `focus/Focus.kt` (`FocusController` gets `onEnded: suspend () -> Unit = {}`, called at end of `end()` and `timeUp()` after side effects), `FridayApp.kt` (wire `onEnded = { reminders.releaseHeld() }` — construct focus after reminders), `reminders/Notifier.kt` (bundling)
- Test: `DeviceTests.kt`

**Interfaces (Produces):**
```kotlin
// ReminderEngine
suspend fun releaseHeld(now: Long = System.currentTimeMillis())   // every held cue: held=false, snoozedUntil=null… then fire(cue.id, now)
// Notifier
fun postReminder(t: TaskEntity, c: CueEntity, channel: String, followUp: Boolean, body: String, second: SecondAction, bundle: Boolean)
sealed interface SecondAction { data object SnoozePicker : SecondAction; data class MoveTo(val time: LocalTime) : SecondAction }
```

Until Task 7, callers pass `body = Fmt.cueMessage(ctx, t, c)` and `second = SnoozePicker`.

Rules inside `fire()` (in this order, after the existing completed/early/duplicate checks):
1. **Skip if started** — only for `CueKind.PREP` and `CueKind.START`: if the active focus session's `taskId == task.id`, or `task.lastActiveAt` is within 30 min of `now` → record `firedKey`/`firedAt`, post nothing, no follow-up. (`acknowledge()` must stop calling `dao.touch`, so tapping "Got it" on "get ready" doesn't count as starting; record this in DECISIONS.)
2. **Hold during focus** — if `FocusMath.silencesOthers(focus, now)` and `focus.taskId != task.id` and not (`important && Matrix.isUrgent`): update cue `held = true, snoozedUntil = FocusMath.endsAt(focus)`, `scheduler.set(cue.id, endsAt)`, return. When that alarm fires and the session was extended, rule 2 re-holds to the new end.
3. Otherwise post. `channelFor` no longer returns QUIET for focus (only for quiet hours).
4. **Bundle** — `bundle = true` when another FRIDAY reminder notification is still showing that was posted < 2 min ago (`NotificationManager.activeNotifications`, tag/ID range 10_000+, `postTime`). Bundled children are `setSilent(true)` and all reminders use `setGroup("friday.reminders")`; when ≥2 are showing, post/refresh a summary (`setGroupSummary(true)`, `InboxStyle`, title from plural `bundle_title` "%d things now" / MS "%d perkara sekarang", silent).

- [ ] **Step 1: Failing device tests** (use `c.reminders`, `c.focus`, ids ≥ 1,000,000, fixed `now`)
  - `reminderForOtherTaskIsHeldDuringFocusAndReleasedOnEnd`: start focus on A (25 min); fire cue of B → cue `held == true`, `firedKey == null`; `focus.end(…)` → cue `held == false`, `firedKey != null`.
  - `heldReminderReHoldsWhenFocusExtended`: hold B; `addMinutes(+10)`; `fire(B cue, now = old end)` → still held, `snoozedUntil == new end`.
  - `importantUrgentPassesThroughFocus`: B important, due today → not held.
  - `startReminderSkippedWhenFocusingOnThatTask` and `prepSkippedWhenTaskTouched10MinAgo`: → `firedKey` set, no notification posted (assert `notifier` active notifications for that id absent).
  - `gotItDoesNotCountAsStarted`: acknowledge PREP → `lastActiveAt` unchanged.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** rules above. **Step 4: Run** device tests → PASS.
- [ ] **Step 5: Commit** `Hold reminders during focus, skip ones already started, bundle bursts`

---

### Task 6: Learn snooze habits ("Move to 7 PM")

**Files:**
- Create: `domain/Smart.kt` (object `Habits`)
- Modify: `reminders/Reminders.kt` (`snooze`/`postponeToTomorrow` log; new `moveTo`; `ReminderReceiver` `ACTION_MOVE` with `Extras.MINUTES` = minute of day), `reminders/Notifier.kt` (render `SecondAction.MoveTo` label `move_to` "Move to %1$s" / MS "Alih ke %1$s")
- Test: `app/src/test/java/com/friday/app/SmartTest.kt`, `DeviceTests.kt`

**Interfaces (Produces):**
```kotlin
object Habits {
    /** For a repeating, timed task: the time it keeps getting snoozed to, or null.
     *  Needs ≥3 entries in the last 30 days, the last 3 within ±30 min of their median,
     *  result rounded to the nearest 15 min, and ≥30 min away from [current]. */
    fun learnedMove(log: List<SnoozeLogEntity>, current: LocalTime, now: Long): LocalTime?
}
// ReminderEngine
suspend fun moveTo(cueId: Long, time: LocalTime)  // tasks.save(task.copy(dueTime = time)) (no postpone count), clearSnoozes, memory.add("Usually does “<title>” around <time>", HABIT)
```
`ReminderEngine` constructor gains `memory: MemoryRepository`. `snooze()` logs `targetMin` = minute-of-day of `until`; `postponeToTomorrow` logs nothing (it's a day move, not a time habit).

- [ ] **Step 1: Failing unit tests**
```kotlin
@Test fun learnsConsistentSnoozeTime()   // logs at 19:05, 18:50, 19:10 (last 30d), current 17:00 → 19:00
@Test fun ignoresScatteredSnoozes()      // 19:00, 21:30, 15:00 → null
@Test fun needsThree()                   // two entries → null
@Test fun ignoresOldEntries()            // three 19:00 entries 40 days old → null
@Test fun ignoresIfAlreadyNearCurrent()  // 17:10, 17:05, 17:15 with current 17:00 → null
```
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** `Habits`, logging, `moveTo`, and in `fire()` choose `SecondAction.MoveTo(t)` when the task repeats, has `dueTime`, and `Habits.learnedMove(...)` is non-null; else `SnoozePicker`.
- [ ] **Step 4: Device test** `moveToChangesTimeAndRemembers`: daily task 17:00, `moveTo(cue, 19:00)` → `dueTime == 19:00`, `postponeCount` unchanged, a HABIT memory exists, snooze log empty. Run all → PASS.
- [ ] **Step 5: Commit** `Learn snooze habits and offer to move the time`

---

### Task 7: Meaningful text (offline why-lines + AI-prepared lines) and 2 buttons

**Files:**
- Modify: `domain/Smart.kt` (object `ReminderWhy`), `ui/Fmt.kt` (`reminderBody`), `reminders/Notifier.kt` (buttons), `reminders/Reminders.kt` (pass body), `ai/Online.kt` (`prepareLines`), `data/Repositories.kt` (`var onSaved: (Long) -> Unit = {}` called at end of `save`), `FridayApp.kt` (wire `tasks.onSaved = { scope.launch { runCatching { ai.prepareLines() } } }`; also call on app start), `reminders/Briefing.kt` (`BriefingReceiver` calls `ai.prepareLines()` after posting), strings EN/MS
- Test: `SmartTest.kt`, `OnlineAiTest.kt`

**Interfaces (Produces):**
```kotlin
sealed interface Why { data class Postponed(val times: Int) : Why; data class FirstStep(val step: String) : Why; data class Next(val title: String, val at: LocalTime) : Why }
object ReminderWhy {
    /** First match wins: postponeCount ≥ 2 → Postponed; PREP/START with an open step → FirstStep(first open by position);
     *  END with another open task starting later today within 3 h → Next(earliest). Else null. */
    fun pick(task: TaskWithDetails, cue: CueEntity, others: List<TaskEntity>, now: LocalDateTime): Why?
}
object Fmt { fun reminderBody(ctx: Context, t: TaskEntity, c: CueEntity, why: Why?, occurrenceKey: Long?): String }
// = c.smartText if c.smartKey == occurrenceKey && occurrenceKey != null; else cueMessage(...) + (why?.let { "\n" + text }), total ≤ 140 chars
class OnlineAssistant { suspend fun prepareLines(now: LocalDateTime = now()) }
```
Strings: `why_postponed` "Moved %1$d times. Just 5 minutes counts." / MS "Dah dialih %1$d kali. 5 minit pun dah kira."; `why_first_step` "First step: %1$s" / "Langkah pertama: %1$s"; `why_next` "Next: %1$s at %2$s" / "Seterusnya: %1$s pukul %2$s".

`prepareLines`: no-op unless `available()`. Collect open tasks' time-based cues whose trigger is within the next 24 h and whose `smartKey != Schedule.key(trigger)`; max 20 per call. One request (app's language): system = memory list + "Write one reminder line per item, ≤ 90 characters, warm, specific, says why it matters now, no shame, no emojis." user = JSON list `{cueId, task, kind, at, postponedTimes, important}`. Response `{"lines":[{"cueId":n,"text":"..."}]}` parsed with `ignoreUnknownKeys`; keep only requested cueIds, text trimmed to 90 chars, non-blank; store `smartText` + `smartKey`. Any `AiException` → return silently.

Buttons: main as v1.0 (Start focus / Mark done / Got it); second = `MoveTo` or "Snooze…" picker. Remove the fixed "Snooze N min" action (picker keeps 5/15/60 min/Tomorrow). `defaultSnoozeMin` setting stays for the AI's snooze and the picker's first row.

- [ ] **Step 1: Failing unit tests**
  - `whyPostponedWins`, `whyFirstStepForStart`, `whyNextForEnd`, `whyNullOtherwise`.
  - `OnlineAiTest.prepareLinesIgnoresUnknownIdsAndTrims` with MockWebServer (existing pattern in this file): reply contains a cueId not requested and a 200-char line → only requested id stored, 90 chars.
  - `staleSmartTextNotUsed`: `Fmt.reminderBody` with `smartKey` of another occurrence returns offline text. (Needs `Context`: put this one in DeviceTests instead.)
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** unit + device tests → PASS.
- [ ] **Step 5: Commit** `Reminders say why they matter, in two lines and two buttons`

---

### Task 8: Errand reminders ("next time I'm at a pharmacy")

**Files:**
- Modify: `ai/CommandParser.kt` (`Parsed.placeType: String?`, regex `ERRAND`), `ai/Assistant.kt` (`placeReminder` handles `placeType`), `data/Repositories.kt` (`addPlaceTypeCue(taskId, type)`), `reminders/Notifier.kt` (`isPrimary` also true when `c.placeType != null && t.dueDate == null`), `ui/Fmt.kt` (`cueWhen` for ARRIVE with placeType: `cue_at_type` "Next time you're at a %1$s"), task editor reminder list shows it (read-only row is enough), strings EN/MS (`place_type_store` store/kedai, `place_type_pharmacy` pharmacy/farmasi, `place_type_supermarket` supermarket/pasar raya, `errand_set` "Okay. I'll remind you to %1$s next time you're at a %2$s." — plus "Turn on place awareness in Settings › Places" suffix when it's off)
- Test: `CommandParserTest.kt`

**Interfaces (Produces):** `placeType` values `"STORE"`, `"PHARMACY"`, `"SUPERMARKET"`. Cue: `anchor = ARRIVE, placeId = null, placeType = type, kind = CUSTOM`.

- [ ] **Step 1: Failing parser tests**
```kotlin
@Test fun errandEnglish() { val p = parser.parse("remind me to buy batteries next time I'm at a store"); assertEquals("buy batteries", p.title); assertEquals("STORE", p.placeType) }
@Test fun errandPharmacy() { assertEquals("PHARMACY", parser.parse("buy panadol when I'm at a pharmacy").placeType) }
@Test fun errandMalay() { val p = parser.parse("ingatkan beli susu bila aku pergi pasar raya"); assertEquals("SUPERMARKET", p.placeType); assertEquals("beli susu", p.title) }
@Test fun savedPlaceStillWins() { assertNull(parser.parse("remind me to call mum when I get home").placeType) }
```
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** (`kind = REMINDER` when placeType set; words: store/shop/kedai → STORE, pharmacy/chemist/farmasi → PHARMACY, supermarket/grocery/pasar raya/mydin/lotus's/aeon? — no brand names; generic words only).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `Add errand reminders for any store, pharmacy or supermarket`

---

### Task 9: Place awareness (stillness → lookup → actions)

**Files:**
- Create: `location/Awareness.kt` (`PlaceAwareness`, `StillnessReceiver`, `VisitCheckReceiver`, `PlaceActionReceiver`, `OverpassClient`)
- Modify: `domain/Smart.kt` (`PlaceKinds`, `DndReasons`), `focus/Focus.kt` (`Dnd.enable(ctx, reason)` / `Dnd.restore(ctx, reason)` using `DndReasons`), `location/Places.kt` (`GeofenceReceiver` handles request id `"visit"` EXIT → `awareness.left()`), `reminders/Reminders.kt` (`fire()` hold-place rule), `data/Settings.kt` (`placeAwareness: Boolean = false`, `placeOffKinds: Set<String>`, `placeUndos: Set<String>` encoded `"KIND:n"`), `ui/settings/PlacesScreen.kt` (toggle + permission flow: ACTIVITY_RECOGNITION on API 29+, background location reuse), `AndroidManifest.xml` (`ACTIVITY_RECOGNITION`, `com.google.android.gms.permission.ACTIVITY_RECOGNITION` for API ≤28, new receivers non-exported), `FridayApp.kt`, `SystemEventsReceiver` (re-register stillness after boot), strings EN/MS
- Test: `SmartTest.kt`, `DeviceTests.kt`

**Interfaces (Produces):**
```kotlin
enum class PlaceKind { HOLD, STUDY, STORE, PHARMACY, SUPERMARKET, HOME, NONE }
object PlaceKinds {
    /** OSM tags → kind. amenity=place_of_worship|cinema|theatre|hospital|clinic|doctors → HOLD; amenity=library → STUDY;
     *  amenity=pharmacy or shop=chemist → PHARMACY; shop=supermarket|convenience → SUPERMARKET; any other shop=* → STORE; else NONE.
     *  With several elements, the first by priority HOLD > PHARMACY > SUPERMARKET > STUDY > STORE wins. */
    fun classify(elements: List<Map<String, String>>): PlaceKind
    /** Errand cue placeType matches: STORE matches STORE/PHARMACY/SUPERMARKET; others match only themselves. */
    fun matches(placeType: String, kind: PlaceKind): Boolean
}
object DndReasons { fun add(current: Set<String>, r: String): Set<String>; fun remove(current: Set<String>, r: String): Set<String>; fun shouldRestore(after: Set<String>): Boolean = after.isEmpty() }
class PlaceAwareness(ctx, db, settings, places: PlaceManager, reminders: ReminderEngine, notifier: Notifier, tasks: TaskRepository, memory: MemoryRepository, overpass: OverpassClient) {
    suspend fun enable(); suspend fun disable()          // (un)register ActivityRecognition STILL enter/exit transitions
    suspend fun stillFor3Min()                            // the visit check (below)
    suspend fun left(now: Long = System.currentTimeMillis())
    fun holding(): Boolean                                // current visit is HOLD and not turned off
    suspend fun undo()                                    // from the "Went silent · Undo" action
}
class OverpassClient(http: OkHttpClient) { suspend fun tagsNear(lat: Double, lng: Double): List<Map<String, String>> } // POST https://overpass-api.de/api/interpreter, around:60, out tags 10; lat/lng rounded to 4 decimals; timeout 10 s; failure → emptyList()
```

Flow:
- STILL ENTER → set inexact alarm in 3 min (`VisitCheckReceiver`). STILL EXIT → cancel that alarm (leaving is decided by the visit geofence, not by stillness).
- `stillFor3Min()`: skip if a visit is already active. `places.here()`; saved place within its radius → kind HOME if name is home/rumah, else NONE (saved-place reminders already handled by v1.0 geofences); otherwise `PlaceKinds.classify(overpass.tagsNear(...))`. If kind != NONE: store visit (kind, lat, lng, startedAt) in a SharedPreferences `VisitStore`, register geofence `"visit"` (EXIT, radius 150 m), then act:
  - HOLD (not in `placeOffKinds`): `Dnd.enable(ctx, "place")`; post quiet notification "Went silent here" + action Undo. Reminders now hold (below).
  - STUDY: one notification "At the library. Focus on <rankForNow first>?" + action Start focus (route `focus/<id>`).
  - STORE/PHARMACY/SUPERMARKET: fire every open errand cue where `PlaceKinds.matches(placeType, kind)` (respecting `COOLDOWN_MS` via `firedAt`).
  - HOME: if hour ≥ 17, open tasks due today exist, and not shown today → one notification "Left today: A, B" (max 3 titles).
- `fire()` hold-place rule (after the focus rule): if `awareness.holding()` and not important-and-urgent → `held = true`, `snoozedUntil = min(now + 3 h, …)`, alarm at that time (safety cap if leave is never detected).
- `left()`: clear visit, remove `"visit"` geofence, `Dnd.restore(ctx, "place")`, `reminders.releaseHeld()` — only release cues whose hold was for the place: simplest correct rule is `releaseHeld()` then rule 2 of Task 5 re-holds those still under a running focus.
- `undo()`: `Dnd.restore(ctx, "place")`, `releaseHeld()`, increment undo count for that kind; at 2 → add to `placeOffKinds` and `memory.add("Doesn't want FRIDAY to go silent at <kind label>", HABIT)`. Visit stays active but `holding()` becomes false.

- [ ] **Step 1: Failing unit tests** (`SmartTest`)
  - `classifyMosqueIsHold`, `classifyPharmacyBeatsShop` (elements shop=convenience + amenity=pharmacy → PHARMACY), `classifyEmptyIsNone`, `storeMatchesPharmacy`, `pharmacyDoesNotMatchStore`.
  - `DndReasonsTest`: add "focus", add "place", remove "focus" → `shouldRestore == false`; remove "place" → true.
- [ ] **Step 2: Failing device tests**
  - `holdPlaceHoldsAndLeaveReleases`: set visit HOLD via `VisitStore` test hook; fire cue → held; `left()` → fired.
  - `holdPlaceCapIs3Hours`: held cue `snoozedUntil == now + 3h`.
  - `errandFiresAtMatchingKind`: errand cue STORE; call internal `act(PlaceKind.PHARMACY)` → cue `firedAt != null`; second call within 30 min → unchanged.
  - `focusEndDuringHoldPlaceStaysQuiet`: Dnd reasons {focus, place}; focus end → reasons {place} (assert via `Dnd` prefs, no policy access needed for the set logic).
- [ ] **Step 3: Implement** all of the above. **Step 4: Run** unit + device tests → PASS. Manual check on emulator: set test GPS provider at a mosque/pharmacy coordinate (`cmd location providers ...`, per memory), simulate stillness is not possible → expose a debug-only "Check this place now" row in PlacesScreen (`BuildConfig.DEBUG`) that calls `stillFor3Min()`.
- [ ] **Step 5: Commit** `Place awareness: know the kind of place and let it shape reminders`

---

### Task 10: Docs, version, release build

**Files:** `README.md` (new features, Places permissions, what's sent to OpenStreetMap/AI), `docs/DECISIONS.md` (v1.1 decisions: 3-min stillness instead of 10 so short shop stops count; hold cap 3 h; lecture halls are rarely mapped in OpenStreetMap, so they aren't detected as hold places (limitation, listed in README); ack no longer touches task; Snooze N button replaced by picker; group bundling), `app/build.gradle.kts` (`versionCode = 2`, `versionName = "1.1"`).

- [ ] **Step 1:** Update docs + version. **Step 2:** `.\gradlew.bat testDebugUnitTest assembleRelease` → BUILD SUCCESSFUL; copy `app/build/outputs/apk/release/app-release.apk` to `FRIDAY-v1.1.apk` (git-ignored like v1.0 — check `.gitignore`). **Step 3:** Device tests via `am instrument` → all PASS. Install over v1.0 with `adb install -r` and confirm the user's existing tasks are still there. **Step 4: Commit** `FRIDAY v1.1`

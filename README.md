# FRIDAY

A calm personal assistant for Android that remembers things so you don't have to keep everything in your head.
Built for one person's phone (sideloaded, not on the Play Store), in English and Malay.

FRIDAY is not a medical app and does not diagnose or treat ADHD or any other condition.

## Install on your phone

1. Copy `FRIDAY-v1.3.apk` (in this folder) to your phone, for example over USB or Google Drive.
2. Open it on the phone. If asked, allow your Files app to "Install unknown apps", then tap **Install**.
3. Open FRIDAY, say hi, and allow notifications.

Or with USB debugging on: `adb install -r FRIDAY-v1.3.apk`.

Updates install over the old version and keep your data, **as long as they are signed with the same key**
(see "Signing key" below).

### First-time setup worth doing

| Where | Why |
|---|---|
| Settings › Reminders › *Let reminders through battery saving* | Some phones (Xiaomi, Oppo, Vivo, Samsung…) delay reminders otherwise. |
| Settings › Reminders › *Places* | Save Home/Campus/Work with "I'm here now" for "when I leave/arrive" reminders. |
| Settings › Assistant › *AI connection* | Optional online brain (see below). |
| Settings › Assistant › *Voice* | Natural voice, and "Use FRIDAY as phone assistant" (long-press power/home). |

## Optional: online AI (Gemini, free)

Everything works offline. An AI key adds free conversation, **Suggest reminders** and **Break it down**.

1. Get a free key at <https://aistudio.google.com/apikey> (Settings › AI connection › *Get a free Gemini key*).
2. Settings › AI connection › **API key** › paste › Save.
3. Tap **Test connection**. It should say "Connected · gemini-flash-latest".

- Server: `https://generativelanguage.googleapis.com/v1beta/openai/` (Gemini's OpenAI-compatible endpoint).
  Any OpenAI-compatible service works (OpenAI, OpenRouter, Groq, Ollama/LM Studio on your network):
  change **Server** and **Model**, or use *Pick from the service*.
- Default model `gemini-flash-latest` is Google's alias for its newest Flash model.
- What is sent: your message, the recent chat, the titles/times of open tasks, and the short list of
  things FRIDAY remembers about you (Settings › *What FRIDAY knows about me*). Never notes or locations. When you add an event, FRIDAY also sends its title,
  time and the things you said you'd bring, to plan how to get ready.
  On Gemini's free plan Google may use what you send to improve its products.
- The key is encrypted on the phone with Android Keystore and excluded from backups.
  A key stored inside an app is fine for a personal build, **not** for an app published to others.

## Optional: natural voice (ElevenLabs)

Settings › Assistant › Voice › **ElevenLabs key** (get one at <https://elevenlabs.io/app/settings/api-keys>),
then pick a **Voice** and tap **Test voice**. FRIDAY uses `eleven_flash_v2_5` (fast, supports Malay).
Without a key, or offline, it uses the phone's own text-to-speech. Only the words FRIDAY says are sent.

## Build it yourself

Requirements: JDK 17+ (JDK 21 used), Android SDK with platform **37.1** and build-tools **36.0.0**.
Put the SDK path in `local.properties` (`sdk.dir=…`). No Android Studio needed.

```powershell
.\gradlew.bat assembleDebug        # debug APK  -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease      # release APK (smaller, faster) -> app\build\outputs\apk\release\app-release.apk
.\gradlew.bat testDebugUnitTest    # unit tests on your computer
```

Device tests (run against an in-memory database, so app data is untouched). Install and run them
with `am instrument`, because Gradle's `connectedAndroidTest` uninstalls the app afterwards and would wipe its data:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w com.friday.app.test/androidx.test.runner.AndroidJUnitRunner
```

### Signing key

Both build types are signed with FRIDAY's own key in `keystore/friday.jks`, with the passwords in
`keystore.properties`. Both are git-ignored. **Back them up somewhere private.** Without them, a
new build can't update the installed app, and you'd have to uninstall FRIDAY (losing its data) first.

## What's inside

- **Home**: one "Now" card picked with the Eisenhower matrix, up to 3 "Next" items, upcoming reminders,
  Quick Capture (type or speak), and "I forgot what I was doing" (Context Recovery).
- **Tasks**: Today / Upcoming / Overdue / All / Done views, search, categories, and a Matrix view.
  Tasks have a date, time, duration, repeat (daily, weekdays, weekly on chosen days, monthly), steps,
  notes and category. Star = important; urgent = due within 2 days.
- **Reminders inside tasks**: automatic "get ready" (15 min before), "start" and "time's up"
  reminders, plus your own (for example "5 min before the end: screenshot analytics"). You can reuse the
  same reminders as last time, and add place reminders (arrive/leave).
- **Notifications**: two buttons: the main action (Mark done / Got it) and either Snooze… (5 min, 15 min,
  1 h, Tomorrow) or "Move to <time>" once FRIDAY has learned your usual time.
  One follow-up nudge if ignored, quiet hours (silent delivery), exact alarms, and reminders restored
  after reboot, clock and time-zone changes. Times are wall-clock, so 9 PM stays 9 PM when you travel.
- **Assistant**: offline English/Malay command understanding, quick answers ("what do I have today?",
  "I have 20 minutes", "I have too many things to do"), optional online AI with validated actions,
  Undo, and one-tap confirmation for changes. It never deletes. Siri-style voice conversation: tap the
  mic on Home and FRIDAY listens, answers aloud and listens again when it asks you something.
- **Inbox & Notes**: capture now, sort later. Turn a note into a task or a reminder, or keep it as a note.
- **What FRIDAY knows about me**: a list of facts the AI remembered (for example "I study best in the
  morning"). You can read, edit and delete every one. The AI uses it, and thinks before it replies.
- **Reminders that say why**: a reminder tells you why it matters, with two buttons: the main action and
  either "Snooze…" (5 min, 15 min, 1 h, Tomorrow) or "Move to <time>" once FRIDAY has learned when you
  usually snooze a start reminder to.
- **Reminders arrive together**: several reminders at once arrive as one bundle with one alert.
- **Getting ready, like a person would** (v1.2): say "I've license lesson at 28 Nov, 8 am, remind me to
  bring my IC". FRIDAY keeps a simple title, asks how early to alarm you (with a suggestion, e.g. "I'd say
  1 hour, OK?"), and adds a "before you go" checklist: what to bring for that kind of event, your phone,
  wallet and keys, and checking the switches. Calls and chores get no checklist. Works by voice too:
  answer "okay", "two hours", "dua jam" or "no thanks". With the AI on it plans any event; offline it knows
  lessons, exams, interviews, clinics, flights, trips, gym, meetings and events.
- **Events are not to-dos** (v1.3): a lesson or appointment only reminds you. It stays off the Now card,
  is never "overdue", its reminder says "Got it" instead of "Mark done", and it leaves the list once it's
  over. FRIDAY decides when you add it; flip the **Event** switch on the task if it guessed wrong.
- **Errand reminders**: say "remind me next time I'm at a pharmacy / store / supermarket to buy plasters"
  or "ingatkan saya bila di farmasi / kedai / pasar raya untuk beli plaster".
- **Know where I am** (Settings › Reminders › Places, off by default): FRIDAY notices when you stop somewhere, knows
  the kind of place (shop, pharmacy, library…), and lets it shape reminders, for example staying quiet at a
  quiet place. Needs precise location set to **Allow all the time**, **Physical activity**, and
  **Do Not Disturb access** (only to say it went silent).
- **Daily summary** at 8:00, skipped on days with nothing to report.
- **Settings**: name, theme (system/light/dark), language (English/Malay, Android 13+), reminders,
  quiet hours, daily summary, places, AI, voice, privacy (clear chat, delete
  all data) and about.

## Project structure

```
app/src/main/java/com/friday/app/
  FridayApp.kt        app container (hand-written dependency injection)
  MainActivity.kt     single activity; handles notification, shortcut and assistant launches
  ai/                 CommandParser (offline EN/MS), AssistantEngine, Online (OpenAI-compatible AI)
  data/               Room database, repositories, DataStore settings, Keystore secret store
  domain/             pure logic: matrix ranking, schedules and repeats, quiet hours, getting ready
  location/           saved places, geofences, place awareness and Do Not Disturb at quiet places
  reminders/          alarms, reminder engine, notifications, snooze picker, daily summary
  voice/              speech output (ElevenLabs / phone TTS) and in-app listening
  ui/                 theme, shared components, screens, settings, navigation
app/src/test/         unit tests (parser, logic, online AI client)
app/src/androidTest/  device tests
app/schemas/          Room schema history (add a migration for every future change)
docs/DECISIONS.md     product and technical decisions
```

## Dependencies

Kotlin 2.4, Android Gradle Plugin 9.5, Gradle 9.9 (wrapper), Jetpack Compose (BOM 2026.11.01,
Material 3 restyled), AndroidX Activity/Core/Lifecycle/Navigation, Room 2.8 (KSP), DataStore,
kotlinx-coroutines, kotlinx-serialization, OkHttp 5, Google Play services Location.
Inter typeface (SIL Open Font License, see `INTER_LICENSE.txt`) and Material Symbols icons (Apache 2.0).
No analytics and no ads.

## Test results (Pixel 6 Pro emulator, Android 16)

- 74 unit tests pass: the offline parser in English, Malay and mixed sentences; matrix ranking;
  schedules, repeats and time zones; quiet hours; events and getting ready; spoken answers; and AI action validation with the
  OpenAI-compatible client against a fake server.
- 42 device tests pass (v1.3), including:
  - task create/edit/complete/delete with undo, and repeating-task history
  - data surviving a database restart
  - reminders firing once, snooze, Mark done and the single follow-up nudge
  - the notification snooze button reaching the receiver
  - the database upgrade from v1 and v2 with data kept
  - an event asking how early to get ready, with its checklist
  - the daily summary being skipped on empty days
  - the assistant working without online AI
  - light/dark theme switching
- Checked by hand on the emulator:
  - a real 2-minute reminder rang on time
  - v1.3 installed over v1.1 with data kept; the license-lesson and dentist examples; the Home mic
  - FRIDAY as the phone's assistant (assist key)
  - microphone permission and listening
  - phone text-to-speech
  - a place reminder firing when leaving Home
  - the release build, which updated the app with data kept

## What is sent where

- **AI (if you set a key):** the v1.0 data plus the memory list, and for a new event its title, time and the
  things you said you'd bring. Never notes or locations.
- **OpenStreetMap:** only the rounded location of a stop (about 11 m precision), when "Know where I am"
  is on and you have been still for about 3 minutes. Nothing else.

## Known limitations

- **Lecture halls** are rarely mapped in OpenStreetMap, so FRIDAY can't tell you are in one. Put the
  phone on silent yourself there.
- **Time limits:** quiet-place silence and held reminders end after 3 hours at most.
- **Downgrading** to an older version is not possible without uninstalling (the database was upgraded in
  v1.1 and v1.3), which erases your data. Keep the APK of the version you use.

- **Voice:** speech recognition couldn't be heard on the emulator (it has no microphone input).
  Listening starts and the system mic indicator turns on; real recognition needs a phone.
- **ElevenLabs and real Gemini answers** need your keys. They were tested against a fake server, and
  the real Gemini endpoint correctly rejected a fake key.
- **Place reminders** depend on Google Play services, "Allow all the time" location and Google Location
  Accuracy. FRIDAY offers one-tap fixes, but phones without Google services can't use them.
- **Offline understanding** covers common phrasings; unusual sentences need the online AI, or are saved
  to the Inbox so nothing is lost.
- **Battery savers** on some phones can still delay reminders until FRIDAY is set to unrestricted
  (Settings shows a one-tap fix).
- **Monthly repeats** on the 29th–31st move to the last day of shorter months and stay there.
- **Backup:** data lives on the phone and in Android's Google backup if that's on (keys excluded). There
  is no cross-device sync, widget or watch app.
- **Code:** some forms still use Compose's older text-field API. It works but is deprecated; the fields
  that clear after sending already use the new one.

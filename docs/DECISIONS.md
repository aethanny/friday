# FRIDAY: decisions log

Decisions agreed with the owner during planning (2026-11-20), plus choices made while building.
CONTEXT.md is the original brief; where this file differs, this file wins.

## Product
- Personal app, sideloaded APK. Not for the Play Store (this is why `USE_EXACT_ALARM` is used).
- Languages: English and Malay screens (follow the phone, switchable in Settings on Android 13+).
  The offline parser understands English, Malay and mixed sentences; replies use the language you wrote in.
- Look: Apple-inspired. Inter font (SF Pro can't ship on Android), Apple blue as the only accent,
  inset grouped lists, large collapsing titles, iPhone-style tab bar, light and dark.
- Home shows one thing at a time: a single "Now" card, at most 3 "Next" items, the next reminders.

## Prioritisation (Eisenhower matrix)
- Important = the star you set. Urgent = due within 2 days or overdue. No High/Medium/Low.
- "Now" order: Do now → timed things starting within the hour → Quick wins → Plan it → Later.
- Build decision: a clock time *without* a duration counts as a deadline ("submit at 5 PM") and ranks
  normally; a timed task *with* a duration ("TikTok LIVE 9 PM, 1 h") is a scheduled activity and only
  becomes "Now" an hour before it starts.

## Reminders
- A reminder belongs to a task; a task can have several. Every timed task automatically gets
  "get ready" (15 min before), "start" and, if it has a duration, "time's up". Date-only tasks remind
  at 9:00 on the day. Your own reminders anchor to start/end ("5 min before the end: screenshot analytics").
- Times are stored as wall-clock values, so "9 PM" stays 9 PM after travelling to another time zone,
  and an occurrence that already fired never fires again.
- No answer within 10 minutes → one follow-up nudge, never more. Swiping away does not count as handled.
- Quiet hours (23:00–07:00, on): your reminders still arrive on time, silently. FRIDAY's own
  extras (daily summary, follow-ups) wait until morning.
- During focus, only the focused task's reminders pop up (plus important-and-urgent ones); others arrive silently.
- Notification actions: Done / Got it, Snooze (default length), and Snooze… (5 min, 15 min, 1 h, Tomorrow).
  "Tomorrow" moves the task and counts as a postponement.
- Missed reminders (phone off) fire after reboot if they are less than an hour old.
- "Only the focused task pops up" applies while a session is running; a paused session, or one
  that finished and is waiting for its check-in, no longer quiets anything.
- Place reminders ("when I leave/arrive at Home"): any place saved with "I'm here now" (no map).
  Google Play services geofencing, registered only for places with an open reminder; a 30-minute
  cooldown stops GPS wobble at the edge from repeating it. For a reminder with no date, the
  notification offers "Mark done". Needs location "Allow all the time" and Google Location
  Accuracy; FRIDAY shows a one-tap fix when either is missing. Deleting a place removes its reminders.

## Focus
- Timestamps, not a running timer: the countdown survives closing the app, process death and reboots.
- Do Not Disturb during focus (needs one-time access); FRIDAY restores your previous DND setting.
- Distraction shield: apps you pick are covered by a calm "back to your task" screen during focus.
  It shows the focus ring and time left, names the app ("YouTube can wait 22 min."), what you've
  already put in, and your next step. "Back to my task" is instant; "Let me in for 5 minutes" unlocks
  after one guided breath (8 s), the pause that breaks the automatic reach for the app. Never a hard
  lock. Every new attempt gets a fresh breath. Needs Usage access + Display over other apps.
- End of session: Completed / Made progress / Got distracted, then an optional "Where did you stop?".

## Assistant
- Local parser first (private, offline, instant); anything it can't handle goes to the optional online AI.
- New tasks, reminders and notes are created immediately with Undo. Changing or completing existing
  tasks needs one tap to confirm. FRIDAY can never delete.
- Online AI: one OpenAI-compatible connection, default Google Gemini (free tier) with the
  `gemini-flash-latest` alias so it never points at a retired model; "Pick from the service" lists
  the server's models. Key stored encrypted with Android Keystore. Sent: the message, recent chat and
  the titles/times of open tasks; never notes. Every AI action is validated (known task ids, sane
  dates/times, max 5 actions); unknown action types, including any kind of delete, are dropped.
- AI extras: "Suggest reminders" and "Break it down" in a task (you tick what to keep).
  Offline, "Break it down" asks for the very first tiny step. Tasks postponed 3+ times show a nudge.
- Voice: in-app listening (microphone permission asked on first use, with an explanation).
  Spoken questions get spoken answers; typed ones only if "Read typed replies aloud" is on.
  FRIDAY keeps listening only when its reply is a question, like Siri. ElevenLabs
  (`eleven_flash_v2_5`, Malay-capable, first voice on the account by default) with the phone's
  own text-to-speech as fallback. FRIDAY can be chosen as the phone's digital assistant app.

## Data and safety
- Everything stays on the phone (Room + DataStore) and in Android's encrypted backup.
  API keys are excluded from backup.
- Database migrations must be added for every future schema change; FRIDAY never wipes data to upgrade.
- APK signing key lives in `keystore/` (git-ignored). Keep a private copy: without it, updates
  can't install over the existing app.

## Tech
- Kotlin, Jetpack Compose (Material 3 as a base, restyled), Room, DataStore, Navigation Compose,
  coroutines/StateFlow, OkHttp + kotlinx.serialization. Hand-written DI container instead of Hilt;
  AlarmManager instead of WorkManager.
- AGP 9.5.1, Kotlin 2.4.21, Gradle 9.9.2, compileSdk 37.1, targetSdk 36, minSdk 26.

## v1.1 fixes
- B1: a snoozed place reminder keeps its snooze alarm when alarms are re-synced (it used to be cancelled on every app start).
- B2: monthly repeats keep their anchor day (Jan 31 -> Feb 28 -> Mar 31); the anchor is stored in `repeatDays` (0 = old tasks, use the due date's day).
- B3: completing a repeating task resets its postpone count, so one old postponement no longer marks every future occurrence as postponed.
- B4: the AI action type is trimmed once before every check, so " CREATE_REMINDER" without a time is rejected.
- B5: deleting a task with a place reminder (including undoing a creation) unregisters its geofence right away.

## v1.1 smart timing
- Reminders for other tasks are held (not delivered quietly) while a focus session runs, and arrive when it ends or its time is up. Important + urgent tasks still come through. The hold alarm at the session's end is kept as a backup.
- "Get ready" and "Start" reminders are skipped when the task is being focused on or was touched in the last 30 min. Tapping "Got it" (or opening the app from a reminder) no longer counts as touching the task, so acknowledging "get ready" doesn't make the start reminder skip.
- Reminders arriving within 2 min of another one still on screen are posted silently and grouped under one summary ("3 things now").
- Reminders have two buttons: the main action and "Snooze…" (the fixed "Snooze N min" button is gone).

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
- Online AI: one OpenAI-compatible connection, default Google Gemini (free tier), key stored
  encrypted with Android Keystore. Only your message plus related task titles and times are sent.

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

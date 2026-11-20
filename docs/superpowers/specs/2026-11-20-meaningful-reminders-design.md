# Meaningful reminders + a FRIDAY that knows you (v1.1)

## Why
FRIDAY should act like a sharp human secretary who already knows the user: it speaks up only when it
matters, says one clear thing, and never adds noise. v1.0 reminders fire at the set time with fixed
text ("Get ready: Gym"), and the AI forgets the user between chats.

## Goals
1. **Meaningful text**: each reminder says, in 1-2 short lines, why it matters now.
2. **Good timing**: reminders arrive when they can be acted on, not just when the clock says.
3. **One clear next step**: buttons fit the task, not a generic "Snooze".
4. **Memory**: FRIDAY keeps an editable list of facts about the user and uses it everywhere.
5. **Human-like reasoning**: the AI weighs time, energy, memory and history before it answers.
6. **Logic sweep**: find and fix existing bugs before building on top.

7. **Place awareness**: FRIDAY knows what kind of place the user is at and lets that shape reminders.

## Non-goals (for now)
- Wi-Fi / Bluetooth / data control (Shizuku).
- Auto-sending texts.

## Anti-goals (failure even if it works)
- More notifications than v1.0, or longer ones. Hard caps: 2 lines of body, 2 buttons, 1 follow-up.
- A reminder that arrives late because the AI was slow or offline.
- Shaming wording ("You failed again"). Tone stays supportive.
- Memory the user can't see or delete.

## Approaches considered
1. **AI does everything** (memory + timing decided online): most "human", but breaks offline and can
   suppress a reminder the user needed.
2. **All on-device rules**: private and predictable, but can't understand "I hate morning calls".
3. **Mix (chosen by the user)**: timing and fallbacks on the phone, memory stored on the phone,
   AI used for wording and reasoning when online. Never less reliable than v1.0.

## Design

### Phase 0: logic sweep
Run unit tests; review reminders, focus, parser, assistant, places for real bugs (wrong times,
double fires, missed reminders, wrong state after undo/snooze/reboot). Each fix gets a unit test.
Write the list of what was found in DECISIONS.md.

### Phase 1: memory
- New table `memories` (text, source = you / chat / habit, created). Shown in
  Settings › Assistant › "What FRIDAY knows about me": read, edit, delete, add.
- Online AI gets the list in its system prompt and may add one with a new `REMEMBER` action
  (short fact about the user's preferences/routine; never health details unless the user typed them
  as a memory themselves). Saved silently, visible in the list; the chat shows a small "Remembered".
- Habit facts are added by FRIDAY itself (Phase 3), marked "habit".

### Phase 2: human-like reasoning
- The AI reply JSON gains a hidden `"think"` field: it first considers the time, what the user is
  doing (focus, recent activity), memory, what keeps being postponed, and what's next; then replies.
  `think` is never shown or stored.
- Prompt rules: one recommendation, not a menu; notice avoidance kindly; respect memory
  (e.g. "hates long messages" makes replies shorter).

### Phase 3: smart timing + learning (all on the phone)
- **Hold during focus**: a reminder for another task (not important-and-urgent) waits until the focus
  session ends, then arrives (replaces v1.0's silent delivery).
- **Skip if already started**: no "get ready"/"start" reminder if the user is focusing on that task
  or worked on it in the last 30 min (`lastActiveAt`).
- **Bundle**: reminders landing within 2 minutes of each other arrive as one notification
  ("3 things now: …").
- **Learn snooze habits**: if a repeating task was snoozed/moved to roughly the same time of day
  3 times, the reminder's second button becomes "Move to 7 PM" (moves this one and future ones),
  and a habit memory is added.

### Phase 4: meaningful text + buttons
- Body (max 2 lines) is picked in this order:
  1. AI-written line, prepared ahead of time (when a task is saved and each morning for the next
     24 h), stored on the reminder. Never generated at fire time.
  2. Offline line from rules, e.g. "Moved 3 times. Just 5 minutes counts.",
     "Due tomorrow.", "First step: open the slides.", "Next: Lab report at 4."
  3. v1.0 text.
- Buttons (max 2): main = Done / Start focus / Got it (as v1.0); second = learned move
  ("Move to 7 PM") if any, else "Snooze…" (picker already has 5 min / 15 min / 1 h / Tomorrow).
- Follow-up nudge, quiet hours and English/Malay stay as in v1.0.

### Phase 5: place awareness (tied to reminders)
- **Detect**: when the phone stops moving for ~10 min (Activity Recognition "still" transition, not
  constant GPS), FRIDAY takes one location fix and asks OpenStreetMap (Overpass, free, no key) what
  is there: mosque, cinema, lecture hall/university, hospital/clinic, library, gym, supermarket,
  pharmacy, etc. Saved places (Home/Campus/Work) win over the lookup. Off by default until the user
  turns it on in Settings › Reminders › Places (needs Physical activity + "Allow all the time").
- **Reminders follow the place**:
  - *Hold places* (mosque, cinema, lecture hall, hospital): phone goes silent (DND, existing access);
    reminders wait and arrive when the user leaves, bundled. Important-and-urgent still come through.
  - *Study places* (library): one offer "Focus on <Now task>?".
  - *Errand places*: a new kind of place reminder, "next time I'm at a pharmacy/supermarket/…"
    ("remind me to buy batteries at a store"), fires on arrival at any place of that type.
  - *Home*: one calm "left today: …" if anything is left (at most once per evening).
- Every automatic change shows one quiet "Went silent · Undo". Undo twice at the same kind of place
  → FRIDAY stops doing it there and adds a habit memory.
- Privacy: only the rough location of the stop is sent to OpenStreetMap; nothing else.

## Constraints
- Kotlin/Compose, existing structure; Room migration must keep the user's data (real data on device).
- Device tests via `am instrument` only (never `connectedAndroidTest`).
- Works fully offline; AI only improves wording and chat.
- Data sent to AI: as v1.0 plus memory list. Never notes.

## Left to the builder
Exact schema/migration, how prepared lines are stored, bundling mechanics, wording of offline
lines in both languages, thresholds tuning (30 min, 2 min, 3 snoozes) with tests.

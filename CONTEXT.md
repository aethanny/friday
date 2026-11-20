# MASTER PROMPT: BUILD FRIDAY — AN INTELLIGENT PERSONAL AI ASSISTANT FOR ANDROID

You are acting as an autonomous senior Android engineer, software architect, AI engineer, product designer, and UX specialist.

Your task is to design, develop, implement, test, and deliver a working Android application called **FRIDAY**.

Do not merely describe the application or generate a project proposal. You must actually build the application in the current working directory.

Work independently, make sensible technical decisions, and prioritize a complete, functional, polished MVP.

Do not stop after presenting the architecture or implementation plan. Continue through implementation, compilation, testing, and refinement.

If something is unclear, make a reasonable assumption, document it, and continue.

---

# 1. PRODUCT IDENTITY

## Application name

FRIDAY

## Product description

FRIDAY is an intelligent personal assistant designed to help people organize everyday life, remember important things, stay focused, reduce distractions, and follow through on their responsibilities.

The inspiration is FRIDAY, Tony Stark's AI assistant from Iron Man.

However, this is not supposed to be a fictional futuristic interface.

I want a realistic personal assistant that can actually help with everyday life.

The application should be particularly useful for people who struggle with:

- Forgetfulness.
- Getting distracted easily.
- Procrastination.
- Losing track of ongoing activities.
- Difficulty prioritizing tasks.
- Forgetting appointments.
- Mental overload.
- Inconsistent routines.
- Remembering small but important responsibilities.

Design with ADHD-friendly principles, but never diagnose users or claim to treat ADHD.

## Core philosophy

**FRIDAY should remember things so I don't have to keep everything in my head.**

It should reduce cognitive load rather than create more work.

The application must feel like a helpful companion, not another demanding productivity application.

---

# 2. TECHNICAL STACK

Build a genuine native Android application.

Preferred technologies:

- Kotlin
- Jetpack Compose
- Material 3
- Android SDK
- MVVM architecture
- Kotlin Coroutines
- StateFlow
- Room Database
- DataStore
- Navigation Compose
- WorkManager where appropriate
- AlarmManager for scheduled reminders when appropriate
- Android Notification APIs
- Android Speech Recognition APIs
- Android Text-to-Speech APIs
- Retrofit or another suitable HTTP client for remote AI communication
- Hilt or another suitable dependency injection solution

Use stable, mutually compatible dependency versions and the Android SDK available in the development environment.

Do not blindly downgrade dependencies or use obsolete Android APIs.

Recommended minimum Android version: API 26, unless a strong technical reason requires otherwise.

Use Gradle Kotlin DSL.

Keep the application architecture clean, maintainable, scalable, and testable.

---

# 3. VISUAL DESIGN SYSTEM

This is one of the most important parts of the project.

I do not want a generic AI-generated dashboard.

I want FRIDAY to feel like a professionally designed premium Android application.

## Design direction

Combine three inspirations:

1. Apple-inspired minimalism.
2. Modern AI assistant interfaces.
3. Subtle futuristic elements inspired by Iron Man's FRIDAY.

The result should be calm, sophisticated, practical, and distinctive.

Do not copy Apple's interface or Iron Man's movie interface directly.

## Visual characteristics

- Minimal interface.
- Excellent typography hierarchy.
- Generous spacing.
- Restrained rounded corners.
- Subtle shadows and elevation.
- Consistent iconography.
- Smooth transitions.
- Minimal visual noise.
- Clear information hierarchy.
- Responsive layouts.
- Accessible touch targets.
- Beautiful empty states.
- Subtle interaction feedback.

## Color palette

Create a cohesive design system.

Dark theme:
- Deep charcoal or midnight background.
- Slightly elevated dark surfaces.
- Soft white primary typography.
- Muted secondary text.
- Subtle electric cyan or blue accent.
- Restrained amber for important warnings.

Light theme:
- Warm off-white background.
- Clean white surfaces.
- Dark primary typography.
- Muted gray secondary text.
- The same accent color as dark theme.

Support both themes properly.

Do not use excessive gradients, glowing effects, glassmorphism, or decorative borders.

Use gradients only when they genuinely improve the interface.

## Typography

Use a modern, readable Android typography system.

Prioritize:
- Excellent readability.
- Proper font weights.
- Balanced line heights.
- Consistent heading scale.
- Clear separation between primary and secondary information.

Do not make every text element bold.

## Icons

Use consistent professional icons.

Avoid emojis as substitutes for interface icons.

## Animation

Implement subtle animations for:
- Navigation transitions.
- Opening and closing dialogs.
- Task completion.
- Focus timer states.
- Assistant typing indicators.
- Button interactions.
- Screen transitions.

Animations should feel smooth and lightweight.

Respect Android accessibility settings and reduce-motion preferences where appropriate.

---

# 4. APPLICATION NAVIGATION

Create an intuitive navigation structure.

Recommended bottom navigation:

1. Home
2. Tasks
3. Assistant

Settings should be accessible from the home screen through a profile or settings icon.

Focus Mode should be accessible from the home screen and active task.

Notes and Inbox should be accessible through the Tasks section or Quick Capture.

Avoid adding unnecessary navigation destinations.

---

# 5. HOME SCREEN

The home screen is the most important screen.

It should feel like opening a personal assistant, not opening a spreadsheet.

## A. Greeting

Display a time-sensitive greeting:

- Good morning.
- Good afternoon.
- Good evening.

Show the current date in a subtle way.

Use a personalized greeting without making it overly decorative.

## B. Assistant introduction

Introduce FRIDAY with a simple status or contextual message.

Examples:

"How can I help you stay on track today?"

"You have two important things to take care of."

"Let's make today a little easier."

The assistant message should change depending on useful context.

Avoid repeating generic greetings on every screen refresh.

## C. Current focus

If a focus session is active, display:

- Current task.
- Remaining time.
- Pause.
- Resume.
- End session.

If no session is active, display a simple invitation to start focusing.

## D. Today's priorities

Show only the most important tasks.

Prioritize based on:
- User-selected priority.
- Deadline.
- Overdue status.
- Time sensitivity.
- Whether the task has been repeatedly postponed.

Do not display every task on the home screen.

## E. Upcoming reminders

Display the next few relevant reminders.

Make the time and task title easy to scan.

## F. Quick Capture

Create an easily accessible input component.

Placeholder:

"Tell FRIDAY anything..."

Support:
- Text entry.
- Voice input.
- Submit action.
- Quick note creation.
- Task creation.
- Reminder creation.

Examples:

"Remind me to pay my internet bill tomorrow."

"Call my lecturer at 3 PM."

"Buy shampoo this weekend."

"Finish the poster before Friday."

"I have an idea for a new website."

The interface should make capturing thoughts extremely fast.

## G. Daily overview

Display a simple summary:

- Tasks remaining.
- Tasks completed.
- Upcoming reminders.
- Overdue tasks.

Do not turn the home screen into a statistics dashboard.

---

# 6. TASK MANAGEMENT

Implement a fully functional task management system.

Each task should support:

- ID.
- Title.
- Description.
- Creation date.
- Due date.
- Due time.
- Priority.
- Category.
- Estimated duration.
- Completion status.
- Subtasks.
- Notes.
- Recurrence, where applicable.

Support these task views:

- Today.
- Upcoming.
- Overdue.
- Completed.
- All tasks.

Allow the user to:

- Create tasks.
- Edit tasks.
- Delete tasks.
- Complete tasks.
- Reopen completed tasks.
- Change priorities.
- Set deadlines.
- Add subtasks.
- Search tasks.
- Filter tasks.

Task creation must be quick.

Do not force the user through a large form.

## Task prioritization

Use a simple and explainable prioritization system.

Consider:
- High, medium, and low priority.
- Due dates.
- Overdue status.
- User preferences.

Never let AI silently change task deadlines or discard user data.

---

# 7. SMART REMINDERS

Reminders are a core feature.

Users must be able to create reminders naturally.

Examples:

"Remind me tomorrow at 9 AM to submit my assignment."

"Remind me every Monday to clean my room."

"Remind me at 8 PM to call my mother."

"Remind me to bring my charger when I leave home."

Each reminder should support:

- Title.
- Description.
- Date.
- Time.
- Recurrence.
- Priority.
- Completion status.
- Snooze.
- Notification settings.

## Notification actions

Implement Android notifications with:

- Mark as done.
- Snooze for 5 minutes.
- Snooze for 15 minutes.
- Snooze for 1 hour.
- Open reminder.

Use proper notification channels.

Request notification permission on Android versions that require it.

## Notification reliability

Implement proper Android scheduling.

Use AlarmManager for user-scheduled reminders where appropriate.

Use exact alarms only when permissions and platform requirements allow them.

When exact alarm access is unavailable, use an appropriate inexact fallback and communicate its limitations.

Handle:
- Device reboot.
- Time zone changes.
- Time changes.
- App process death.
- Updated reminders.
- Deleted reminders.
- Recurring reminders.

Do not create duplicate notifications.

Do not create endless notification loops.

A dismissed notification must not accidentally mark a reminder as completed.

Allow users to configure reminder behavior and quiet hours.

---

# 8. AI ASSISTANT

Create a dedicated assistant screen.

The assistant should feel conversational, intelligent, and natural.

The user should be able to communicate through text.

Prepare the architecture for voice interaction.

## Example conversations

User:
"What do I have to do today?"

FRIDAY:
"You have two important tasks today. Your assignment is due tonight, and you also need to pay your internet bill."

User:
"I have too many things to do."

FRIDAY:
"Let's simplify things. We'll start with the most urgent task and break it into smaller steps."

User:
"Remind me to buy groceries tomorrow."

FRIDAY:
"Sure. I'll remind you tomorrow. What time would you prefer?"

User:
"I have 20 minutes. What should I do?"

FRIDAY:
"You could finish the first section of your assignment. It should fit into a short focus session."

## Assistant capabilities

The assistant should eventually support:

- Task creation.
- Reminder creation.
- Task prioritization.
- Task decomposition.
- Daily briefing.
- Notes.
- Focus session management.
- Summarizing unfinished responsibilities.
- Context recovery.

## AI architecture

Create an extensible AI provider abstraction.

Support an optional configurable remote AI provider.

Start with an OpenAI-compatible API adapter and design the interface so other providers can be added later.

Do not assume that Claude Code itself is the runtime AI provider.

Do not hardcode API keys into the source code or APK.

Allow users or developers to configure an AI endpoint and credentials through an appropriate configuration mechanism.

For a prototype, use Android Keystore-backed encryption for locally stored credentials if BYOK functionality is implemented. Explain that client-side API keys are not suitable for a public production application.

Do not log credentials or sensitive conversation data.

## Structured actions

The AI must not execute arbitrary commands.

It must return validated, structured action proposals.

Supported actions:

- CREATE_TASK
- CREATE_REMINDER
- CREATE_NOTE
- UPDATE_TASK
- COMPLETE_TASK
- SNOOZE_REMINDER
- CREATE_SUBTASKS
- START_FOCUS_SESSION

Validate dates, times, fields, and identifiers before execution.

For ambiguous requests, ask a short clarification question.

For destructive operations, request confirmation.

The AI should never silently delete tasks or reminders.

## Offline functionality

FRIDAY must remain useful without internet access or an AI API connection.

Implement a local assistant service capable of recognizing common reminder and task commands.

Use deterministic parsing and sensible fallback responses.

Do not pretend that the local parser is a fully capable language model.

Show a clear connection or availability status when remote AI is unavailable.

---

# 9. FOCUS MODE

Implement a practical focus system.

The user should select a task and start a focus session.

Available durations:

- 5 minutes.
- 15 minutes.
- 25 minutes.
- 45 minutes.
- Custom duration.

Focus screen should show:

- Current task.
- Large countdown timer.
- Pause.
- Resume.
- End session.
- Progress indicator.

Persist the session state so the countdown remains correct when the app is closed and reopened.

Use timestamps rather than relying on a timer running continuously in the background.

At the end of a session, ask:

"How did it go?"

Possible responses:

- Completed.
- Made progress.
- Got distracted.

Give positive, non-judgmental feedback.

Do not shame users for getting distracted.

Optional: Provide a small interruption recovery action:

"Let's get back to what you were doing."

---

# 10. NOTES AND INBOX

Create a lightweight capture system.

Users should be able to save thoughts without immediately deciding what they are.

Examples:

"Website idea."

"Ask my lecturer about the assignment."

"Remember this design inspiration."

"Potential gift idea."

Support:

- Quick notes.
- Ideas.
- Unsorted entries.
- Conversion into tasks.
- Conversion into reminders.
- Editing and deleting.

Create an Inbox for uncategorized entries.

Never discard information because the classification is uncertain.

---

# 11. DAILY BRIEFING

Create a small daily summary.

Include:

- Important tasks.
- Upcoming appointments or reminders.
- Overdue tasks.
- Outstanding priorities.

Keep the briefing concise.

Allow the user to configure when it appears.

Do not generate unnecessary daily reports.

---

# 12. CONTEXT RECOVERY — SIGNATURE FEATURE

This is an especially important feature.

Implement a simple first version called Context Recovery.

The purpose is to help users remember what they were doing after getting distracted or interrupted.

Track only useful, explicit in-app context:

- Last active task.
- Last selected focus task.
- Last focus session.
- Recent task notes.
- Last unfinished subtask.

Provide an action on the home screen or assistant screen:

"I forgot what I was doing."

When activated, FRIDAY should display relevant saved context.

Example:

"You were working on your Concertly website.

Your last active task was updating the event booking section.

Your next planned step was to finish the payment interface.

Would you like to continue?"

Never claim to know what happened outside the application.

Never secretly monitor other applications or capture screen contents.

Make it clear that Context Recovery is based on information saved inside FRIDAY.

---

# 13. SETTINGS

Implement a proper settings screen.

Include:

- Light theme.
- Dark theme.
- System theme.
- Notification preferences.
- Default snooze duration.
- Quiet hours.
- Daily briefing time.
- Focus duration preferences.
- AI configuration.
- Voice preferences.
- Privacy settings.
- Clear application data.
- Application information.

Only request Android permissions when they are needed.

Never request accessibility access, notification-listener access, location, contacts, microphone, or other sensitive permissions without a clearly implemented and explained feature requiring them.

---

# 14. DATABASE ARCHITECTURE

Use Room for persistent local storage.

Create appropriate entities for:

- Task.
- Reminder.
- Note.
- FocusSession.
- AssistantMessage or conversation history where appropriate.
- UserPreference.

Define proper:
- Relationships.
- Primary keys.
- Foreign keys where needed.
- Indices.
- Type converters.
- DAO interfaces.
- Database migrations.

Keep database operations outside the UI layer.

Use repositories.

Ensure data remains after application restarts.

Use appropriate timestamps and time-zone handling.

Avoid storing ambiguous date strings as the only representation of scheduled events.

---

# 15. APP ARCHITECTURE

Use a clean MVVM-based architecture.

Suggested structure:

app/
  src/
    main/
      java/
        com/
          friday/
            app/
            core/
            data/
              database/
              datastore/
              repository/
              local/
            domain/
              model/
              usecase/
              repository/
            ui/
              theme/
              components/
              navigation/
              screens/
                home/
                tasks/
                assistant/
                focus/
                notes/
                settings/
            ai/
              provider/
              parser/
              actions/
              models/
            notifications/
            workers/
            receiver/
            util/

Adjust the structure when there is a technically justified reason.

Do not force unnecessary abstraction.

Separate:
- UI.
- Business logic.
- Data storage.
- AI communication.
- Android system integrations.

Make the application straightforward to maintain.

---

# 16. SECURITY AND PRIVACY

FRIDAY is a personal application.

Privacy matters.

Requirements:

- Store personal tasks and reminders locally by default.
- Do not upload personal data unnecessarily.
- Do not add advertising SDKs.
- Do not add unnecessary analytics.
- Do not log API credentials.
- Do not hardcode secrets.
- Provide clear AI data usage information.
- Allow users to clear local data.
- Request minimum necessary permissions.

Remote AI requests should include only relevant context required to answer the user's request.

Clearly distinguish local processing from remote AI processing.

---

# 17. DEVELOPMENT REQUIREMENTS

You are expected to work directly in the current repository or project directory.

First inspect the current environment.

Determine:
- Whether an Android project already exists.
- Installed Java version.
- Gradle configuration.
- Available Android SDK.
- Existing project structure.
- Existing source files.

Preserve useful existing work.

If the directory is empty, create a complete Android project.

If an existing project is broken, fix it without unnecessarily destroying existing functionality.

Use stable compatible dependencies.

Avoid unnecessary libraries.

Do not generate fake implementations.

Do not create buttons that do nothing.

Do not leave placeholder screens for core MVP functionality.

Do not leave core TODO comments instead of implementing the features.

Do not claim something works unless it has been implemented and tested.

---

# 18. IMPLEMENTATION PRIORITIES

Build these features in order, but continue through all of them without waiting for manual approval between phases.

### Priority 1 — Application foundation
- Android project.
- Gradle configuration.
- Architecture.
- Navigation.
- Design system.
- Light and dark themes.

### Priority 2 — Main UI
- Home screen.
- Tasks screen.
- Assistant screen.
- Focus screen.
- Notes and Inbox.
- Settings screen.

### Priority 3 — Core functionality
- Room database.
- Task CRUD.
- Reminder CRUD.
- Notes.
- Task priorities.
- Search and filters.
- Focus timer.

### Priority 4 — Android integrations
- Notification channels.
- Notification permissions.
- Reminder scheduling.
- Notification actions.
- Snooze.
- Reboot and time-zone handling.

### Priority 5 — Assistant intelligence
- Local natural language parser.
- Conversational interface.
- AI provider abstraction.
- Optional remote AI integration.
- Structured actions.
- Validation.
- Context Recovery.

### Priority 6 — Refinement
- Empty states.
- Loading states.
- Error handling.
- Animations.
- Accessibility.
- Responsive UI.
- Performance.
- Testing.

---

# 19. TESTING REQUIREMENTS

Create appropriate unit tests and UI tests where practical.

Test at least:

1. Task creation.
2. Task editing.
3. Task completion.
4. Task deletion.
5. Reminder creation.
6. Reminder scheduling.
7. Reminder snoozing.
8. Reminder completion.
9. Recurring reminder behavior.
10. Database persistence.
11. Focus session persistence.
12. Countdown accuracy after reopening the application.
13. Local natural language parsing.
14. Ambiguous reminder handling.
15. AI unavailable state.
16. Theme switching.
17. Notification action handling.
18. Time-zone changes.

Run Gradle compilation and tests.

Fix compilation errors and important test failures.

Do not ignore warnings that indicate a real functional problem.

If Android SDK, emulator, or device access is unavailable, clearly identify what could not be tested.

---

# 20. VISUAL QUALITY CONTROL

Do not settle for a generic Material 3 template.

Review every screen as a professional mobile product designer.

Check:

- Alignment.
- Padding.
- Typography.
- Contrast.
- Card hierarchy.
- Touch target sizes.
- Navigation consistency.
- Scroll behavior.
- Keyboard behavior.
- Empty states.
- Dark mode.
- Light mode.
- Smaller Android screens.

Avoid:
- Huge unnecessary cards.
- Excessively rounded components.
- Too many accent colors.
- Cluttered layouts.
- Excessive text.
- Unnecessary decorative icons.
- Repeated information.
- Excessively complicated interactions.

The result should look like a real premium application ready for personal daily use.

---

# 21. DEFINITION OF DONE

The first version is considered complete when:

- The Android application builds successfully.
- The project has a coherent architecture.
- The home screen is fully implemented.
- Task management works.
- Reminders can be created and scheduled.
- Notifications have working actions.
- Snooze works.
- Data persists locally.
- Focus mode works.
- The assistant interface works.
- Common natural language commands work locally.
- Optional AI integration has a documented configuration path.
- Context Recovery works.
- Light and dark modes work.
- Settings work.
- There are no major placeholder features.
- Core tests pass.

---

# 22. FINAL DELIVERABLES

When implementation is complete, provide:

1. Complete Android project source code.
2. A clear project folder structure.
3. A list of implemented features.
4. A list of external dependencies.
5. Instructions to run the app.
6. Instructions to configure optional AI functionality.
7. Instructions to generate a debug APK.
8. Location of the generated APK, if successfully built.
9. Build and test results.
10. Known limitations and features that require further development.

If possible, generate a debug APK in the project directory.

Use the appropriate Gradle command for the current operating environment.

Do not report a successful build or APK generation without verifying the actual result.

---

# 23. FINAL INSTRUCTIONS

You have creative freedom over the implementation as long as you preserve the product vision.

Make intelligent decisions.

Prioritize a genuinely usable application over unnecessary complexity.

Do not spend the entire session explaining your plan.

Do not stop after producing architecture documents.

Do not ask me to approve every individual screen.

Do not wait for me to provide design references.

Do not replace real functionality with screenshots or mockups.

Build the actual application.

When encountering a blocker, investigate it, select a reasonable alternative, and continue.

If a feature cannot be completed because of a platform limitation, external credential requirement, or unavailable dependency, implement the best honest fallback and document the limitation.

**The final goal is to give me a working Android application that I can install on my phone and use as my personal assistant.**

Build FRIDAY.

Start now.
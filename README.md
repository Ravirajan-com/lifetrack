# LifeTrack

Personal life tracker — Expenses (Axio-style SMS auto-import), Gym progress, and Daily goals.
Native Android: **Kotlin + Jetpack Compose + Room + WorkManager**. 100% offline, data never leaves the phone.

## Open & run

1. Open the `LifeTrack` folder in **Android Studio** (Koala or newer).
2. Let Gradle sync (Android Studio generates the wrapper automatically; AGP 8.5.2, Kotlin 2.0.20, JDK 17).
3. Run on a real device (SMS features don't work on most emulators).
4. Grant SMS + notification permissions when prompted.
5. Expense tab → **Inbox** → "Import last 90 days of SMS" to backfill transactions.

## What's implemented

### Expense
- `SmsReceiver` catches incoming bank/UPI SMS in real time; `SmsParser` extracts amount,
  debit/credit, merchant, UPI VPA, and bank (HDFC/SBI/ICICI/Axis/Kotak/… patterns).
- 90-day inbox backfill.
- Categorize sheet with a **"learn rule" switch**:
  - ON → every past & future transaction with the same merchant/UPI ID gets this category.
  - OFF → one-off override for just this transaction (rules skip overridden rows).
- Dashboard: monthly total, donut by category, budget vs actual per category, daily bar chart.
- Default categories seeded on first launch; per-category monthly budgets in the schema.

### Gym
- Splits → Days → Exercise templates (target sets × reps).
- Start a session, log each set (reps × kg); the card shows **"Last time: 10x60kg 8x65kg…"**
  pulled from your previous session of that exercise.
- Per-exercise progress chart (max weight per session over time; volume also computed).

### Goals
- Daily-recurring or today-only goals, checklist with streak counter (🔥).
- Optional reminder time per goal; `GoalReminderWorker` (WorkManager, 15-min cadence)
  notifies for due, incomplete goals.

## Project layout

```
app/src/main/java/com/lifetrack/app/
├── LifeTrackApp.kt            # seeds categories, schedules reminders
├── MainActivity.kt            # bottom nav + runtime permissions
├── data/db/                   # Room: entities, DAOs, AppDatabase
├── data/repo/                 # ExpenseRepository (rule learning + SMS backfill)
├── sms/                       # SmsParser + SmsReceiver
├── notifications/             # reminder worker + scheduler
└── ui/                        # Compose screens per module + Canvas charts
```

## Known skeleton limitations / next steps
- Reminders fire on a 15-min WorkManager cadence → switch to `AlarmManager` exact alarms for minute precision.
- SMS parser covers common formats; add bank-specific patterns as you see unparsed messages (rawSms is stored for debugging).
- No edit/delete for transactions & sessions yet; no month picker on dashboard (current month only).
- Charts are minimal Canvas drawings → swap in Vico for axes/labels when ready.
- Budget editing UI not wired (schema + display done); add a budget editor sheet.
- Consider weekly goals, gym rest timers, and CSV export.

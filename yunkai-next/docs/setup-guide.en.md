# Setting up phone control, step by step

[简体中文](setup-guide.md) | English

This guide walks you through "Set up phone control" in Mika. No technical knowledge needed; just follow along. It takes about 3 to 5 minutes.

## Before you start

- **What you need**: an Android phone running Android 13 or later (Mika installs on older versions but can't use apps there), connected to any Wi-Fi network (internet access not required, just being connected is enough).
- **Why this is needed**: Mika uses apps for you on an invisible "background screen", so you can keep using your own screen. To do that, Mika borrows Android's built-in "Wireless debugging" to start a small built-in helper.
- **Everything happens on the phone**: no computer, no cable.
- **You do it once**: after pairing once, Mika connects by itself. Even if the system later turns wireless debugging off, Mika turns it back on, with your permission.
- **Optional**: web, files and chat work without it. You only need it for tasks that use apps on your phone.

> The paths below follow stock Android. Some phones name or place menus differently. If you can't find something, use the search box at the top of system Settings (search for "Build number", "Developer options" or "Wireless debugging").

## Where to find the wizard

- The first time you open Mika, "Set up phone control" is the last step after setting up a model.
- If you tapped "Not now" back then, that's fine: open Mika's Settings and tap the notice in the status card at the top, or go to Settings › Phone control › Pair again.
- If a task needs your phone before you've set it up, a line appears in the chat; tap "Open Settings".

The wizard is a numbered checklist of six steps. **Each step ticks itself off when it's done**: Mika checks the system switches by itself, so you usually don't need to press "Next". The current step is highlighted and has a button that takes you straight to the right system settings page; when you're done there, press Back to return to Mika.

Some phones can't report these switches, or report them wrong: you've turned a setting on but Mika stays on that step. Then tap "I’ve turned it on, next" under the step to carry on; the wizard never locks you in. What counts in the end is pairing and connecting: once Mika connects, setup is done. You can also tap any ticked step to go back and redo it.

<!-- Screenshot: the six-step checklist in "Set up phone control" -->

---

## Step 1: Turn on Developer options

**In Mika**: tap "Open About phone".

**In system settings**:

1. Go to Settings › About phone.
2. Scroll all the way down to "Build number".
3. **Tap "Build number" 7 times quickly in a row.** Along the way you'll see "You are now X steps away from being a developer". If your phone has a screen lock, you'll be asked for your PIN or password once.
4. When you see "You are now a developer!" (or similar), you're done.

<!-- Screenshot: "Build number" on the About phone page -->

**How to tell it worked**: back in Mika, step 1 shows "On" with a tick.

**Common snags**:

- Can't find "Build number": on some phones it's inside "Software information" or "Version" under "About phone". You can also search Settings for "Build number".
- Nothing happens: tap in quick succession without long pauses.
- "No need, you are already a developer": it was already on; just go back to Mika.
- It's on but Mika stays on this step: tap "I’ve turned it on, next" to continue.

## Step 2: Turn on USB debugging

**In Mika**: tap "Open Developer options".

**In system settings**:

1. Go to Settings › System › Developer options (Mika takes you there).
2. Scroll to "USB debugging" and turn it on.
3. When asked "Allow USB debugging?", tap "OK".

<!-- Screenshot: the "USB debugging" switch in Developer options -->

**No cable needed.** With it on, Mika can keep working even when Wi-Fi is off, once pairing is done.

**How to tell it worked**: back in Mika, step 2 is ticked.

**Common snags**:

- Can't find "Developer options": on stock Android it's under Settings › System; some phones put it under "Additional settings" or similar. Searching Settings for "Developer options" is fastest.
- **Keep USB debugging on** from now on. Turning it off stops phone control.
- It's on but Mika stays on this step: tap "I’ve turned it on, next" to continue.

## Step 3: Turn on Mika's accessibility (we suggest skipping this)

**Suggestion: just tap "Skip (Mika turns it on after pairing)"**, the top button on this step.

Why we suggest skipping:

- Once pairing is done, Mika turns on its own accessibility service **by itself** ("Turn on accessibility for me" in Settings › Recovery & repair is on by default). You don't have to hunt for it in system settings.
- Doing it by hand can get stuck: if Mika wasn't installed from an app store (for example, you downloaded the APK from a web page), Android marks accessibility as a "Restricted setting" and won't let you turn it on directly. When Mika turns it on after pairing, this restriction doesn't get in the way.

The only cost of skipping: in the next steps you type the 6-digit pairing code yourself once (it's easy, see step 5).

<!-- Screenshot: the "Skip (Mika turns it on after pairing)" button on the accessibility step -->

**If accessibility is already on**: this step ticks itself off and Mika reads the pairing code by itself, which is even easier.

**If you really want to turn it on by hand now**: tap "Open Accessibility settings", find Mika in the list, turn it on and confirm. If the switch is greyed out or you see "Restricted setting":

1. Go back to Mika and tap "Can't turn it on? Allow restricted settings in App info first" (or go to Settings › Apps › Mika yourself).
2. On the "App info" page, tap ⋮ (three dots) at the top right and choose "Allow restricted settings", then confirm.
3. Go back to Accessibility settings and turn Mika on.

<!-- Screenshot: "Allow restricted settings" in the ⋮ menu on App info -->

> Note: "Allow restricted settings" only appears in the ⋮ menu **after you've tried once** to turn on accessibility and the system blocked it.

## Step 4: Turn on Wireless debugging

**First make sure the phone is on Wi-Fi** (internet access not required).

**In Mika**: tap "Open Developer options".

**In system settings**:

1. In Developer options, scroll to "Wireless debugging" and turn on the **switch** on its right.
2. When the system asks "Allow wireless debugging on this network?": **tick "Always allow on this network"**, then tap "Allow". With that ticked, Mika can turn wireless debugging on by itself on this Wi-Fi in future.

<!-- Screenshot: "Wireless debugging" in Developer options -->
<!-- Screenshot: the "Allow wireless debugging" dialog with the "Always allow on this network" checkbox -->

**How to tell it worked**: back in Mika, step 4 is ticked.

**Common snags**:

- The switch turns itself off again: usually the phone isn't on Wi-Fi, or "Allow" wasn't tapped in the dialog. Join Wi-Fi and try again.
- The switch is on but Mika stays on this step: some phones don't report this switch. Tap "I’ve turned it on, next" to continue.

## Step 5: Pair with a code

**The one thing that trips people up**: the pairing code only works while the system's pairing dialog is open. **As soon as the dialog closes (switching apps, pressing Back, locking the screen), the code is no longer valid.** So stay on the pairing dialog while you enter the code; don't switch apps.

**Before you start**: at this step Mika asks for permission to send notifications. Please tap "Allow": without accessibility, the pairing code is entered in Mika's notification. If notifications are off, the main button on this step is to turn them on first:

- Mika isn't allowed to send notifications: the wizard says "The pairing code goes into a notification, so allow Mika’s notifications first." Tap "Allow notifications".
- Allowed, but Mika's "Pairing" notifications are turned off: tap "Turn on “Pairing” notifications" and turn them on in the system notification settings.
- "Pairing" notifications are set not to pop up: the wizard mentions it; tap "Turn on" and turn on "Pop on screen". You can also skip that and enter the code from the notification shade.

**In Mika**: tap "Open Wireless debugging".

**In system settings**:

1. Mika takes you to Developer options with the "Wireless debugging" row highlighted. **Tap the words "Wireless debugging"** (not the switch) to open its page.
2. Tap "Pair device with pairing code".
3. A dialog appears with a **6-digit pairing code** and an "IP address & port".

<!-- Screenshot: "Pair device with pairing code" on the Wireless debugging page -->
<!-- Screenshot: the pairing dialog showing the 6-digit code -->

Then one of two things happens:

### Case A: accessibility is already on — nothing to do

The wizard shows a green hint: "When the code appears, wait a few seconds — Mika reads it and finishes pairing." Stay on the pairing dialog and **wait a few seconds**. Mika reads the code and pairs by itself, and the dialog closes on its own.

### Case B: accessibility is off (you skipped step 3) — enter the code in the notification

The wizard shows this as three small steps: ① Tap “Pair device with pairing code” ② Mika’s notification pops up at the top of the screen; if not, swipe down to open the notification shade ③ Tap “Enter code” and type the 6 digits. In detail:

1. **Don't leave the pairing dialog.** Note the 6 digits.
2. As soon as the pairing dialog opens, Mika's notification "Enter pairing code" pops up at the top of the screen, saying "Type the 6 digits shown on screen here — no need to leave the pairing window". If nobody answers it, it pops up again a little later, at most three more times.
   - If it doesn't pop up, or disappears quickly: **swipe down from the top of the screen** to open the notification shade, and you'll find it there. Pulling down the shade doesn't close the pairing dialog.
3. Tap "Enter code" on the notification. An input box labelled "6 digits" appears.
4. Type the 6 digits and tap send (the arrow next to the box). Spaces, dashes or full-width digits are fine.
5. The notification changes to "Pairing…", then a few seconds later to "Paired", and goes away by itself.

<!-- Screenshot: Mika's "Enter pairing code" notification with the input box in the notification shade -->

**Tips**:

- Allow Mika's notifications first. If you haven't, the wizard shows a note and an "Allow notifications" button.
- If the wizard shows an orange note saying "Needs you" and "Task results" notifications won't pop up: that's about notifications during later tasks, not pairing. You can tap "Turn on", or ignore it for now.
- If what you typed isn't 6 digits, the notification says "Enter 6 digits, then try again" right away and keeps the input box; nothing is sent.
- If the code was wrong or expired, the notification says "Wrong or expired code: in the pairing window, tap “Pair device with pairing code” again to get a new code, then enter it again" and keeps the input box. The same goes if the pairing dialog has already closed: go back to "Wireless debugging" and tap "Pair device with pairing code" again to get a **new** code, then enter the new one.
- Still no notification? The wizard has "No notification? Enter it here". But going back to Mika closes the pairing dialog, so use **split screen**: the pairing dialog on one side, Mika on the other. If Mika hasn't found the pairing dialog yet, it asks for one more number: the digits after the colon in "IP address & port" in the pairing dialog.

**How to tell it worked**: back in Mika, step 5 shows "Paired".

This step has no "Next": pairing has to actually happen. If neither the notification nor reading the code works, use "No notification? Enter it here" described above.

## Step 6: Connect automatically

Nothing to do here. Once paired, Mika connects and gets ready by itself, usually in a few seconds. When you see "Phone control is set up", you're done.

<!-- Screenshot: the wizard showing "Phone control is set up" -->

If you skipped step 3, Mika now turns on its own accessibility service. You can see Mika switched on in the system's Accessibility settings.

To find the Wireless debugging port, Mika first tries the one it used last time, the one the system reports and the one announced on the local network. If none of these works (some routers block those announcements), it searches this phone itself; the wizard shows "Looking for the Wireless debugging port on this phone…" for up to about ten seconds. It only looks at this phone, never at other devices on the same Wi-Fi.

If it still can't find it, the "Connect automatically" step shows "Enter the Wireless debugging port": open the system's "Wireless debugging" page, look at the "IP address & port" line, enter **the number after the colon** (for example `41235` in `192.168.1.5:41235`) and tap "Connect". Mika remembers it once connected. The number changes when you turn Wireless debugging off and on; Mika can usually find the new one by itself, otherwise just enter it again.

While connecting and after a failed connection, "Not now" is always at the bottom, so you can leave at any time and continue later from Settings.

If pairing or connecting fails, Mika uses what it could read to say which earlier step is the most likely cause (for example "“Wireless debugging” seems to be off" or "This phone doesn’t let Mika read the “Wireless debugging” switch"), with a button such as "Back to “Turn on Wireless debugging”" that takes you back to redo that step.

---

## After setup

- **Keep USB debugging on.** Turning it off stops phone control.
- **The system may turn wireless debugging off**: Android turns it off when Wi-Fi disconnects or changes, and after a restart. That's normal. Mika doesn't need it day to day, only when restarting its built-in helper (for example after a phone restart or a Mika update). "Turn on Wireless debugging automatically" and "Restore after restart" in Settings › Recovery & repair are on by default, so Mika turns it back on for you while on Wi-Fi, with no new pairing code.
- **The first time on a new Wi-Fi network**: the system hasn't allowed wireless debugging on that network yet, so Mika can't turn it on. Turn on "Wireless debugging" once yourself and, in the dialog, **tick "Always allow on this network"** and tap "Allow". After that, nothing more to do on that Wi-Fi.
- **To start over**: Settings › Phone control › Pair again.
- **To stop using it**: Settings › Phone control › Unpair. You can also remove Mika under Wireless debugging › Paired devices in system settings.

## Troubleshooting

| Symptom | What to do |
|---|---|
| Can't find "Developer options" | Do step 1 first (tap "Build number" 7 times in "About phone"). Once on, stock Android has it under Settings › System › Developer options; other phones may put it under "Additional settings" or similar. Search Settings for "Developer options". |
| Can't find "Build number" | Scroll down in "About phone"; on some phones it's inside "Software information" or "Version". Or search Settings for "Build number". |
| The Wireless debugging switch turns itself off | Make sure you're on Wi-Fi; in the dialog that appears when you turn it on, tap "Allow" and tick "Always allow on this network". After changing Wi-Fi, allow it once more on the new network. |
| No pairing notification from Mika | Check that Mika is allowed to send notifications and that "Pairing" notifications aren't turned off (the wizard reminds you and gives a button); swipe down from the top to open the notification shade; if it's still not there, use "No notification? Enter it here" in the wizard together with split screen. |
| The pairing notification only shows in the shade and doesn't pop up | "Pairing" notifications are set not to pop up. Tap "Turn on" in the wizard and turn on "Pop on screen" in system settings; or just swipe down to the shade to enter the code. |
| "Wrong or expired pairing code" | The code changes each time the pairing dialog opens. Go back to "Wireless debugging", tap "Pair device with pairing code" again and enter the new 6 digits. |
| "The pairing window closed" | Keep the pairing dialog open while entering the code: don't switch apps, press Back or lock the screen. Open the dialog again and enter the code. |
| Pairing worked, but "Connect automatically" fails | Make sure "Wireless debugging" is still on and the phone is on Wi-Fi, then tap "Retry". If that doesn't help, tap "Pair again". If the wizard points at an earlier step, tap "Back to “…”" to redo it. |
| Stuck at "Connect automatically" / can't find the Wireless debugging port | Turn "Wireless debugging" off and on, then tap "Retry"; switch to a phone hotspot or your home Wi-Fi (some routers stop devices from discovering each other); or in "Enter the Wireless debugging port", enter the number after the colon in "IP address & port" on the "Wireless debugging" page and tap "Connect". |
| A switch is on but the wizard stays on that step | Some phones report switches wrong. Tap "I’ve turned it on, next" to continue; what counts in the end is pairing and connecting. |
| "Wireless debugging isn't allowed on this Wi-Fi yet" | Turn on "Wireless debugging" once yourself; in the dialog, tick "Always allow on this network" and tap "Allow". |
| "Not connected to Wi-Fi" | Join any Wi-Fi network (internet access not required); Mika resumes by itself. |
| "Pairing expired" | Pair again. Turn on "Disable adb authorization timeout" in Developer options so it won't expire again. |
| Mika's accessibility switch is greyed out / "Restricted setting" | Recommended: skip that step; Mika turns it on after pairing. To do it by hand: try turning it on once so the system blocks it, then go to Settings › Apps › Mika, tap ⋮ at the top right › Allow restricted settings, and go back to Accessibility settings to turn Mika on. |
| Accessibility still off after pairing | Check that "Turn on accessibility for me" in Settings › Recovery & repair is on, and wait a few seconds; if it's still off, turn it on by hand as in the row above. |
| Stuck and nothing above fits | Tap "Copy diagnostics" at the bottom of the wizard (also inside the failed step, and under About Mika › Send feedback). Look it over, then tap "Copy" or "Share" and send it to the author (WeChat or a GitHub issue). It has the version, phone model, the state of each step and the last error — no API keys, chat content or screen content. |

# Claude task: make the remote's Assistant button open Free My TV search

Owner: Ayush. Hand-off file for Claude (research + development).
Status: open. Update the Status line and the Log at the bottom as you go.

## Goal

On Ayush's **TCL G10 Google TV**, pressing the remote's **Google Assistant / mic button**
should open Free My TV's own search screen (`SearchActivity`) with voice input already
listening, instead of Google Assistant. The user can still type, or press Back to cancel.

Scope: this fork only, and only what works on Ayush's TCL TV. Don't open PRs upstream
(simbasjungles/free-my-tv), and skip LG/Samsung (`web/`, `webos/`, `tizen/`).

## What already works (on main, merge commit d36a0cf)

- The remote's **Search** key (keycode 84, `KEYCODE_SEARCH`) opens our search with the mic
  listening. Tested on the TV: PASS.
- Search results show pictures. Tested on the TV: PASS.
- A `keep_google_home` setting exists. It keeps Google TV's own home app installed. With it
  on, `RemoteKeyService` is meant to open our search on a tap of the Assistant/mic button
  and Google Assistant on a long press.

## The problem

The **Assistant** key (keycode 231, `KEYCODE_VOICE_ASSIST`) and the **mic** key (keycode 219,
`KEYCODE_ASSIST`) never reach our app. TCL's system handles them first and launches Google
Assistant, so our key handling never runs. Tested on the TV: FAIL. The log showed
`TclPhoneWindowManagerEx.startAssistLongPressFunction` handling the key, and the system
sending `android.speech.action.WEB_SEARCH` to Google's search app.

Note: the test sent these keys with `adb shell input keyevent`, not the physical remote.
Which keycode the real button sends was not captured; check it first (for example
`adb shell getevent -l` while pressing it).

## Part 1: Research (do this first, keep it short)

Find out whether there is a reliable way, on this TV, to get those presses to our search.
Look at options like these, and add any others you find:

1. An **accessibility service** with key-event filtering (`FLAG_REQUEST_FILTER_KEY_EVENTS`).
   `RemoteKeyService` already is one (`flagRequestFilterKeyEvents` in
   `app/res/xml/remote_key_service.xml`, `onKeyEvent` in the service), and 231/219 still
   didn't reach it in the test. Confirm the service was enabled
   (`adb shell settings get secure enabled_accessibility_services`); if it was, this option
   is ruled out on this TV unless there's a way around TCL's interception.
2. Registering as the **default assistant / voice interaction app**
   (`android.intent.action.ASSIST`, `VoiceInteractionService`, the assist role) so the system
   launches us instead of Google.
3. Handling `ACTION_ASSIST`, `ACTION_VOICE_COMMAND`, or `android.speech.action.WEB_SEARCH`
   intents and whether TCL routes the button through any of them.
4. ADB-only setup steps (for example `settings put secure assistant ...`,
   `cmd role add-role-holder ...`, `pm disable-user` of the Google Assistant component),
   with how reversible each one is and what else it breaks.
5. Whether the button sends a Bluetooth HID usage that the system intercepts below the app
   layer, which would make this impossible without root.

For each option, write down: does it work on Android TV / Google TV at this OS level, what
setup Ayush has to do, the risks (breaking Google Assistant, a TCL update undoing it, Play
Protect), and how to undo it.

Read these first (they already handle keys, search, and settings):
`app/src/io/freemytv/RemoteKeyService.java`, `RemoteKeys.java`, `KeyPage.java`,
`SearchActivity.java`, `Prefs.java`, `HomeRole.java`, `Adb.java`, `app/AndroidManifest.xml`,
plus `README.md`, `AGENTS.md`, `CLAUDE.md`, and `REVIEW.md` for the project rules.

**Write your findings to `claude/research/assistant-button.md`** with a clear
recommendation at the top (the best option, or "not possible without root" if that's the
answer, with the evidence).

## Part 2: Development (only if research finds a safe option)

1. Open a new branch off `main` and a focused PR into `main` in
   Ayush-Chaudhary/free-my-tv. One feature per PR, clear title and description.
2. Implement the recommended option so that a tap on the Assistant/mic button opens
   `SearchActivity` with the mic listening. Keep the existing `keep_google_home` behavior:
   holding the button still opens Google Assistant when that setting is on.
3. If it needs a one-time setup step from Ayush (enabling an accessibility service, an ADB
   command), add that step to the settings screen or the README in plain words.
4. Run `tools/check.sh` and make sure it passes. Don't break the Search key (84), which
   already works.
5. In the PR description, include how Ayush tests it on the TV and how to undo the change.

## Rules

- Don't install on, connect to, or change Ayush's TV yourself unless he tells you to.
  He or his bots (Rudra, Ren) will run the TV test.
- Don't merge your own PR. Ayush approves merges.
- Keep it lean: research first, and stop and report if the answer is "not possible".

## When you're done

Update the Status line at the top to `research done` or `PR open: <link>`, and add a
short summary to the Log below.

## Log

- 2026-10-28: Task created by Lumi for Ayush, based on Rudra's TV test of main (d36a0cf).

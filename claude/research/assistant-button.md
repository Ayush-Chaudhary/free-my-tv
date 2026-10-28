# The TCL remote's Assistant / mic button: research

TV: Ayush's TCL G10, Google TV, Android 12. Nothing in this round was run on the TV; the evidence is from logs
captured on that TV in an earlier session (24 October 2026) and from how Android handles these keys.

## Recommendation

**Answer the intent, not the key.** The button never reaches an app as a key, but the system turns it into an
`android.speech.action.WEB_SEARCH` intent that it starts the normal way, by resolving it like any other app's
intent. An activity of ours that accepts `WEB_SEARCH` becomes a second answer. Android then asks once
(*Open with*), and *Free My TV* → *Always* makes the button ours. No root, no ADB, no change to Google's apps, and
Google Assistant stays installed and working.

Built in this PR as `VoiceKeyActivity`, off by default, behind *Remote Buttons* → *Assistant button* (TCL only)
and `assistant_button` in `config.properties`.

The one thing that could make it fail: if Google's search app declares its `WEB_SEARCH` filter with a priority
above 0, Android picks Google without asking, and our activity is never offered. That can't be seen from here. The
TV test below checks it in one command.

## Evidence

Log from the TV, pressing the Assistant key while Netflix was in front (earlier session):

```
START u0 {act=android.speech.action.WEB_SEARCH cmp=com.google.android.katniss/com.google.android.apps.tvsearch.app.launch.trampoline.SearchActivityTrampoline} from uid 1000
```

- `from uid 1000`: the system started it, after `TclPhoneWindowManagerEx` took the key (Rudra's log).
- No package or flags are set on the intent. The `cmp=` part is filled in by `ActivityStarter` after resolving, as it
  is for the implicit `HOME` intents in the same log.
- In the same session, our own implicit `RECOGNIZE_SPEECH` intent landed on
  `android/com.android.internal.app.ResolverActivity`: with two answers, this TV shows the chooser as stock
  Android does.
- AOSP's `PhoneWindowManager.launchVoiceAssistWithWakeLock` sends exactly this intent for `KEYCODE_VOICE_ASSIST`
  (231) when the TV isn't locked. TCL's code appears to follow it.

## The options

| Option | Works here? | Setup | Risks | Undo |
|---|---|---|---|---|
| 1. Accessibility key filter (`RemoteKeyService`) | **No.** 219 and 231 are handled in `interceptKeyBeforeQueueing` (TCL's `startAssistLongPressFunction`), before filters run. In the earlier session the service was enabled and bound, saw `KEYCODE_SEARCH` (84) and never saw 231. | none | none | n/a |
| 2. Become the assistant (`cmd role add-role-holder android.app.role.ASSISTANT io.freemytv`, a `VoiceInteractionService`) | Possibly for 219, but replaces Google Assistant for everything: hold-to-talk, "Hey Google", Assistant from the Google TV remote app, voice input in other apps. | ADB once | Google voice stops working everywhere. A TCL update or Google Play update can hand the role back. A full `VoiceInteractionService` is a large piece of code to add. | `cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss` |
| 3. Answer `WEB_SEARCH` (this PR) | **Likely.** It is the intent the button sends. | Pick *Free My TV* → *Always* once, on the TV. | Any app that asks for a web search also comes to us (rare on a TV). If Google's filter has a priority, nothing happens and nothing breaks. | *Assistant button* → *Google Assistant* turns the activity off; Android then drops the *Always* by itself. |
| 3b. `ACTION_ASSIST` / `ACTION_VOICE_COMMAND` | Not what this TV sends for the button (the log shows `WEB_SEARCH`). An `ASSIST` filter also makes us a candidate for the assistant role. | n/a | n/a | n/a |
| 4. Turn Google off (`pm disable-user com.google.android.katniss`, i.e. `disable_assistant=true`) | Yes: we'd be the only answer. | ADB / the switch | Our own voice search uses Google's recognizer (`RECOGNIZE_SPEECH` → katniss), so the mic would stop working in our search too. Remote voice, casting voice and Assistant are gone. Component-level disable of katniss's search activity isn't allowed from the shell. | `pm enable com.google.android.katniss` |
| 5. Below the app layer (Bluetooth HID, kernel) | Not reachable without root. Not needed if 3 works. | root | voids the rules of this repo | n/a |

## How the feature behaves

- **Free My TV mode, setting on, *Always* picked:** a press opens `SearchActivity` with the mic listening. A query in
  the intent (another app's web search) fills the field and searches instead.
- **Original mode:** the activity hands the same intent to Google's app, so the button acts as it always did.
- **Setting off (default):** the activity is disabled in the manifest and Android doesn't know it exists.
- **Holding the button (with `keep_google_home`):** unknown. If TCL sends the same `WEB_SEARCH` for a hold, a hold
  opens our search too, and Google Assistant is one step away through the search screen's *Ask Google Assistant*
  pill. The activity logs the intent's extra names (no values) so the TV test can tell whether a hold looks different.

## TV test (for Rudra or Ren)

```bash
# 0. Before installing: does Google's filter outrank everyone? A "priority=" above 0 on the WEB_SEARCH filter means
#    this approach can't work on this TV.
adb shell dumpsys package com.google.android.katniss | grep -B2 -A6 'android.speech.action.WEB_SEARCH'

# 1. Build with assistant_button=true (or turn it on in Remote Buttons after installing), install as usual.
adb shell cmd package query-activities -a android.speech.action.WEB_SEARCH   # should list both apps

# 2. In Free My TV mode, press the remote's Assistant button. Expect Android's "Open with" list:
#    pick Free My TV, then Always. Our search should open with the mic listening.
adb logcat -s FreeMyTV | grep 'assistant button'

# 3. Press again: straight to our search, no list. Hold the button too, and note what opens.
# 4. Search key (84) still opens our search. Original mode: the button opens Google Assistant.
# 5. Undo: Remote Buttons → Assistant button → Google Assistant. The next press goes to Google, no list.
```

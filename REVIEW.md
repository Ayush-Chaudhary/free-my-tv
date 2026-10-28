# Review: `feat/focused-picks-carousel`

Reviewed against `main` at the merge base `4d73951`. The branch is 17 commits ahead of that base and 233 commits behind current `main`. It does not merge cleanly: `HomeActivity`, `HomeApps`, `HomeMode`, `HomeView`, `Prefs`, `Recs`, `RemoteKeys`, and `build.sh` all conflict. Upstream has since shipped the overlapping pieces this branch also builds: widening cards (`ed0deed`), `screensaver=false` (`bc61ae8`, which does what `own_screensaver` was for), and pick-service switches, the TMDB-key page, Now Playing, and the sleep timer (`29202ee`).

This port keeps the behavior and drops the duplicates. Visual work is listed under [For Ren (UI)](#for-ren-ui).

## Findings

### High

1. **`keep_google_home` can leave Home on Google TV with nothing to take it back.** The switch leaves `com.google.android.apps.tv.launcherx` enabled whenever the flag is set (`HomeMode.java` around the “apps config.properties keeps on” comment, line 713 on the branch). The Home-key takeover only runs inside `RemoteKeyService`, and that service is enabled only where `RemoteKeys.supported()` is true and the filter is safe. TCL on Android 11 never starts it (`FILTER_SAFE` is false there). On every other brand the flag silently leaves Google’s home in front, and the Home button opens it. A system launcher also outranks a normal HOME role holder, which is why the branch intercepts `KEYCODE_HOME` at all.
2. **`RoleManager` is used with no API check** (`RemoteKeyService.java:113-114`). The class is Android 10 (API 29). This app’s minSdk is 26. Loading `RemoteKeyService` on Android 8 or 9 throws `NoClassDefFoundError` the first time `ourHome()` runs, which is every Home press while the flag is on. The import is on line 4, so the class is referenced from the service itself.
3. **Opening a title force-stops the app over the TV’s ADB** (`HomeApps.java:425-428`). The comment says this is for Netflix on a TCL G10: a backgrounded Netflix comes forward on its home page and drops a plain `VIEW` intent. The code stops every package that matches a package-name regex, including Prime Video, Disney+, and YouTube, then starts the activity. `am force-stop` kills the user’s playback, waits on ADB, and is unnecessary once the intent is one the app actually accepts (see [Deep links](#deep-links)). The package is checked against a regex before it is concatenated into the shell command, so this is not an open injection, but it is still a shell command built from a string.

### Medium

4. **The accessibility service asks for window content on every build** (`app/res/xml/remote_key_service.xml:9`, `canRetrieveWindowContent="true"`). Upstream’s service only filters keys. With the flag, the service reads the Assistant’s question and the text of Google TV’s title page (`RemoteKeyService` walk of `getRootInActiveWindow`). That text is not written to disk, but the capability is in the APK even when `keep_google_home` is false, and the README Privacy section does not mention it, Wikidata, or YouTube trailers.
5. **Search spends the Watchmode quota up front, and the cache throws the answers away.** Typing is already debounced (`SearchActivity.java:60` and `:159-164`, 600 ms). What still hurts is `Recs.search` (`Recs.java:231-250`): up to 12 titles, each `fetchDetails` one after another, then `findLink` (two Watchmode calls, or a Wikidata query) for every title that is on an installed app. Watchmode’s free plan is 2,500 requests a month. A few searches can use a large part of it. `Recs.save` (`Recs.java:1068`) then deletes `wm:` and `wd` keys that are not in the hourly pick list, so the next search of the same title pays again.
6. **A trailer WebView crash can take down the home screen.** The player is created in the `:home` process (`HomeView.java:1809-1810`, client at `:2079`) with no `onRenderProcessGone`. If the renderer dies, Android destroys the host process unless that callback returns true. Three such crashes in five minutes and the watchdog restores the stock home. There is also no low-RAM check before creating a WebView that is on the order of tens of MB, on TVs that already run the UI at 720p with 1 GB of RAM.
7. **The “Unable to load details” match is English only** (`RemoteKeyService` `LOAD_ERRORS`). On a TV whose Google TV UI is not English, a failed title page stays on Google’s error screen. A loose match would steal a page that loaded, so this needs one string per language, not a fuzzy search.

### Low

8. **`own_screensaver` duplicates `screensaver=false`.** Upstream already keeps the TV’s screensaver when `screensaver=false`. A second flag would drift from `HomeMode`’s existing order (setup app, stock home, screensaver).
9. **The branch does not pass `tools/translate.py --check` or an updated settings table.** New user-facing strings (“Top 10 Movies”, the search screen, “Apple TV button”) are not in the catalogues. The README settings table has no `keep_google_home` row. Comments still talk about an Explore layout the light home screen no longer draws that way.
10. **TCL Apple TV key `4160` is real and worth keeping**, as a `Prefs` key next to the other TCL codes, with a settings row. It does not belong in a one-off `if` inside the key service.

## Deep links

Tried on a TCL G10 (Google TV, Android 12). Nothing raised `SecurityException`. The plain https title URL with `-p` is what works. `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` (`-f 0x10008000`) is only for Netflix and Disney+. On Prime Video, Max and Hulu it sticks on the profile picker or the app’s home when the app is already open.

| App | Cold | Warm | Form that worked |
|---|---|---|---|
| Netflix `com.netflix.ninja` | fresh start | starts fresh, playback began ~10 s later (the picture is blank under DRM, so the page itself was not seen) | `MainActivity`, `VIEW`, `https://www.netflix.com/title/<id>`, `LEANBACK_LAUNCHER`, extra `source` = `"30"`, `-f 0x10008000`. A plain VIEW only brought the existing screen forward. |
| Disney+ `com.disney.disneyplus` | works | works; clear-task helps | `VIEW` + `BROWSABLE`, `https://www.disneyplus.com/browse/entity-<id>`, `-p`, and `-f 0x10008000` |
| Prime Video `com.amazon.amazonvideo.livingroom` | works (profile picker, then the title) | works the same way | `VIEW` + `BROWSABLE`, `https://app.primevideo.com/detail?gti=<gti>`, `-p`. Clear-task sticks on the profile picker. |
| Max `com.wbd.stream` | works | works | `VIEW` + `BROWSABLE`, `https://play.max.com/show/<id>` or `/movie/<id>`, `-p`. Clear-task fails when warm and drops to the profile picker. |
| Hulu | works without clear-task | clear-task fails and lands on Hulu’s home | `VIEW` + `BROWSABLE`, the title’s https URL, `-p`. No clear-task. |
| YouTube `com.google.android.youtube.tv` | `vnd.youtube:<id>` opens YouTube’s home | same | `VIEW`, `https://www.youtube.com/watch?v=<id>`, `-p`. That started the video. |
| Apple TV `com.apple.atve.androidtv.appletv` | not tried | not tried | plain https URL, `-p`, no clear-task, until a device says otherwise |

```bash
adb shell am start -a android.intent.action.VIEW -n com.netflix.ninja/.MainActivity \
  -d https://www.netflix.com/title/80057281 -c android.intent.category.LEANBACK_LAUNCHER \
  --es source 30 -f 0x10008000

adb shell am start -a android.intent.action.VIEW -p com.disney.disneyplus \
  -c android.intent.category.BROWSABLE \
  -d "https://www.disneyplus.com/browse/entity-ENTITYID" -f 0x10008000

adb shell am start -a android.intent.action.VIEW -p com.amazon.amazonvideo.livingroom \
  -c android.intent.category.BROWSABLE \
  -d "https://app.primevideo.com/detail?gti=amzn1.dv.gti.0a9a9a9a-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

adb shell am start -a android.intent.action.VIEW -p com.wbd.stream \
  -c android.intent.category.BROWSABLE -d "https://play.max.com/show/SHOWID"

adb shell am start -a android.intent.action.VIEW -p com.hulu.livingroomplus \
  -c android.intent.category.BROWSABLE -d "https://www.hulu.com/movie/MOVIEID"

adb shell am start -a android.intent.action.VIEW -p com.apple.atve.androidtv.appletv \
  -c android.intent.category.BROWSABLE -d "https://tv.apple.com/movie/TITLE/umc.cmc.ID"

adb shell am start -a android.intent.action.VIEW -p com.google.android.youtube.tv \
  -d "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

`0x10008000` is `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`. Leave it off Prime Video, Max, Hulu, YouTube and Apple TV.

## What this port changes

- New branch from current `main`. No merge of the 233-commit gap. Rows sit on the existing widening cards: on Movies and Shows, up and down move between Top 10, New on your apps, and the genre rows. Explore stays the hourly mix.
- `own_screensaver` is not added. `screensaver=false` is the switch that already exists.
- `keep_google_home` is ignored unless `RemoteKeys.keepGoogleHome()` is true (supported brand, filter safe, preference on). Elsewhere Google’s home is turned off as before. The HOME-role check lives in `HomeRole.Held` and is not loaded below API 29.
- Window content is compiled into the accessibility XML only when `keep_google_home=true`. Other builds stay key-only.
- Search waits 700 ms, asks TMDB once per query, and resolves which app has each hit in parallel. Watchmode and Wikidata run when the title is opened, links live in their own preferences (a row save does not delete them), and Watchmode stops at 2,000 requests in a calendar month.
- Title opens go through `TitleLinks`. Nothing is force-stopped.
- `TrailerPlayer` returns true from `onRenderProcessGone`, and does not create a WebView when `ActivityManager.isLowRamDevice()` is set or the memory threshold is very low. The blur uses reflection so the class still loads on Android 8.
- TCL key `4160` is the Apple TV button in Remote Buttons.
- README Privacy and the settings table mention `keep_google_home`, Wikidata, YouTube trailers, and the window-content read. Catalogues pass `translate.py --check`.

## For Ren (UI)

The structure is in place. The look is not redesigned.

- Movies and Shows rows reuse the existing card row. The row name is drawn in the tab row’s existing type. There is no vertical list of rows on screen at once, no new card shape, and no focus animation beyond the widening cards already on `main`.
- The trailer pop-out uses the player’s own scrim and 16:9 window. Spacing, type, and how it sits on the card are the first version of that behavior, not a design pass.
- Search is a plain full-screen list (apps, then titles) so the feature works. It does not use the home screen’s glass, type ramp, or focus treatment.
- `keep_google_home` is a `config.properties` key and a `Prefs` boolean (`keepGoogleHome`, default from that key). There is no Home Settings row for it yet. A row should say, in plain language, that Google’s home stays available for Assistant, and that it only applies on TVs where Remote Buttons runs.
- The same home screen on LG and Samsung (`web/`) does not have the rows, trailers, or search. That port is separate.

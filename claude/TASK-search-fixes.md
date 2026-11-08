# Task for Claude: follow-ups from the TV review of PR #17

**Deliverable:** one small PR from `fix/search-followups` (off `main`, after phase 2, `b241451`). Do not merge it. Only
Ayush's fork and his TCL G10; no `web/`, no secrets, `config.properties` stays gitignored.

## Fixes (`SearchActivity.java`, with `Recs.java` and a one-line touch in `SearchCanvas`/`AssistantTurn`)
1. **Stale requests.** A new query hid old results but left the old Worker and provider requests running, so fast
   typing could fill all four background threads. Now every query's work is tracked (`work`), and the next query
   (`cancelWork`) cancels queued tasks and interrupts running ones; each task also checks the generation before it starts.
2. **Provider lookups.** The TV waits 4 s for watch-provider lookups, but a TMDB call could take 8 s + 8 s, so a title
   on one of Ayush's apps could stay on "Find where to watch". `Recs.fillApp` now gives its TMDB call about 3 s
   (1 s connect, 2 s read). A lookup still running after the wait keeps going and, if it lands late, fills in that
   card's badge and "Open in {service}" and redraws (`lateApp`, `SearchCanvas.hitChanged`). A new query stops it.
3. **Voice language.** If the recognizer refuses `en-IN` (`ERROR_LANGUAGE_NOT_SUPPORTED`/`_UNAVAILABLE`), it listens
   again once in `en-US`. The system voice screen fallback now also asks for `en-IN` (Indian English and Hinglish).

## Check
`./build.sh` ends with `Built FreeMyTV.apk`; `tools/check.sh` passes. Test on the G10 (see the PR checklist).

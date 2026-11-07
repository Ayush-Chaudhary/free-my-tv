# Task for Claude: Phase 2, wire the TV app to the assistant Worker

**Deliverable:** one PR from a fresh branch off `main` (this file is the brief; the work starts after `2a9adee`, the Cinema search screen, PR #15). Wire the Android TV app to the Worker and drop `service` from the Worker in that same PR. Do not merge it.

Follow `claude/research/ai-assistant.md`, section **Phase 2: TV app fills in `service`**, and the v1 contract in `assistant/README.md`. The screen already exists: `SearchActivity`, `SearchCanvas`, `AssistantTurn` (parse only, no network), `Recs.searchHits` and `Recs.fillService`.

## Scope rules
- Only for Ayush's fork and his TCL G10 (Google TV, Android 12). No upstream contributions, and no `web/` (LG webOS / Samsung Tizen).
- Free tiers only: Cloudflare Workers Free, Groq free, TMDB free. Don't add a card or start a paid plan anywhere.
- No secrets in the repo. `config.properties` and the keystore stay gitignored. Don't touch the release keystore or signing.
- One focused PR with a clear title and description. The description includes a short manual test checklist for the G10. Testing is on that TV.

## What to build
1. **Call the Worker from the Cinema search screen.** For both `recommend` and `search`, `POST https://free-my-tv-assistant.ayush-fmtv.workers.dev/v1/turn` with `Authorization: Bearer <token>`, body as in `assistant/README.md` (`v`, `query`, `lang`, `region`, `services`, `history`). Parse with `AssistantTurn.fromJson` and show it through `SearchActivity.present`. Handle 400, 401, 429, 502, 503 and 504 without crashing. On 503, 504, or a timeout of about 6 s, fall back to the plain TMDB title search the screen already uses (`Recs.searchHits`). The reply is not streamed: it arrives whole, at most 220 characters (`AssistantTurn.REPLY_MAX`). Keep the client-side type-out `SearchCanvas` already draws.
2. **`assistant_url` and `assistant_token`.** Read them from gitignored `config.properties`, next to `tmdb_key`. This project has no Gradle `BuildConfig`: `build.sh` writes `out/gen/io/freemytv/Config.java`. Add them the same way as `Config.TMDB_KEY`. Never commit the values. Add empty placeholders to `config.example.properties` (the template `build.sh` copies). The URL above is the deployed Worker; the token is the one `assistant/setup.sh` printed once.
3. **Streaming-service lookup on the TV.** For each pick, call TMDB `/{movie|tv}/{id}/watch/providers` with the app's own TMDB key (`Recs.key()`). Read the device region's flatrate list (`Config.TMDB_REGION` / `Recs.REGION`). Map it to an app installed on the TV with `PackageManager` and the existing table `Recs.SERVICES` (the same matching `Recs.fetchDetails` already does). Cache the results in memory for the session. A pick with no match gets no badge.
4. **Buttons.** Keep the labels the screen already has: `Open` (and `Open in {service}`) when the title is on an installed app, which deep-links or launches that app (`HomeApps.open`, `Recs.ensureLink`); `Find where to watch` for the rest (`SearchCanvas.openLabel`).
5. **Drop `service` from the Worker, in this PR.** Remove it from the `/v1/turn` response, and remove the per-title provider fetch, byte slicing and cache (`tmdb.serviceFor`, `flatrateFromBytes` / `flatrateIn`, and the `watch/providers` cache in `assistant/src/tmdb.js` and `assistant/src/turn.js`). Keep discover's `with_watch_providers`, `watch_region` and flatrate filters (`providerIds` stays: that is the region catalogue, not the per-title lookup). Update `assistant/README.md` and the Worker tests together (`assistant/test/turn.test.js`, `contract.test.js`, `cache.test.js`). `npm test` in `assistant/` must pass.
6. **Voice.** `SpeechRecognizer` with language `en-IN` (`RecognizerIntent.EXTRA_LANGUAGE`, and the preference extra), so Indian-accented English and Hinglish come through. The Worker already replies in plain English. Keep the mic-level animation smoothed from `RecognitionListener.onRmsChanged` (about −2 to 10 dB) into `SearchCanvas.setLevel`.

## Check
- `./build.sh` ends with `Built FreeMyTV.apk`. `npm test` passes in `assistant/`. `tools/check.sh` passes. Don't change `web/`.
- On the G10: a spoken "something funny for tonight" shows a typed-out reply and picks; a title on an installed app says Open and opens it; one that isn't says Find where to watch and shows no badge. With the Worker down, or after about 6 s, the screen falls back to plain TMDB search. A wrong token does not crash the screen.

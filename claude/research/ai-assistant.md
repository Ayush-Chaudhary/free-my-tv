# AI assistant for Free My TV: the plan

For Ayush's TCL G10 (Google TV, Android 12), this fork only. A plan for review: no app code yet.
Written 28 October 2026. Free-tier figures change often; each one below says where it came from, and the
ones marked *verify* should be checked in the provider's console before phase 1.

## In short

- **Start:** the remote's TCL Channel button (set to *Search*) or the search screen's mic. Android's speech
  recognizer turns speech into text, as it does today.
- **Backend:** a small **Cloudflare Worker** (free plan, no cold starts). It holds the LLM and TMDB keys and runs
  two LLM calls per turn:
  1. Turn the request into TMDB queries.
  2. Pick 3–5 titles from what TMDB returned, and write a one- or two-sentence reply.
- **Model:** **Groq** (Llama 3.3 70B or gpt-oss-120b) first, because its free tier doesn't train on prompts.
  **Gemini Flash-Lite** is the fallback, paid only: its free tier lets Google train on and review prompts.
- **On the TV:** the reply shows in the search screen, which already has a slot for it
  (`SearchCanvas.show(..., reply)`), and is read aloud with Android text-to-speech.
  - The picks are ordinary `Recs.Pick` cards, so opening them uses the existing path:
    `fillService` → `ensureLink` → `HomeApps.open` → `TitleLinks.view`.
  - If the backend fails or takes over 6 seconds, the screen shows today's plain search.
- **Cost:** $0 a month at Ayush's use. The first paid step would be Workers Paid ($5 a month), and only if TMDB
  parsing goes over the free plan's 10 ms CPU limit.
- **Phases:** six small PRs. Each one works on its own and is tested on the G10.

## What already exists (and is reused)

| Piece | Where | Used for |
|---|---|---|
| Speech-to-text, in-app listener with live words | `SearchActivity.listen()` (`SpeechRecognizer`, partial results) | the request text |
| Remote button → search, listening | `RemoteKeys` (`Prefs.SEARCH`), `SearchActivity.startVoice` (PR #5) | how the assistant starts |
| Text-to-speech | `SearchActivity.speak()` (`TextToSpeech`, `QUEUE_FLUSH`) | the spoken reply |
| A reply line under the search bar | `SearchCanvas.show(hits, status, reply)` | the written reply |
| Title cards that open in their app | `SearchCanvas.Hit.pick`, `Recs.fillService`, `Recs.ensureLink`, `HomeApps.open`, `TitleLinks.view` | the picks |
| The TV's streaming apps | `Recs.installedServices()` → `{key, name}` | which services the backend may recommend from |
| A phone page for typing a secret into the TV | `KeyPage` (Home Settings → TMDB key) | entering the assistant token without a rebuild |

**The Google Assistant button can't be used** (`claude/research/assistant-button.md`): the G10's voice remote
sends it straight to Google. The assistant starts from the TCL Channel button set to *Search*, from the search
screen's mic, or from the Search key (84).

## 1. Hosting and cost

| Option | Free tier | Cold start | Fit |
|---|---|---|---|
| **Cloudflare Workers** (chosen) | 100,000 requests a day; 10 ms CPU per request, and time spent waiting on `fetch` doesn't count; 50 subrequests per request. [Limits](https://developers.cloudflare.com/workers/platform/limits/) | none (V8 isolates) | Most of a turn is waiting on Groq and TMDB, which doesn't count as CPU. The 50-subrequest cap is enough: a turn makes about 2 LLM calls and 6–10 TMDB calls. |
| Vercel (Hobby) | generous, but the free plan is for non-commercial use only | small on Edge, about 1 s on Node | fine, but no advantage |
| Google Cloud Run | 2 million requests a month free | 1–3 s from zero, unless a minimum instance is paid for | too slow for a TV if it scales to zero |

- **Cost at Ayush's use:**
  - Assume 20–40 assistant turns a day, about 1,200 a month. Each turn is about 2 LLM calls and 3,000 tokens.
  - Every free tier above covers that many times over.
- **Paid triggers:**
  - **Workers Paid:** $5 a month, with 10 million requests and 30 million CPU-ms included
    ([pricing](https://developers.cloudflare.com/workers/platform/pricing/)). Needed only if parsing TMDB's JSON
    goes over 10 ms of CPU. Phase 1 measures this; trimming responses with `append_to_response` and only the
    needed fields should keep it under.
  - **Paid LLM:** if the free LLM's daily cap is hit (see §7). Gemini Flash-Lite and Groq both cost a few cents
    a million tokens, so about 4 million tokens a month would be well under $1 (*verify* current prices).
- **Latency target:** under 3 seconds from the end of speech to the reply. Groq answers in well under a second
  per call; TMDB calls run in parallel, about 300 ms. The TV shows "Thinking…" and today's plain search hits
  straight away, so the screen is never blank.

## 2. API keys and how the TV signs in

- **The LLM and TMDB keys** live only as Worker secrets (`wrangler secret put GROQ_KEY`, `TMDB_KEY`). They're
  never in the repo, the APK or `config.properties`.
  - The TV keeps its own TMDB key for the picks it already makes; the backend uses a separate one.
- **The TV signs in with a long random token** (32 bytes, base64url) sent as `Authorization: Bearer …`.
  - The Worker stores only its SHA-256 (`DEVICE_TOKEN_HASH`) and compares in constant time.
  - The token can only reach this Worker. If it leaks, the worst case is someone using up the free quota, which
    the rate limit below caps.
- **Setup** (one time, from Ayush's Mac):
  1. `assistant/setup.sh` makes the token, prints it once, and sets the Worker's hash with `wrangler`.
  2. The token goes into `config.properties` as `assistant_token` (gitignored), or is typed on the TV through
     *Home Settings → Assistant* on the `KeyPage` QR page, which avoids a rebuild.
  3. The Worker URL goes into `config.properties` as `assistant_url`. It's not a secret.
- **Rotation:** rerun `setup.sh` (new hash on the Worker), then enter the new token on the TV. The old token stops
  working at once. Groq or TMDB keys rotate with `wrangler secret put`, with no change on the TV.
- **Rate limit:** 300 turns a day and 10 a minute per token, counted with Cloudflare's Rate Limiting binding
  (free). Over the limit, the TV gets a 429 and falls back to plain search.
- **Logs:** the Worker logs status codes and timings only, never the request text. `observability` stays off,
  or is set to errors only.

## 3. Follow-up conversation

- **History lives on the TV, in memory, in `SearchActivity`.** The Worker stays stateless: no KV, nothing stored
  on the server.
- **Each request sends the last 6 turns,** trimmed to about 2,000 tokens. Each turn holds what Ayush said, the
  reply, and the picks shown as `{n, id, kind, title, year}`. That lets "more like the second one" and
  "something shorter" resolve: pick #2 is in the history with its TMDB id.
- **It ends** when the search screen closes, after 10 minutes without a turn, or when Ayush says "start over".
  The *clear* key on the keyboard also clears it.
- **Not saved to disk.** It stays out of `Prefs.recentSearches`, which keeps only typed and plain searches, as
  today.

## 4. Fitting into the search screen

- **One screen, no new activity.** `SearchActivity.run(query, voice)` decides:
  - A **typed** query, or a voice query that's an app command ("open Netflix", already handled by `command()`),
    goes the way it does today.
  - A **voice** query, with `assistant_url` set, goes to the assistant. Today's TMDB title search runs at the
    same time, so the cards are ready if the backend fails.
  - A setting, *Home Settings → Assistant: Voice / Off*, can turn it off.
- **While waiting:** the status line says "Thinking…" and the plain hits show.
- **When the reply comes:**
  - The reply goes in the `reply` slot and is spoken.
  - The picks replace the plain hits as cards: `SearchCanvas.Hit` with `pick` set and `meta` showing the app.
  - OK on a card opens it as search cards do today.
- **Talking again:** pressing the button again, or the on-screen mic, continues the conversation.
- **Fallback:** if there's no network, a 429, a timeout (6 s), or a reply that doesn't parse, the plain search
  results stay. The status line says "The assistant isn't answering. Here's a plain search." Nothing else
  changes, and the home screen never depends on the backend.
- **Typed questions:** the keyboard's search key on a typed query stays plain search. A small "Ask" pill can
  send the typed text to the assistant; that's phase 6, if Ayush wants it.

### The JSON contract (TV ↔ Worker)

Request:

```json
{"v": 1, "query": "something funny and short for tonight", "lang": "en", "region": "US",
 "services": ["netflix", "prime", "disney", "max", "hulu", "appletv", "paramount", "youtube"],
 "history": [{"said": "a good thriller", "reply": "Try these.", "picks": [{"n": 1, "id": 1396, "kind": "tv", "title": "Breaking Bad", "year": 2008}]}]}
```

Response:

```json
{"reply": "Here are three short comedies on your apps.", "intent": "recommend",
 "picks": [{"id": 1268, "kind": "movie", "title": "Palm Springs", "year": 2020, "service": "hulu", "why": "90-minute time-loop comedy"}]}
```

- **`intent`** is one of:
  - `recommend` or `search`: show the picks.
  - `open_app` (with `"app": "netflix"`): open the app, as "open Netflix" does today.
  - `chat`: reply only, no picks.
- **Picks are checked:** the Worker returns only ids that came back from its own TMDB calls this turn, so the
  LLM can't make up a title. `service` is set only when TMDB's watch providers for `region` list one of
  `services`.
- **On the TV,** each pick becomes a `Recs.Pick` (`id`, `kind`, `title`). `fillService` fills the rest from the
  TV's TMDB cache, and opening it uses the existing path.

### Inside the Worker (one turn)

1. **Plan:** the LLM gets the system prompt, the history and the request. It returns JSON only:
   `{"intent", "searches": [titles], "discover": {kind, genres, runtime_lte, year_gte, ...}, "like": tmdb id}`.
   This uses Groq's JSON mode, with no free-form tool loop.
2. **Fetch:** the Worker calls TMDB in parallel: `/search/multi`, `/discover/*` with `with_watch_providers` for
   Ayush's services and `watch_region`, and `/{kind}/{id}/recommendations` for "more like…". Then it gets
   `watch/providers` for up to 15 candidates.
3. **Choose:** the LLM gets the candidate list (id, title, year, runtime, genres, one-line overview, service) and
   returns `reply` and 3–5 ids from the list, with a 3–6 word `why` for each.
4. **Check:** the Worker drops unknown ids and caps the reply at 220 characters before returning it.

- **TMDB tools:** the TMDB call shapes can follow `mcp-server-tmdb` (MIT: copy with its licence notice, or
  rewrite). The Worker calls TMDB directly; there's no MCP server.

## 5. Recommendations and data sent

- **Sent each turn:**
  - the request text and the history above;
  - the language and TMDB region from `config.properties`;
  - the **service keys** of the TV's installed streaming apps (`Recs.installedServices`, minus services turned
    off in *Picks from*).
- **Not sent:** the device, account, IP address beyond what any HTTPS request shows, app list beyond those
  service keys, watch history, or location.
- **Taste (phase 6, opt-in):**
  - The app doesn't keep a watch history today. *Assistant → Use what I open* would keep the last 20 titles
    opened from Free My TV, on the TV only (`Prefs`), and send their titles with each turn.
  - Off by default.
- **README privacy section** (in the PR that turns the assistant on, not now):
  > With an `assistant_url` set, a voice search goes to your own assistant service (a Cloudflare Worker you
  > deploy). It sends what you said, the last few turns of that conversation, your language and region, and which
  > streaming services the TV has. The service passes it to the LLM provider you set (Groq by default) and to
  > TMDB, and keeps nothing. With *Use what I open* on, it also sends the titles of the last 20 things you opened
  > from Free My TV. Typed searches don't use it.

## 6. Spoken output

- **Engine:** Android `TextToSpeech` with the TV's default engine. On the G10 that should be Google's
  (`com.google.android.tts`), which is kept on that TV. *Verify* with `adb shell settings get secure tts_default_synth`
  and by hearing a sample.
- **Length:** at most two sentences, about 25 words and 8 seconds. The picks are on screen, so the voice doesn't
  read out lists, only "Here are three short comedies; Palm Springs is the shortest."
- **Never while listening:** speaking starts after the recognizer has stopped. A new mic press stops the speech
  (`tts.stop()`), so the TV doesn't hear itself.
- **Trailers:**
  - The search screen has no trailer. The home screen's trailer player pauses when the search screen covers it
    (*verify* on the TV).
  - TTS asks for transient audio focus with ducking (`AudioAttributes.USAGE_ASSISTANT`). Any other audio, such as
    a title's own app already playing behind, dips while the reply is spoken.
- **Mute option:** *Assistant: Voice / Text only / Off*.

## 7. Risks and unknowns

| Risk | Detail | What to do |
|---|---|---|
| **Free LLM limits change** | Groq's free plan is reported as 30 requests a minute and 1,000 a day for Llama 3.3 70B, and 14,400 a day for Llama 3.1 8B. These are third-party reports (August 2026); Groq's own page shows its Developer plan. Gemini's free Flash-Lite is reported at 500 a day (October 2026), down from 1,000, and Google no longer publishes a table. | At 2 calls a turn, Groq's 1,000 a day is about 500 turns: plenty. *Verify* in the Groq console and AI Studio. On 429, fall back to Llama 3.1 8B, then plain search. |
| **Gemini free tier: training and human review** | Google's [terms](https://ai.google.dev/gemini-api/terms): on unpaid services, prompts may be used to improve products and read by human reviewers. Users must be 18+, and apps "likely to be accessed by" minors are not allowed. Family members use the TV. | Use Gemini only on the paid tier. Groq first: it reports no training on API data, logs kept up to 30 days for abuse, and zero retention available in Data Controls (third-party summaries; *verify* in Groq's Services Agreement). |
| **10 ms CPU on Workers Free** | Parsing several TMDB JSON responses could cross it. | Measure in phase 1 (`wrangler tail` shows CPU). Trim fields; if needed, Workers Paid at $5 a month. |
| **Speech recognizer on the G10** | It works today with mic permission (PR #5). Each recognition is one utterance, so continuous conversation needs a press per turn. | Fine: press to talk is how the remote works. |
| **Mic permission** | Granted on the G10 now. A reinstall that clears data would ask again. | `adb install -r -d` keeps data. |
| **Title links without Watchmode** | No `watchmode_key` today, so only titles Wikidata maps open on their own page (Netflix, Prime Video, Disney+, Max, per #7). The rest open the app's home. | Say so in the card ("Opens Hulu"). Optional: a free Watchmode key, which the app caps at 2,000 calls a month. |
| **Wrong service** | TMDB's watch providers can be out of date for a region. | Show the app on the card; if the app opens without the title, Ayush can search inside it. Same as today's picks. |
| **Licences** | Local Voice (Apache-2.0) and mcp-server-tmdb (MIT): code may be copied with notices. FrameIQ (AGPL-3.0) and Dicio (GPL-3.0): ideas only, no code; this repo is MIT. Checked with `gh api` on 28 October 2026. | Local Voice isn't needed: the in-app `SpeechRecognizer` already captures the mic. |
| **Cost surprise** | None on free plans: Cloudflare and Groq free plans don't bill. | Don't add a card to Groq or Google until needed; set a budget alert if a paid tier is turned on. |
| **Outage** | The Worker, Groq or TMDB is down. | Plain search fallback, already designed in §4. |

**To test on the TV:**
- the recognizer's accuracy with titles;
- TTS voice and loudness;
- audio ducking over an app playing;
- end-to-end latency over the TV's Wi-Fi;
- the trailer pausing when search opens.

## 8. PRs, in order

Each PR is on a fresh branch off `main` and is one topic. App PRs: `tools/check.sh` passes, installed with
`adb install -r -d`, and tested on the G10.

1. **`assistant/`: the Worker, deployable on its own.** A Cloudflare Worker with `wrangler.toml`, `setup.sh`
   (token and secrets), the bearer check, the rate limit, Groq plan and choose calls, TMDB fetches and the JSON
   contract. Unit tests run with `node --test`, against recorded TMDB and Groq responses.
   - *Test:* `curl` the deployed Worker with the token: "a funny movie under 100 minutes" returns 3–5 real TMDB
     ids on Ayush's services, in under 3 s. A wrong token returns 401; over the limit returns 429.
     `wrangler tail` shows CPU under 10 ms.
2. **App: settings and client, no visible change.** `assistant_url` and `assistant_token` in `build.sh` and
   `config.example.properties`, *Home Settings → Assistant* (token via `KeyPage`), and `Assistant.java`
   (HttpURLConnection, 6 s timeout, parse, error mapping).
   - *Test:* on the G10, a debug broadcast (`am broadcast -a io.freemytv.ASK --es q "..."`, debug builds only)
     logs the reply and picks to `adb logcat -s FreeMyTV`. Wi-Fi off logs a clean failure.
3. **App: voice search asks the assistant.** In `SearchActivity` and `SearchCanvas`: "Thinking…", reply and
   picks as cards, plain search alongside, the fallback, and the TTS rules (stop on mic, ducking). Also the
   README privacy text and the *Voice / Text only / Off* setting.
   - *Test:* TCL Channel button → "something funny for tonight": a reply is spoken, 3–5 cards show, and OK opens
     one (its page for Netflix, Prime, Disney+ or Max; the app's home otherwise). With Wi-Fi off it shows plain
     search with the notice. Typed search is unchanged.
4. **App: follow-ups.** In-memory history (6 turns, 10-minute expiry, "start over") sent with each request.
   - *Test:* "a thriller" → "something shorter" → "more like the second one" each give sensible picks. Closing
     search and asking again starts fresh.
5. **App: spoken reply polish.** Reply length, the voice, audio focus over another app's audio, and checking the
   trailer pause.
   - *Test:* with YouTube playing behind, the reply ducks it. The trailer on home is paused while search is open.
6. **Optional: taste and typed asking.** *Use what I open* (opt-in, last 20 titles, on the TV only) and an "Ask"
   pill for typed text. README privacy updated again.
   - *Test:* with it on, "something like what I watch" leans to recent titles; off sends none (checked in
     `wrangler tail` request sizes).

## Sources

- Cloudflare Workers [limits](https://developers.cloudflare.com/workers/platform/limits/) and
  [pricing](https://developers.cloudflare.com/workers/platform/pricing/).
- Gemini API [terms](https://ai.google.dev/gemini-api/terms) and
  [rate limits](https://ai.google.dev/gemini-api/docs/rate-limits) (limits are shown per project in AI Studio).
  Reported free figures: [aiplug.work](https://aiplug.work/en/blog/gemini-free-tier-limits),
  [klymentiev.com](https://klymentiev.com/blog/gemini-api-free-tier).
- Groq [rate limits](https://console.groq.com/docs/rate-limits) (shows the Developer plan). Reported free figures:
  [grizzlypeaksoftware.com](https://www.grizzlypeaksoftware.com/articles/p/groq-api-free-tier-limits-in-2026-what-you-actually-get-uwysd6mb),
  [ampm-aiops.com](https://ampm-aiops.com/en/guides/groq-review-2026/). Data use:
  [apistatuscheck.com](https://apistatuscheck.com/blog/groq-api-data-privacy-guide),
  [developersdigest.tech](https://www.developersdigest.tech/blog/which-ai-apis-train-on-your-data).
- Licences: GitHub's API for privatevoice/local-voice (Apache-2.0), Laksh-star/mcp-server-tmdb (MIT),
  RobinMillford/FrameIQ (AGPL-3.0), DicioTeam/dicio-android (GPL-3.0).

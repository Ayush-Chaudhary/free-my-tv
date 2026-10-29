# Task for Claude: plan the AI assistant for Free My TV

**Deliverable:** a written plan in `claude/research/ai-assistant.md`. **Do not write app code yet.** Open a PR with just the plan, or commit it to a branch, and Ayush will review it before any build work starts.

## Goal
Build a conversational assistant into Free My TV on Ayush's TV (TCL G10 Google TV, Android 12) that replaces Google Assistant as far as possible. It uses a cloud-hosted LLM to talk with Ayush, recommend movies and shows, and open the picks in the right streaming app.

## Scope rules
- Only for Ayush's fork (`Ayush-Chaudhary/free-my-tv`) and his TCL G10. No upstream contributions, and no `web/` (LG webOS / Samsung Tizen) work.
- Free tiers first. For anything paid, say what the paid tier costs and when we'd need it.
- No secrets in the repo. `config.properties` and the keystore stay gitignored.

## Known limit
TCL's `TclPhoneWindowManagerEx` catches the remote's Assistant button (keycodes 231/219) and opens Google Assistant. It can't be taken over without root (see `claude/TASK-assistant-button.md` and PR #5). So the assistant starts from the app's mic button, or from a remote button set to *Search* in *Remote Buttons* (added in PR #5). Keycode 84 (SEARCH) does reach the app.

## Proposed architecture (starting point: confirm or improve it)
1. **On the TV:** Android speech-to-text in the app (the existing search mic flow in `SearchActivity`).
2. **Cloud backend:** a small service that runs an LLM with TMDB tool calls and returns a short spoken reply plus 3–5 picks (TMDB id, kind, title, and which of Ayush's apps it's on, if known).
   - LLM candidates: the Gemini Flash-Lite free tier, or the Groq free tier (about 1,000 requests a day, which is Lumi's figure; please verify both providers' current limits and terms).
3. **Back on the TV:** the app reads the reply aloud with Android TTS and shows the picks. A pick opens through the existing deep-link path (`Recs.ensureLink`/`findLink` → `HomeApps.open` → `TitleLinks.view`, using Watchmode and Wikidata). Note that there's currently **no Watchmode key** in `config.properties`, so only titles Wikidata covers open their own page. Everything else opens the app's home page. Factor that in.

## Repos to borrow from (check licenses before copying)
- [Local Voice](https://github.com/privatevoice/local-voice) (Apache-2.0): mic capture.
- [mcp-server-tmdb](https://github.com/Laksh-star/mcp-server-tmdb) (MIT): TMDB tool definitions.
- [FrameIQ](https://github.com/RobinMillford/FrameIQ) (AGPL): prompt ideas only. Don't copy code.
- [Dicio](https://github.com/DicioTeam/dicio-android) (GPL): reference only. Don't copy code.

## The plan should cover
1. **Hosting choice and cost:** free options first (for example Cloudflare Workers, Vercel, or Cloud Run free tiers), plus cold starts, latency on a TV, and the monthly cost at Ayush's likely use.
2. **API key handling:** keep LLM and TMDB keys on the backend, never in the APK. Explain how the TV authenticates to the backend, and how keys are set up and rotated.
3. **Follow-up conversation:** multi-turn context ("something shorter", "more like the second one"), where the history lives, and its size limits.
4. **Hooking into existing search:** how voice search, the assistant, and the current search screen share UI and code. Cover the fallback when the backend is unreachable (plain search as today).
5. **Recommendations:** how the backend knows Ayush's apps and taste (installed apps from `HomeApps`, any existing taste and picks data in the app) while keeping data use minimal. Update the README privacy section to match.
6. **Spoken output:** Android TTS on the G10, reply length, and whether TTS interrupts trailers.
7. **Risks and unknowns:** rate limits, provider terms, licensing, and anything that needs testing on the TV.
8. **Phased PR breakdown:** small, focused PRs (one per piece of work, each on a fresh branch off main), in order, each with a short description and a test plan on the TCL G10.

## Build notes for later phases
- Build with `build.sh` (no Gradle) using JDK 17, and check with `tools/check.sh`.
- Install with `adb install -r -d` so app data is kept. Never uninstall the app or clear its data.

## Log

- 2026-10-28: Claude. Plan written in `claude/research/ai-assistant.md`:
  - a Cloudflare Worker backend (Groq first, Gemini paid as fallback) with the keys kept on the backend;
  - a per-TV token, conversation history kept on the TV, and the existing search screen and title links;
  - six phased PRs. No app code.

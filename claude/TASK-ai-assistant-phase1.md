# Task for Claude: build Phase 1 of the AI assistant (the `assistant/` Worker)

**Deliverable:** a PR from a fresh branch off `main` that adds `assistant/`, a Cloudflare Worker that deploys on its own. **No app code in this PR.** Phases 2 to 6 of the plan come later, each as its own PR.

Follow the plan in `claude/research/ai-assistant.md`: sections 1, 2 and 4 for the design, and item 1 of section 8 for the scope and the test.

## Scope rules
- Only for Ayush's fork (`Ayush-Chaudhary/free-my-tv`) and his TCL G10. No upstream contributions, and no `web/` (LG webOS / Samsung Tizen) work.
- Free tiers only. Don't add a card or start a paid plan anywhere (Cloudflare, Groq, Google or any other).
- No secrets in the repo or the APK. The Groq and TMDB keys exist only as Worker secrets (`wrangler secret put GROQ_KEY`, `TMDB_KEY`). `config.properties` and the keystore stay gitignored and untouched.
- One focused PR with a clear title and description.

## What to build in `assistant/`
1. **`wrangler.toml`** for the Workers Free plan, with the Rate Limiting binding. Observability off, or errors only.
2. **`setup.sh`** (run once from Ayush's Mac): makes a 32-byte base64url device token, prints it once, and stores only its SHA-256 as the `DEVICE_TOKEN_HASH` secret via `wrangler`. It also prompts for `GROQ_KEY` and `TMDB_KEY` with `wrangler secret put`. Rerunning it rotates the token.
3. **Auth:** `Authorization: Bearer <token>` is hashed and compared in constant time with `DEVICE_TOKEN_HASH`. A wrong or missing token gets 401.
4. **Rate limit:** 300 turns a day and 10 a minute per token. Over the limit gets 429.
5. **One turn**, following "Inside the Worker" in section 4:
   - **Plan** call to Groq in JSON mode (no free-form tool loop), returning `intent`, `searches`, `discover` and `like`.
   - **Fetch** from TMDB in parallel: `/search/multi`, `/discover/*` with `with_watch_providers` and `watch_region`, `/{kind}/{id}/recommendations`, then `watch/providers` for up to 15 candidates. Trim to the fields needed so CPU stays under 10 ms.
   - **Choose** call to Groq in JSON mode, returning `reply` and 3 to 5 ids with a 3 to 6 word `why` for each.
   - **Check:** return only TMDB ids that came back from this turn's own TMDB calls, drop anything else, set `service` only when the region's watch providers list one of the request's `services`, and cap `reply` at 220 characters.
   - On a Groq 429, fall back to Llama 3.1 8B. If that fails too, return an error status the TV will map to plain search.
6. **The v1 JSON contract** exactly as in section 4: the request carries `v`, `query`, `lang`, `region`, `services` and `history`, and the response carries `reply`, `intent` (`recommend`, `search`, `open_app` with `app`, or `chat`) and `picks`.
7. **Logs:** status codes and timings only, never the request text.
8. **Tests:** unit tests run with `node --test` against recorded Groq and TMDB responses (fixtures in the repo, with no keys in them). Cover auth, the rate limit, the contract, dropping made-up ids, the reply cap and the fallbacks.
9. **`assistant/README.md`:** setup, deploy, rotation and how to run the tests.

## Check before building
- **Groq's free limits:** check them in the Groq console, since the plan's figures come from third-party reports. Record what you find in the PR description.
- **Watchmode is out of date in the plan.** Ayush now has a Watchmode key in `config.properties` on his Mac, so the "Title links without Watchmode" risk in section 7 no longer applies. Title links already open Max and Prime title pages on the TV. The Worker doesn't need Watchmode.

## Deploying and the curl test
Deploying needs Ayush's own Cloudflare and Groq accounts, plus a TMDB key for the Worker. **Don't sign up for anything or create accounts.** When you reach deployment, stop and list exactly what Ayush must create or copy (free plans only), and the commands he runs (`setup.sh`, `wrangler deploy`).

Once it's deployed, put these results from the section 8 test in the PR description:
- `curl` with the token and "a funny movie under 100 minutes" returns 3 to 5 real TMDB ids on Ayush's services, in under 3 s.
- A wrong token returns 401.
- Going over the limit returns 429.
- `wrangler tail` shows CPU under 10 ms.

If deployment has to wait for Ayush's accounts, open the PR with the unit tests passing and mark the curl results as pending.

# Free My TV assistant: the Worker

The backend for the AI assistant (plan: [`claude/research/ai-assistant.md`](../claude/research/ai-assistant.md)). A
Cloudflare Worker on the **Workers Free plan** that takes what you said on the TV and answers with a short reply and
3 to 5 titles. The TV app doesn't use it yet: this is phase 1, and it works on its own with `curl`.

It holds the Groq and TMDB keys, so neither is in the app or the repo. No sign-up is needed to read or test the code.

## What one turn does

`POST /v1/turn` with `Authorization: Bearer <token>`:

1. **Plan.** Groq (Llama 3.3 70B, JSON mode) turns the request into `intent`, title `searches` (spelling variants),
   a TMDB `discover` filter and `like`. It's told the speech may come from Indian English, Hindi or Hinglish and may be misheard. Replies are always in plain English.
2. **Fetch.** TMDB, in parallel: `/search/multi` (a term with no result is retried loosened, and results are ranked
   by how alike their title sounds), `/discover/*` on the TV's services in the region, `/recommendations` for
   "more like the second one" (only for an id in the history), then `watch/providers` for up to 15 candidates.
3. **Choose.** Groq picks 3 to 5 ids from those candidates and writes the reply.
4. **Check.** Only ids that came back from this turn's TMDB calls are returned, once each. `service` is set only when
   the region's flat-rate providers list one of the request's `services`. `reply` is cut to 220 characters.

If Groq answers 429, a server error, a timeout or something that isn't JSON, the call is retried once on
Llama 3.1 8B. If that fails too, the status is 503 (or 504), and the TV shows plain search.

### Request and response (v1)

```json
{"v": 1, "query": "a funny movie under 100 minutes", "lang": "en", "region": "US",
 "services": ["netflix", "prime", "hulu"],
 "history": [{"said": "a thriller", "reply": "Try these.", "picks": [{"n": 1, "id": 603, "kind": "movie", "title": "The Matrix", "year": 1999}]}]}
```
```json
{"reply": "Here are three short comedies.", "intent": "recommend",
 "picks": [{"id": 587792, "kind": "movie", "title": "Palm Springs", "year": 2020, "service": "hulu", "why": "90-minute time-loop comedy"}]}
```

`intent` is `recommend`, `search`, `open_app` (with `"app": "netflix"`, only for an app in `services`) or `chat`
(a reply, no picks). `service` is `null` when none of the TV's apps streams it in the region.

| Status | Meaning |
|---|---|
| 200 | An answer (possibly with no picks) |
| 400 | Bad request (version, empty or over-long `query`) |
| 401 | Missing or wrong token |
| 429 | Over 10 turns a minute or 300 a day (`Retry-After`) |
| 502 / 503 / 504 | TMDB failed / Groq failed on both models / out of time (5.5 s) |

`GET /health` answers `{"ok":true}` without a token.

## Set up (one time; Ayush's own accounts, free plans only)

You need three things. Nothing here asks for a card.

1. A **Cloudflare** account (free) at dash.cloudflare.com.
2. A **Groq** API key (free) from console.groq.com/keys.
3. A **TMDB** API key or read access token (free) from themoviedb.org/settings/api. This can be the same key the app uses.

Then, from this folder:

```bash
npm install
npx wrangler login        # opens a browser for Cloudflare
npx wrangler deploy       # prints https://free-my-tv-assistant.<you>.workers.dev
./setup.sh                # makes the token, stores its hash, asks for the Groq and TMDB keys
```

`setup.sh` prints the **device token once**. Keep it with the Worker's URL; the app will take them as
`assistant_token` and `assistant_url` (phase 2). Try it:

```bash
curl -s -X POST https://free-my-tv-assistant.<you>.workers.dev/v1/turn \
  -H "Authorization: Bearer <token>" -H "content-type: application/json" \
  -d '{"v":1,"query":"a funny movie under 100 minutes","lang":"en","region":"US","services":["netflix","prime","hulu","max","disney"],"history":[]}'
```

## Rotate

- **The token:** run `./setup.sh` again. The Worker gets a new hash and the old token stops working at once.
- **The Groq or TMDB key:** `./setup.sh --keys`, or `npx wrangler secret put GROQ_KEY` (or `TMDB_KEY`). Nothing changes on the TV.

## Limits

- **Per token:** 10 turns a minute (Cloudflare's Rate Limiting binding; approximate and per location) and 300 a day
  (an exact counter in a small Durable Object, free on the Workers Free plan; the day is the calendar day in New York, America/New_York, so it resets at local midnight through EST and EDT). The binding only allows
  10 or 60 second periods, which is why the daily count lives in the Durable Object.
- **Cloudflare Free:** 100,000 requests a day, 10 ms of CPU per request (time spent waiting on Groq and TMDB doesn't
  count) and 50 subrequests. A turn makes about 20 (2 Groq, up to 6 searches/discovers, up to 15 provider lookups).
  Watch the CPU with `npm run tail`.
- **Logs:** `[observability]` is off. The Worker logs one line per request with the status, milliseconds and counts
  only (never the text, the token or the picks), visible in `npm run tail`.

## Tests

```bash
npm test
```

Node 18+ and no network. The tests use `node --test` with a fake `fetch`; the fixtures in `test/fixtures/` are
**hand-trimmed to the shapes of real TMDB and Groq responses, not live captures**, and hold no keys. Once the Worker
is deployed, replace them with real captures if the shapes drift. They cover auth, both rate limits, the contract, made-up ids,
the reply cap, the Groq fallbacks, the failure statuses, services and Indian-English/Hinglish requests.

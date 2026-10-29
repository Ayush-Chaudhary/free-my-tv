import assert from 'node:assert/strict';
import test from 'node:test';
import { handle } from '../src/handler.js';
import { REPLY_MAX } from '../src/turn.js';
import { ask, fakeFetch, funnyRoutes, makeEnv, SERVICES } from './helpers.js';

const run = async (body, fetch, env) => {
  const res = await handle(ask(body), env || (await makeEnv()), { fetch, cache: new Map() });
  return { res, json: await res.json() };
};

test('a funny movie under 100 minutes: 3-5 real picks, the contract, the filters sent to TMDB', async () => {
  const f = fakeFetch({ groq: ['groq-plan-funny', 'groq-choose-funny'], tmdb: funnyRoutes() });
  const { res, json } = await run({ query: 'a funny movie under 100 minutes' }, f);
  assert.equal(res.status, 200);
  assert.deepEqual(Object.keys(json).sort(), ['intent', 'picks', 'reply']);
  assert.equal(json.intent, 'recommend');
  assert.ok(json.picks.length >= 3 && json.picks.length <= 5, `${json.picks.length} picks`);
  for (const p of json.picks) {
    assert.deepEqual(Object.keys(p).sort(), ['id', 'kind', 'service', 'title', 'why', 'year']);
    assert.ok(p.why.length > 0 && p.why.length <= 60);
  }
  // the discover call carries the runtime, the genre and the TV's providers in the region
  const d = f.calls.find((c) => c.path === '/3/discover/movie');
  assert.equal(d.search.get('with_runtime.lte'), '100');
  assert.equal(d.search.get('with_genres'), '35');
  assert.equal(d.search.get('watch_region'), 'US');
  assert.deepEqual(d.search.get('with_watch_providers').split('|').map(Number).sort((a, b) => a - b), [8, 9, 15, 337, 350, 531, 1899]);
  assert.equal(d.search.get('api_key'), 'tmdb-test-key');
  assert.equal(f.groqLeft(), 0);
});

test('services come only from the request, in the region, and "Channel" subscriptions never count', async () => {
  const f = fakeFetch({ groq: ['groq-plan-funny', 'groq-choose-funny'], tmdb: funnyRoutes() });
  const { json } = await run({ query: 'a funny movie', services: ['netflix'] }, f);
  const byTitle = Object.fromEntries(json.picks.map((p) => [p.title, p.service]));
  assert.equal(byTitle['Hot Fuzz'], 'netflix');
  assert.equal(byTitle['Palm Springs'], null);   // on Hulu and Prime here, but the TV only has Netflix
  assert.equal(byTitle['Game Night'], null);
  const all = await run({ query: 'a funny movie' }, fakeFetch({ groq: ['groq-plan-funny', 'groq-choose-funny'], tmdb: funnyRoutes() }));
  const svc = Object.fromEntries(all.json.picks.map((p) => [p.title, p.service]));
  assert.equal(svc['Palm Springs'], 'hulu');
  assert.equal(svc['Game Night'], 'max');
  assert.ok(all.json.picks.every((p) => p.service === null || SERVICES.includes(p.service)));
});

test('a title only rentable has no service', async () => {
  const f = fakeFetch({ groq: ['groq-plan-funny', { reply: 'Try these.', picks: [{ id: 290250, why: 'buddy detective comedy' }, { id: 587792, why: 'time loop' }, { id: 4638, why: 'spoof' }] }], tmdb: funnyRoutes() });
  const { json } = await run({ query: 'a funny movie' }, f);
  assert.equal(json.picks.find((p) => p.title === 'The Nice Guys').service, null);
});

test('open_app returns the app only when the TV has it', async () => {
  let r = await run({ query: 'open netflix' }, fakeFetch({ groq: ['groq-plan-open-app'] }));
  assert.equal(r.json.intent, 'open_app');
  assert.equal(r.json.app, 'netflix');
  assert.deepEqual(r.json.picks, []);
  r = await run({ query: 'open netflix', services: ['hulu'] }, fakeFetch({ groq: ['groq-plan-open-app'] }));
  assert.equal(r.json.intent, 'chat');
  assert.equal(r.json.app, undefined);
});

test('chat answers need no TMDB and one LLM call', async () => {
  const f = fakeFetch({ groq: ['groq-plan-chat'] });
  const { json } = await run({ query: 'what can you do' }, f);
  assert.equal(json.intent, 'chat');
  assert.deepEqual(json.picks, []);
  assert.equal(f.calls.filter((c) => c.host === 'api.themoviedb.org').length, 0);
});

test('bad requests get 400', async () => {
  const env = await makeEnv();
  for (const body of [{ query: '' }, { query: 'x'.repeat(401) }, { v: 2, query: 'a' }]) {
    const res = await handle(ask(body), env, { fetch: fakeFetch({}), cache: new Map() });
    assert.equal(res.status, 400, JSON.stringify(body).slice(0, 40));
  }
});

test('history is trimmed to 6 turns and sent to the model; ids in it make "more like" work', async () => {
  const history = Array.from({ length: 9 }, (_, i) => ({ said: `ask ${i}`, reply: 'ok', picks: [{ n: 1, id: 603, kind: 'movie', title: 'The Matrix', year: 1999 }] }));
  const f = fakeFetch({
    groq: [{}, 'groq-choose-funny'].map((x, i) => (i === 0 ? 'groq-plan-funny' : x)),
    tmdb: funnyRoutes(),
  });
  await run({ query: 'something shorter', history }, f);
  const plan = f.calls.find((c) => c.host === 'api.groq.com').body;
  const text = plan.messages.map((m) => m.content).join('\n');
  assert.ok(text.includes('ask 8') && text.includes('ask 3') && !text.includes('ask 2'));
  assert.equal(plan.response_format.type, 'json_object');
});

test('"more like the second one" only trusts an id from the history', async () => {
  const history = [{ said: 'a thriller', reply: 'ok', picks: [{ n: 2, id: 603, kind: 'movie', title: 'The Matrix', year: 1999 }] }];
  const go = async (id) => {
    const plan = { intent: 'recommend', searches: [], discover: null, like: { kind: 'movie', id } };
    const f = fakeFetch({
      groq: [plan, { reply: 'ok', picks: [] }],
      tmdb: { ...funnyRoutes(), [`/movie/${id}/recommendations`]: 'tmdb-discover-movie-comedy' },
    });
    await run({ query: 'more like the second one', history }, f);
    return f.calls.some((c) => c.path.endsWith('/recommendations'));
  };
  assert.equal(await go(603), true);
  assert.equal(await go(12345), false);
});

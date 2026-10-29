// Groq's OpenAI-compatible chat API in JSON mode. A 429 (or a server error, a timeout, or a reply that is not JSON)
// on the main model falls back to the small one; if that fails too, the caller gets an UpstreamError.

export const MODEL = 'llama-3.3-70b-versatile';
export const FALLBACK_MODEL = 'llama-3.1-8b-instant';
const URL = 'https://api.groq.com/openai/v1/chat/completions';

export class UpstreamError extends Error {
  constructor(message, status = 503) {
    super(message);
    this.status = status;
  }
}

async function once(model, messages, { env, fetch, timeoutMs, maxTokens }) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(URL, {
      method: 'POST',
      headers: { authorization: `Bearer ${env.GROQ_KEY}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        model, messages, temperature: 0.3, max_tokens: maxTokens, response_format: { type: 'json_object' },
      }),
      signal: ctl.signal,
    });
    if (!res.ok) throw Object.assign(new Error(`groq ${res.status}`), { status: res.status });
    const body = await res.json();
    const text = body && body.choices && body.choices[0] && body.choices[0].message && body.choices[0].message.content;
    const parsed = JSON.parse(text);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('groq: not an object');
    return parsed;
  } finally {
    clearTimeout(timer);
  }
}

/** One JSON-mode call: the main model, then the fallback. `stats.llm` counts the calls made. */
export async function chatJson(messages, ctx) {
  const { stats, deadline, now } = ctx;
  let last;
  for (const model of [MODEL, FALLBACK_MODEL]) {
    const left = deadline - now();
    if (left < 400) break;
    stats.llm += 1;
    try {
      return await once(model, messages, { ...ctx, timeoutMs: Math.min(3000, left), maxTokens: 500 });
    } catch (e) {
      last = e;
      stats.groq = e.status || (e.name === 'AbortError' ? 'timeout' : 'bad');
    }
  }
  throw new UpstreamError(`llm unavailable (${last ? last.message : 'out of time'})`, last && last.name === 'AbortError' ? 504 : 503);
}

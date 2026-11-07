// Groq's OpenAI-compatible chat API in JSON mode. A 429 (or a server error, a timeout, or a reply that is not JSON)
// on the main model falls back to the small one; if that fails too, the caller gets an UpstreamError.

// Groq retired llama-3.3-70b-versatile and llama-3.1-8b-instant (they 503 as llm_unavailable).
// gpt-oss on Groq still does JSON mode (response_format json_object), which is all this Worker asks for.
export const MODEL = 'openai/gpt-oss-120b';
export const FALLBACK_MODEL = 'openai/gpt-oss-20b';
const URL = 'https://api.groq.com/openai/v1/chat/completions';

export class UpstreamError extends Error {
  constructor(message, status = 503) {
    super(message);
    this.status = status;
  }
}

function partText(part) {
  if (typeof part === 'string') return part;
  if (part && typeof part.text === 'string') return part.text;
  if (part && typeof part.content === 'string') return part.content;
  return '';
}

function answerText(message) {
  if (!message || typeof message !== 'object') return '';
  // gpt-oss also returns `reasoning` (and sometimes `reasoning_content`). The answer is content, or tool-call arguments.
  const content = message.content;
  if (typeof content === 'string' && content.trim()) return content;
  if (Array.isArray(content)) {
    const joined = content.map(partText).join('').trim();
    if (joined) return joined;
  }
  const calls = message.tool_calls;
  if (Array.isArray(calls)) {
    for (const call of calls) {
      const args = call && call.function && call.function.arguments;
      if (typeof args === 'string' && args.trim()) return args;
      if (args && typeof args === 'object') return JSON.stringify(args);
    }
  }
  return '';
}

/** JSON mode should be one object. gpt-oss sometimes fences it or adds a sentence before it. */
export function parseModelJson(text) {
  let s = String(text || '').trim().replace(/^\uFEFF/, '');
  s = s.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim();
  const asObject = (v) => (v && typeof v === 'object' && !Array.isArray(v) ? v : null);
  try {
    const direct = asObject(JSON.parse(s));
    if (direct) return direct;
  } catch { /* fall through to the first {...} */ }
  const start = s.indexOf('{');
  if (start >= 0) {
    let depth = 0, inStr = false, esc = false;
    for (let i = start; i < s.length; i++) {
      const c = s[i];
      if (inStr) {
        if (esc) esc = false;
        else if (c === '\\') esc = true;
        else if (c === '"') inStr = false;
        continue;
      }
      if (c === '"') inStr = true;
      else if (c === '{') depth++;
      else if (c === '}') {
        depth--;
        if (depth === 0) {
          try {
            const found = asObject(JSON.parse(s.slice(start, i + 1)));
            if (found) return found;
          } catch { /* keep looking isn't possible once braces balance */ }
          break;
        }
      }
    }
  }
  throw new Error('groq: not an object');
}

function groqDetail(body) {
  const err = body && body.error;
  const msg = err && (typeof err === 'string' ? err : err.message);
  return typeof msg === 'string' ? msg.replace(/\s+/g, ' ').slice(0, 200) : '';
}

async function once(model, messages, { env, fetch, timeoutMs, maxTokens }) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(URL, {
      method: 'POST',
      headers: { authorization: `Bearer ${env.GROQ_KEY}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        model, messages, temperature: 0.3, max_completion_tokens: maxTokens,
        reasoning_effort: 'low',
        // Reasoning stays out of `content`. `reasoning_format` is rejected on gpt-oss, so don't send it.
        include_reasoning: false,
        response_format: { type: 'json_object' },
      }),
      signal: ctl.signal,
    });
    if (!res.ok) {
      let detail = '';
      try { detail = groqDetail(await res.json()); } catch { /* body was not JSON */ }
      // 429 and 5xx are retried on the fallback model; the caller still sees 503/504, never the key.
      const status = res.status === 429 || res.status >= 500 ? res.status : 502;
      throw new UpstreamError(`groq ${res.status}${detail ? `: ${detail}` : ''}`, status);
    }
    let body;
    try { body = await res.json(); } catch { throw new Error('groq: not json'); }
    const message = body && body.choices && body.choices[0] && body.choices[0].message;
    return parseModelJson(answerText(message));
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
      return await once(model, messages, { ...ctx, timeoutMs: Math.min(3000, left), maxTokens: 800 });
    } catch (e) {
      last = e;
      stats.groq = e.status || (e.name === 'AbortError' ? 'timeout' : 'bad');
    }
  }
  const aborted = last && (last.name === 'AbortError' || last.status === 504);
  throw new UpstreamError(`llm unavailable (${last ? last.message : 'out of time'})`, aborted ? 504 : 503);
}

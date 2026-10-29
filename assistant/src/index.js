// Free My TV's assistant: POST /v1/turn with a bearer token. See assistant/README.md.
import { handle } from './handler.js';

export { Limiter } from './limiter.js';

export default {
  fetch: (request, env) => handle(request, env),
};

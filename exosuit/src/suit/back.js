// Placeholder — replaced by the real "back" region. Shows the envelope proxy until then.
import { proxyRegion } from './proxy.js';

export default function build(ctx) {
  return proxyRegion('back', ctx);
}

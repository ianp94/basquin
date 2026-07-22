// k6 comparison workload for JPetStore-6 — the GET-safe catalog route mix used for the v0.3.0
// load-tool comparison (docs/BENCHMARKS.md). Client throughput + percentiles only; no view of the
// server-side heap/invariant findings Basquin captures.
//
// Run (in-cluster, matching the benchmark) — closed-loop, no think time:
//   k6 run -e BASE=http://jpetstore-app.basquin-system.svc.cluster.local:8080 -e VUS=50 -e DURATION=2m \
//     deploy/bench/k6/jpetstore.js
// VUS/DURATION/BASE are env-parameterised — read at parse time, so they DO take effect even though
// a scenario is defined (a hardcoded `vus:` would ignore CLI `--vus`).
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8092';
const ROUTES = [
  '/', '/actions/Catalog.action',
  '/actions/Catalog.action?viewCategory=&categoryId=FISH',
  '/actions/Catalog.action?viewCategory=&categoryId=DOGS',
  '/actions/Catalog.action?viewCategory=&categoryId=CATS',
  '/actions/Catalog.action?viewCategory=&categoryId=BIRDS',
  '/actions/Catalog.action?viewCategory=&categoryId=REPTILES',
  '/actions/Catalog.action?viewProduct=&productId=FI-SW-01',
  '/actions/Catalog.action?viewProduct=&productId=K9-BD-01',
  '/actions/Catalog.action?viewProduct=&productId=RP-SN-01',
  '/actions/Catalog.action?viewItem=&itemId=EST-1',
  '/actions/Catalog.action?viewItem=&itemId=EST-10',
  '/actions/Catalog.action?viewItem=&itemId=EST-18',
  '/actions/Catalog.action?searchProducts=&keyword=fish',
  '/actions/Catalog.action?searchProducts=&keyword=dog',
  '/actions/Catalog.action?searchProducts=&keyword=reptile',
];
export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: parseInt(__ENV.VUS || '50', 10),
      duration: __ENV.DURATION || '2m',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
export default function () {
  const r = http.get(BASE + ROUTES[Math.floor(Math.random() * ROUTES.length)]);
  check(r, { 'status < 500': (res) => res.status < 500 });
}

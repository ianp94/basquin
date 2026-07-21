// k6 comparison workload for JPetStore-6 — the same route mix the Basquin driver explores.
// Indicative side-by-side only (report methodology caveat). k6 = client throughput + percentiles;
// no view of the server-side heap/invariant findings Basquin captures.
// Run: k6 run -e BASE=http://localhost:8092 deploy/bench/k6/jpetstore.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8092';
const ROUTES = [
  '/', '/actions/Catalog.action',
  '/actions/Catalog.action?viewCategory=&categoryId=FISH',
  '/actions/Catalog.action?viewCategory=&categoryId=DOGS',
  '/actions/Catalog.action?viewCategory=&categoryId=CATS',
  '/actions/Catalog.action?viewProduct=&productId=FI-SW-01',
  '/actions/Catalog.action?viewProduct=&productId=K9-BD-01',
  '/actions/Catalog.action?viewItem=&itemId=EST-1',
  '/actions/Catalog.action?viewItem=&itemId=EST-6',
];
export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: 10, duration: __ENV.DURATION || '2m' } },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
export default function () {
  const r = http.get(BASE + ROUTES[Math.floor(Math.random() * ROUTES.length)]);
  check(r, { 'status < 500': (res) => res.status < 500 });
}

// k6 comparison workload for JSPWiki — the SAME route mix the Basquin driver explores.
// Indicative side-by-side only (see the report's methodology caveat): k6 reports client-side
// throughput + latency percentiles; it has no view of the server-side heap/invariant findings
// Basquin captures. Run: k6 run -e BASE=http://localhost:8090 deploy/bench/k6/jspwiki.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8090';
const ROUTES = [
  '/Wiki.jsp?page=Main', '/Wiki.jsp?page=Page01', '/Wiki.jsp?page=Page10',
  '/Wiki.jsp?page=Page20', '/Wiki.jsp?page=Page30', '/Wiki.jsp?page=Page45',
  '/Wiki.jsp?page=Page60', '/Wiki.jsp?page=About', '/Wiki.jsp?page=SandBox',
  '/Search.jsp?query=alpha', '/Search.jsp?query=beta', '/Search.jsp?query=table',
  '/Search.jsp?query=content',
];
export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: 10, duration: __ENV.DURATION || '2m' } },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};
export default function () {
  const r = http.get(BASE + ROUTES[Math.floor(Math.random() * ROUTES.length)]);
  check(r, { 'status 200': (res) => res.status === 200 });
}

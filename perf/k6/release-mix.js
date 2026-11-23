// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
//
// The release performance run (WP-30): a mix of every read a person makes, over HTTP against a running Trailkeep with
// the 50k seed (perf/seed-cases-postgresql.sql) or the 500k seed (the same plus perf/seed-comments-postgresql.sql).
// Signs in through the identity provider's login form, as a browser does. Run it by hand (docs/perf/release-500k.md):
//
//   k6 run -e PASSWORD=<password of the dev realm> perf/k6/release-mix.js
//
// PASSWORD is required and has no default. BASE_URL defaults to http://localhost:8080, ADMIN to compliance.admin and
// ANALYST to alpha.analyst (the case seed adds the local stack's dev-realm Analyst to Perf Team 01, if the application
// has created that user before the seed runs). VUS (default 10) and DURATION (default 3m) size the browse scenario. The mix is fixed, not random, so two runs send the same requests in the same
// proportions; which case a request opens varies by virtual user and iteration, deterministically.
//
// Thresholds are the plan's targets (BUILD_PLAN "Performance", IMPLEMENTATION_PLAN WP-15): every list and case page p95
// under 500 ms, the search p95 under 1 s, the aging report under 30 s; and no failed check.

import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN = __ENV.ADMIN || 'compliance.admin';
const ANALYST = __ENV.ANALYST || 'alpha.analyst';
// No default: a password written into the script would be copied into runs against other environments.
const PASSWORD = __ENV.PASSWORD;
if (!PASSWORD) {
  throw new Error('PASSWORD is required: run k6 with -e PASSWORD=<the password of the users>. '
    + 'For the local stack it is in deploy/local/keycloak/README.md.');
}
const VUS = Number(__ENV.VUS || 10);
const DURATION = __ENV.DURATION || '3m';

const PAGE = ['p(95)<500'];
export const options = {
  // Keep each virtual user's session across iterations: it signs in once, as a person does.
  noCookiesReset: true,
  scenarios: {
    browse: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'browse' },
    analyst: { executor: 'constant-vus', vus: 2, duration: DURATION, exec: 'analyst' },
    reports: { executor: 'constant-vus', vus: 1, duration: DURATION, exec: 'reports' },
    exports: { executor: 'constant-vus', vus: 1, duration: DURATION, exec: 'exports' },
  },
  thresholds: {
    'http_req_duration{name:list}': PAGE,
    'http_req_duration{name:list-title}': PAGE,
    'http_req_duration{name:list-due}': PAGE,
    'http_req_duration{name:list-deep-page}': PAGE,
    'http_req_duration{name:list-filtered}': PAGE,
    'http_req_duration{name:search-title}': ['p(95)<1000'],
    'http_req_duration{name:search-comment}': ['p(95)<1000'],
    'http_req_duration{name:search-number}': ['p(95)<1000'],
    'http_req_duration{name:case}': PAGE,
    'http_req_duration{name:case-long-thread}': PAGE,
    'http_req_duration{name:history-long-thread}': PAGE,
    'http_req_duration{name:dashboard}': PAGE,
    'http_req_duration{name:analyst-list}': PAGE,
    'http_req_duration{name:analyst-search}': ['p(95)<1000'],
    'http_req_duration{name:analyst-case}': PAGE,
    'http_req_duration{name:aging}': ['max<30000'],
    'http_req_duration{name:aging-csv}': ['max<30000'],
    'http_req_duration{name:compliance}': ['max<30000'],
    // No target in the plan: a ceiling, so the run reports the export's duration and catches a regression.
    'http_req_duration{name:export}': ['max<10000'],
    checks: ['rate>0.99'],
  },
};

let signedIn = false;
// Every origin whose cookies this virtual user keeps (the application's and the identity provider's).
const origins = new Set();

/**
 * The application's session cookie is Secure, and so are the identity provider's (Keycloak 26), so over plain http
 * (the local stack) k6 would store them and never send them back. Redirects are therefore followed by hand, and every
 * cookie a response sets is copied into the jar as a non-secure cookie for that response's origin; behind TLS this
 * changes nothing.
 */
function keepCookies(res) {
  const origin = res.url.replace(/^(https?:\/\/[^/]+).*$/, '$1');
  origins.add(origin);
  const jar = http.cookieJar();
  Object.values(res.cookies).flat().forEach((cookie) => {
    jar.set(origin, cookie.name, cookie.value, { path: cookie.path || '/', secure: false });
  });
}

function follow(res) {
  let hops = 0;
  keepCookies(res);
  while (res.status >= 300 && res.status < 400 && hops < 10) {
    const location = res.headers.Location;
    const next = location.startsWith('http') ? location : res.url.replace(/^(https?:\/\/[^/]+).*$/, '$1') + location;
    res = http.get(next, { redirects: 0, tags: { name: 'sign-in' } });
    keepCookies(res);
    hops++;
  }
  return res;
}

/** Follows the redirect to the identity provider, posts its login form and comes back with a session cookie. */
function signIn(username) {
  if (signedIn) {
    return;
  }
  const login = follow(http.get(`${BASE_URL}/cases`, { redirects: 0, tags: { name: 'sign-in' } }));
  if (login.url.startsWith(BASE_URL) && login.status === 200) {
    // The identity provider still had a session for this user and sent the browser straight back.
    signedIn = true;
    return;
  }
  const action = login.html().find('form').first().attr('action');
  if (!action) {
    fail(`no login form at ${login.url} (status ${login.status})`);
  }
  const back = follow(http.post(action.replace(/&amp;/g, '&'), { username, password: PASSWORD },
    { redirects: 0, tags: { name: 'sign-in' } }));
  if (!check(back, { 'signed in and back on the cases list': (r) => r.status === 200 && r.body.includes('cases found.') })) {
    fail(`sign-in failed for ${username}: ${back.status} ${back.url}`);
  }
  signedIn = true;
}

/**
 * Signs each user in once before the scenarios start, so the application's first-sign-in upsert of the person never
 * runs for many virtual users at once (that race is not what this run measures).
 */
export function setup() {
  [ADMIN, ANALYST].forEach((username) => {
    signedIn = false;
    signIn(username);
    origins.forEach((origin) => http.cookieJar().clear(origin));
  });
  signedIn = false;
}

function page(path, name, expected, timeout) {
  const res = http.get(`${BASE_URL}${path}`, { tags: { name }, timeout: timeout || '60s' });
  keepCookies(res);
  check(res, {
    [`${name} is 200`]: (r) => r.status === 200,
    [`${name} shows ${expected}`]: (r) => typeof r.body === 'string' && r.body.includes(expected),
  });
  return res;
}

/** The seed's case number for case i (1 to 50 000). */
function caseNumber(i) {
  return `PERF-2026-${String(i).padStart(6, '0')}`;
}

/** A case for this virtual user and iteration: deterministic, spread over the whole seed. */
function someCase(salt) {
  return 1 + ((__VU * 7919 + __ITER * 104729 + salt * 15485863) % 50000);
}

/** One of the 500 cases with 208 comments in the 500k seed (case numbers divisible by 100). */
function longThread() {
  return 100 * (1 + ((__VU * 31 + __ITER * 17) % 500));
}

export function browse() {
  signIn(ADMIN);
  page('/cases', 'list', 'cases found.');
  page('/cases?sort=title&dir=asc', 'list-title', 'cases found.');
  page('/cases?sort=due&dir=asc', 'list-due', 'cases found.');
  page('/cases?page=1900', 'list-deep-page', 'Page 1,900 of 1,900');
  page('/cases?status=OPEN&status=PENDING_CLOSURE&sla=AT_RISK&sla=OVERDUE&priority=HIGH&from=2026-03-01&to=2026-09-30',
    'list-filtered', 'cases found.');
  page('/cases?q=plover+audit', 'search-title', 'cases found.');
  // Only in comments, in one comment in 997: every case's whole text is read.
  page('/cases?q=kestrel', 'search-comment', 'cases found.');
  page(`/cases?q=${caseNumber(someCase(1)).slice(0, 14)}`, 'search-number', 'cases found.');
  const a = caseNumber(someCase(2));
  page(`/cases/${a}`, 'case', a);
  const b = caseNumber(someCase(3));
  page(`/cases/${b}`, 'case', b);
  const long = caseNumber(longThread());
  page(`/cases/${long}`, 'case-long-thread', long);
  page(`/cases/${long}/history`, 'history-long-thread', `History of ${long}`);
  page('/', 'dashboard', 'Dashboard');
}

/** An Analyst of one team: the scoped list, a search and that team's cases (case i belongs to team 1 + i % 20). */
export function analyst() {
  signIn(ANALYST);
  page('/cases', 'analyst-list', 'cases found.');
  page('/cases?q=plover+audit', 'analyst-search', 'cases found.');
  const own = caseNumber(20 * (1 + ((__VU * 7919 + __ITER * 104729) % 2499)));
  page(`/cases/${own}`, 'analyst-case', own);
}

/** A Compliance Admin's reports over every case, every few seconds. */
export function reports() {
  signIn(ADMIN);
  page('/reports/aging', 'aging', 'Aging', '120s');
  page('/reports/aging.csv', 'aging-csv', 'Overdue case', '120s');
  page('/reports/compliance', 'compliance', 'SLA compliance', '120s');
  sleep(5);
}

/** A cap-sized export with details (9 645 rows of case type 1), every few seconds. */
export function exports() {
  signIn(ADMIN);
  const res = http.get(`${BASE_URL}/cases/export.csv?type=900000001&details=true`,
    { tags: { name: 'export' }, timeout: '60s' });
  keepCookies(res);
  check(res, {
    'export is a CSV attachment': (r) => r.status === 200
      && String(r.headers['Content-Disposition'] || '').startsWith('attachment'),
    'export holds thousands of rows': (r) => typeof r.body === 'string' && r.body.split('\r\n').length > 9000,
  });
  sleep(5);
}

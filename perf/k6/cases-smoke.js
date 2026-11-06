// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
//
// k6 smoke of the cases list (WP-15 part 5) against a running Trailkeep with the 50 000-case seed
// (perf/seed-cases-postgresql.sql). Each virtual user signs in once through the identity provider's login form, as a
// browser does, and then requests the list, a sorted list, a deep page, a search and, once, the CSV export. Run it by
// hand (docs/perf/cases-list-50k.md):
//
//   k6 run -e BASE_URL=http://localhost:8080 -e USERNAME=compliance.admin -e PASSWORD=dev-password \
//     perf/k6/cases-smoke.js
//
// The thresholds are the plan's targets: GET /cases p95 under 500 ms, the search p95 under 1 s.

import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.USERNAME || 'compliance.admin';
const PASSWORD = __ENV.PASSWORD || 'dev-password';

export const options = {
  // Keep each virtual user's session across iterations: it signs in once, as a person does.
  noCookiesReset: true,
  scenarios: {
    browse: { executor: 'constant-vus', vus: 5, duration: __ENV.DURATION || '1m', exec: 'browse' },
    exportOnce: { executor: 'per-vu-iterations', vus: 1, iterations: 1, exec: 'exportOnce', startTime: '5s' },
  },
  thresholds: {
    'http_req_duration{name:list}': ['p(95)<500'],
    'http_req_duration{name:list-sorted}': ['p(95)<500'],
    'http_req_duration{name:list-deep-page}': ['p(95)<500'],
    'http_req_duration{name:search}': ['p(95)<1000'],
    // No target in the plan; a ceiling so the run reports the export's duration and catches a regression.
    'http_req_duration{name:export}': ['max<10000'],
    checks: ['rate>0.99'],
  },
};

let signedIn = false;

/**
 * The application's session cookie is Secure, so over plain http (the local stack) k6 would store it and never send
 * it back. Redirects are therefore followed by hand, and every cookie the application sets, on any response, is copied
 * into the jar as a non-secure cookie for BASE_URL; behind TLS this changes nothing.
 */
function keepAppCookies(res) {
  if (!res.url.startsWith(BASE_URL)) {
    return;
  }
  const jar = http.cookieJar();
  Object.values(res.cookies).flat().forEach((cookie) => {
    jar.set(BASE_URL, cookie.name, cookie.value, { path: cookie.path || '/', secure: false });
  });
}

function follow(res) {
  let hops = 0;
  keepAppCookies(res);
  while (res.status >= 300 && res.status < 400 && hops < 10) {
    const location = res.headers.Location;
    const next = location.startsWith('http') ? location : res.url.replace(/^(https?:\/\/[^/]+).*$/, '$1') + location;
    res = http.get(next, { redirects: 0, tags: { name: 'sign-in' } });
    keepAppCookies(res);
    hops++;
  }
  return res;
}

/** Follows the redirect to the identity provider, posts its login form and comes back with a session cookie. */
function signIn() {
  const login = follow(http.get(`${BASE_URL}/cases`, { redirects: 0, tags: { name: 'sign-in' } }));
  const action = login.html().find('form').first().attr('action');
  if (!action) {
    fail(`no login form at ${login.url} (status ${login.status})`);
  }
  const back = follow(http.post(action.replace(/&amp;/g, '&'), { username: USERNAME, password: PASSWORD },
    { redirects: 0, tags: { name: 'sign-in' } }));
  const ok = check(back, {
    'signed in and back on the cases list': (r) => r.status === 200 && r.body.includes('cases found.'),
  });
  if (!ok) {
    fail(`sign-in failed: ${back.status} ${back.url}`);
  }
  signedIn = true;
}

function page(path, name, expected) {
  const res = http.get(`${BASE_URL}${path}`, { tags: { name } });
  keepAppCookies(res);
  check(res, {
    [`${name} is 200`]: (r) => r.status === 200,
    [`${name} shows ${expected}`]: (r) => r.body.includes(expected),
  });
}

export function browse() {
  if (!signedIn) {
    signIn();
  }
  page('/cases', 'list', 'cases found.');
  page('/cases?sort=title&dir=asc', 'list-sorted', 'cases found.');
  page('/cases?page=1900', 'list-deep-page', 'Page 1,900 of 1,900');
  page('/cases?q=plover+audit', 'search', 'cases found.');
}

export function exportOnce() {
  signIn();
  const res = http.get(`${BASE_URL}/cases/export.csv?type=900000001`, { tags: { name: 'export' }, timeout: '60s' });
  keepAppCookies(res);
  check(res, {
    'export is a CSV attachment': (r) => r.status === 200
      && String(r.headers['Content-Disposition'] || '').startsWith('attachment'),
    'export holds thousands of rows': (r) => r.body.split('\r\n').length > 9000,
  });
}

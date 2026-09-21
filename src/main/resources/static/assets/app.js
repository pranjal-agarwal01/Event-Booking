// Single-page UI for the Event Booking API.
//
// Served by the Spring Boot app itself (src/main/resources/static), so it shares
// an origin with the API: no CORS, no second deploy, no build step.

const API = '/api/v1';
const RACE_SIZES = [10, 25, 50];
const DEMO = {
  user: ['user@booking.dev', 'password123'],
  admin: ['admin@booking.dev', 'password123'],
};

/* ---------- templating: every interpolated value is HTML-escaped ---------- */
// Event names come from the database, and the demo admin account is public, so
// anything rendered here must be treated as untrusted.

class Safe { constructor(value) { this.value = value; } }
const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const escapeHtml = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ESCAPES[c]);
const trusted = (v) => new Safe(v);
const fill = (v) => (v instanceof Safe ? v.value : Array.isArray(v) ? v.map(fill).join('') : escapeHtml(v));
const html = (strings, ...values) =>
  new Safe(strings.reduce((out, s, i) => out + s + (i < values.length ? fill(values[i]) : ''), ''));
const mount = (el, tpl) => { if (el) el.innerHTML = tpl.value; };
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

/* ---------- session: tokens in sessionStorage, gone when the tab closes ---------- */

const memory = {};
const store = {
  get(k) { try { return sessionStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { sessionStorage.setItem(k, v); } catch { /* private mode: memory only */ } },
  del(k) { try { sessionStorage.removeItem(k); } catch { /* ignore */ } },
};

const session = {
  get access() { return memory.access ?? store.get('eb.access'); },
  get refresh() { return memory.refresh ?? store.get('eb.refresh'); },
  save({ accessToken, refreshToken }) {
    memory.access = accessToken;
    memory.refresh = refreshToken;
    store.set('eb.access', accessToken);
    store.set('eb.refresh', refreshToken);
  },
  clear() {
    memory.access = null;
    memory.refresh = null;
    store.del('eb.access');
    store.del('eb.refresh');
  },
  get user() {
    const claims = decodeJwt(this.access);
    return claims ? { id: Number(claims.sub), email: claims.email, role: claims.role, exp: claims.exp } : null;
  },
};

function decodeJwt(token) {
  if (!token) return null;
  try {
    const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(part + '='.repeat((4 - (part.length % 4)) % 4)));
  } catch {
    return null;
  }
}

/* ---------- API client with one transparent token refresh ---------- */

class ApiError extends Error {
  constructor(status, message, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

let refreshing = null;
function refreshTokens() {
  refreshing ??= (async () => {
    try {
      const res = await fetch(`${API}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.refresh }),
      });
      if (!res.ok) { session.clear(); return false; }
      session.save(await res.json());
      return true;
    } catch {
      return false;
    }
  })().finally(() => { refreshing = null; });
  return refreshing;
}

async function ensureFreshToken() {
  const exp = session.user?.exp;
  if (exp && exp * 1000 - Date.now() < 60_000) await refreshTokens();
}

async function api(path, { method = 'GET', body, headers = {}, retried = false } = {}) {
  const init = { method, headers: { ...headers } };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  if (session.access) init.headers.Authorization = `Bearer ${session.access}`;

  const res = await fetch(API + path, init);
  if (res.status === 401 && !retried && session.refresh && !path.startsWith('/auth/')) {
    if (await refreshTokens()) return api(path, { method, body, headers, retried: true });
    onAuthChanged();
    toast('Your session expired. Please sign in again.', 'error');
  }
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, data?.message ?? `Request failed (${res.status})`, data);
  return data;
}

function messageOf(err) {
  if (!(err instanceof ApiError)) return 'Network error. The server may be waking up; try again in a moment.';
  const fields = err.body?.fieldErrors;
  if (fields && Object.keys(fields).length) {
    return Object.entries(fields).map(([field, msg]) => (msg.startsWith(field) ? msg : `${field}: ${msg}`)).join(' · ');
  }
  return err.message;
}

/* ---------- small UI helpers ---------- */

function toast(message, kind = 'info') {
  const el = document.createElement('div');
  el.className = `toast toast-${kind}`;
  el.setAttribute('role', kind === 'error' ? 'alert' : 'status');
  el.textContent = message;
  $('#toasts').append(el);
  setTimeout(() => el.classList.add('leaving'), 4200);
  setTimeout(() => el.remove(), 4600);
}
const fail = (err) => toast(messageOf(err), 'error');

function busy(btn, on) {
  if (!btn) return;
  btn.disabled = on;
  btn.setAttribute('aria-busy', String(on));
}

const fmtDate = (iso) => new Date(iso).toLocaleString(undefined,
  { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
const shortRef = (ref) => String(ref).slice(0, 8).toUpperCase();
const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`);

function remaining(iso) {
  const ms = new Date(iso) - Date.now();
  if (!iso || ms <= 0) return null;
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

const statusPill = (status) => html`<span class="pill pill-${status.toLowerCase()}">${status}</span>`;
const chips = (seats) => html`<div class="chips">${seats.map((s) => html`<span class="chip">${s}</span>`)}</div>`;

/* ---------- state & routing ---------- */

const state = {
  event: null,          // event currently open
  seats: [],
  seatSignature: '',    // skip re-rendering the map when a poll changes nothing
  selected: new Set(),  // seat ids the user has picked
  holdKey: newKey(),    // Idempotency-Key for the current selection
  hold: null,           // booking created from this page
  race: null,
  raceSize: 50,
};

const view = $('#view');
let pollTimer = null;

const routes = [
  [/^#?\/?$/, () => showEvents()],
  [/^#\/events\/(\d+)$/, (m) => showEvent(Number(m[1]))],
  [/^#\/bookings$/, () => showBookings()],
];

function route() {
  stopPolling();
  const hash = location.hash || '#/';
  for (const [pattern, handler] of routes) {
    const match = hash.match(pattern);
    if (match) {
      for (const a of $$('[data-nav]')) {
        const active = a.dataset.nav === (hash.startsWith('#/bookings') ? 'bookings' : 'events');
        if (active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
      }
      return handler(match);
    }
  }
  location.hash = '#/';
}

/* ---------- events list ---------- */

const hero = () => html`
  <section class="hero">
    <div>
      <p class="eyebrow">Spring Boot · PostgreSQL · optimistic locking</p>
      <h1>Two people click the last seat.<br><span>Exactly one gets it.</span></h1>
      <p class="lede">A seat-booking API built around the race condition most booking demos ignore.
        Pick an event, hold a few seats, then run the race test: dozens of simultaneous
        requests for one seat, and only one of them wins.</p>
      <div class="hero-actions">
        <button class="btn btn-primary" data-action="scroll-events">Pick an event</button>
        <a class="btn btn-ghost" href="/swagger-ui.html" target="_blank" rel="noopener">API docs</a>
      </div>
    </div>
    <div class="terminal" aria-label="Example race test result">
      <div class="dim">$ race --seat A1 --requests 50</div>
      <div class="row"><span>POST /api/v1/bookings</span><span class="dim">× 50, same instant</span></div>
      <hr>
      <div class="row"><span>201 Created</span><span class="ok">1</span></div>
      <div class="row"><span>409 Conflict</span><span class="bad">49</span></div>
      <div class="row"><span>double bookings</span><span class="ok">0</span></div>
    </div>
  </section>`;

async function showEvents() {
  const admin = session.user?.role === 'ADMIN';
  mount(view, html`${hero()}
    <section class="section" id="events-section">
      <div class="section-head">
        <h2>Upcoming events</h2>
        ${admin ? html`<button class="btn btn-soft btn-sm" data-action="new-event">+ New event</button>` : ''}
      </div>
      <div id="events" class="event-grid">
        <div class="skeleton"></div><div class="skeleton"></div><div class="skeleton"></div>
      </div>
    </section>`);
  try {
    const page = await api('/events?size=50&sort=startsAt');
    mount($('#events'), page.content.length
      ? html`${page.content.map(eventCard)}`
      : html`<div class="empty">No events yet.</div>`);
  } catch (err) {
    fail(err);
    mount($('#events'), html`<div class="empty">Couldn't load events. Refresh to try again.</div>`);
  }
}

function eventCard(e) {
  const date = new Date(e.startsAt);
  const pct = e.totalSeats ? Math.round((e.availableSeats / e.totalSeats) * 100) : 0;
  return html`
    <a class="event-card" href="#/events/${e.id}">
      <div class="event-date" aria-hidden="true">
        <span>${date.toLocaleString(undefined, { month: 'short' })}</span>
        <strong>${date.getDate()}</strong>
      </div>
      <div class="event-body">
        <h3>${e.name}</h3>
        <p class="muted">${e.venue}</p>
        <p class="muted small">${fmtDate(e.startsAt)}</p>
        <div class="meter ${pct <= 25 ? 'low' : ''}"><span style="width:${pct}%"></span></div>
        <p class="small"><strong>${e.availableSeats}</strong> of ${e.totalSeats} seats left</p>
      </div>
    </a>`;
}

/* ---------- event page: seat map, selection, hold ---------- */

async function showEvent(id) {
  Object.assign(state, { event: null, seats: [], seatSignature: '', hold: null, race: null });
  state.selected.clear();
  state.holdKey = newKey();
  mount(view, html`
    <a class="back" href="#/">← All events</a>
    <div id="event-head" class="event-head"><div class="skeleton" style="width:100%;min-height:72px"></div></div>
    <div class="event-layout">
      <section class="card" aria-label="Seat map">
        <div id="seatmap" class="seatmap-host"><div class="skeleton"></div></div>
        <ul class="legend">
          <li><span class="seat is-available" aria-hidden="true"></span>Available</li>
          <li><span class="seat is-selected" aria-hidden="true"></span>Your pick</li>
          <li><span class="seat is-held" aria-hidden="true"></span>Held (pending)</li>
          <li><span class="seat is-booked" aria-hidden="true"></span>Booked</li>
        </ul>
      </section>
      <aside id="panel" class="card panel"></aside>
    </div>
    <section id="race" class="card race"></section>`);
  window.scrollTo(0, 0);
  try {
    await loadEvent(id);
  } catch (err) {
    const missing = err instanceof ApiError && err.status === 404;
    if (!missing) fail(err);
    mount(view, html`<a class="back" href="#/">← All events</a>
      <div class="empty">${missing ? 'That event does not exist.' : 'Could not load this event.'}</div>`);
    return;
  }
  renderPanel();
  renderRace();
  startPolling(id);
}

async function loadEvent(id) {
  const [event, seats] = await Promise.all([api(`/events/${id}`), api(`/events/${id}/seats`)]);
  state.event = event;
  state.seats = seats;
  // Seats someone else just took drop out of the selection.
  for (const seatId of [...state.selected]) {
    if (seats.find((s) => s.id === seatId)?.status !== 'AVAILABLE') state.selected.delete(seatId);
  }
  renderEventHead();
  const signature = seats.map((s) => s.id + s.status).join();
  if (signature === state.seatSignature) return false;
  state.seatSignature = signature;
  renderSeatMap();
  return true;
}

// Other people book too: refresh the map every few seconds while it is visible.
function startPolling(id) {
  stopPolling();
  pollTimer = setInterval(async () => {
    if (document.hidden || state.race?.running) return;
    try {
      // Only redraw when a seat actually changed, so focus and hover survive idle polls.
      if (await loadEvent(id)) {
        renderPanel();
        updateRaceTarget();
      }
    } catch { /* transient; the next tick retries */ }
  }, 5000);
}
function stopPolling() { clearInterval(pollTimer); pollTimer = null; }

function renderEventHead() {
  const e = state.event;
  mount($('#event-head'), html`
    <div>
      <p class="eyebrow">${fmtDate(e.startsAt)}</p>
      <h1>${e.name}</h1>
      <p class="muted">${e.venue}</p>
    </div>
    <div class="stat"><strong>${e.availableSeats}</strong><span>of ${e.totalSeats} seats available</span></div>`);
}

const SEAT_STATUS = { AVAILABLE: 'available', HELD: 'held by a pending booking', BOOKED: 'booked' };

function renderSeatMap() {
  const rows = new Map();
  for (const seat of state.seats) {
    const [, row = '?', num = seat.seatNumber] = seat.seatNumber.match(/^([A-Z]+)(\d+)$/) ?? [];
    if (!rows.has(row)) rows.set(row, []);
    rows.get(row).push({ ...seat, num: Number(num) });
  }
  const focused = document.activeElement?.dataset?.seat;
  mount($('#seatmap'), html`
    <div class="stage" aria-hidden="true">Stage</div>
    <div class="seat-rows">
      ${[...rows].map(([row, seats]) => html`
        <div class="seat-row" role="group" aria-label="Row ${row}">
          <span class="row-label" aria-hidden="true">${row}</span>
          ${seats.sort((a, b) => a.num - b.num).map(seatButton)}
          <span class="row-label end" aria-hidden="true">${row}</span>
        </div>`)}
    </div>`);
  if (focused) $(`[data-seat="${focused}"]`)?.focus();
}

function seatButton(seat) {
  const picked = state.selected.has(seat.id);
  const label = picked ? 'selected' : SEAT_STATUS[seat.status];
  return html`<button type="button" class="seat ${picked ? 'is-selected' : `is-${seat.status.toLowerCase()}`}"
    data-seat="${seat.id}" ${trusted(seat.status === 'AVAILABLE' ? '' : 'disabled')}
    aria-pressed="${picked}" aria-label="Seat ${seat.seatNumber}, ${label}"
    title="${seat.seatNumber} · ${label}">${seat.num}</button>`;
}

function toggleSeat(id) {
  if (state.hold?.status === 'PENDING') {
    return toast('Confirm or release your current hold first.', 'error');
  }
  state.hold = null;
  if (state.selected.has(id)) state.selected.delete(id);
  else if (state.selected.size >= 10) return toast('Up to 10 seats per booking.', 'error');
  else state.selected.add(id);
  state.holdKey = newKey(); // a new selection is a new request; retries of it reuse this key
  renderSeatMap();
  renderPanel();
  updateRaceTarget();
}

function renderPanel() {
  const host = $('#panel');
  if (!host || !state.event) return;
  if (state.hold) return mount(host, holdCard(state.hold));

  const picked = state.seats.filter((s) => state.selected.has(s.id));
  const signedIn = Boolean(session.user);
  mount(host, html`
    <h2>Your selection</h2>
    ${picked.length
      ? chips(picked.map((s) => s.seatNumber))
      : html`<p class="muted">Tap available seats on the map, up to 10 at a time.</p>`}
    <div class="stack">
      ${signedIn
        ? html`<button class="btn btn-primary btn-block" data-action="hold" ${trusted(picked.length ? '' : 'disabled')}>
            ${picked.length ? `Hold ${plural(picked.length, 'seat')}` : 'Hold seats'}</button>`
        : html`<button class="btn btn-primary btn-block" data-action="demo-login" data-role="user">Continue as demo user</button>
            <button class="btn btn-ghost btn-block" data-action="open-auth">Sign in or register</button>`}
    </div>
    <p class="fine">Holding creates a <strong>PENDING</strong> booking that keeps the seats for 10 minutes.
      Confirm it, or a scheduled job hands the seats back.</p>`);
}

function holdCard(b) {
  const pending = b.status === 'PENDING';
  const titles = { PENDING: 'Seats on hold', CONFIRMED: 'Booking confirmed' };
  return html`
    <h2>${titles[b.status] ?? `Booking ${b.status.toLowerCase()}`}</h2>
    ${chips(b.seats)}
    <dl class="facts">
      <dt>Reference</dt><dd class="mono">${shortRef(b.reference)}</dd>
      <dt>Status</dt><dd>${statusPill(b.status)}</dd>
      ${pending ? html`<dt>Expires in</dt><dd class="mono" data-expires="${b.expiresAt}">${remaining(b.expiresAt) ?? 'expired'}</dd>` : ''}
    </dl>
    ${pending
      ? html`<div class="stack">
          <button class="btn btn-primary btn-block" data-action="confirm" data-ref="${b.reference}">Confirm booking</button>
          <button class="btn btn-ghost btn-block" data-action="cancel" data-ref="${b.reference}">Release seats</button>
        </div>`
      : html`<div class="stack"><button class="btn btn-ghost btn-block" data-action="new-selection">Pick more seats</button></div>`}`;
}

async function holdSeats(btn) {
  busy(btn, true);
  try {
    state.hold = await api('/bookings', {
      method: 'POST',
      body: { eventId: state.event.id, seatIds: [...state.selected] },
      headers: { 'Idempotency-Key': state.holdKey },
    });
    state.selected.clear();
    toast(`Held ${state.hold.seats.join(', ')} for 10 minutes.`, 'ok');
  } catch (err) {
    fail(err);
  } finally {
    busy(btn, false);
  }
  await loadEvent(state.event.id).catch(() => {});
  renderPanel();
  updateRaceTarget();
}

async function bookingAction(kind, reference, btn) {
  busy(btn, true);
  try {
    const b = await api(`/bookings/${reference}/${kind}`, { method: 'POST' });
    if (state.hold?.reference === reference) state.hold = b;
    if (state.race?.winnerRef === reference) state.race.winnerRef = null;
    toast(`${kind === 'confirm' ? 'Confirmed' : 'Released'} ${b.seats.join(', ')}.`, 'ok');
  } catch (err) {
    fail(err);
  } finally {
    busy(btn, false);
  }
  if (location.hash === '#/bookings') return showBookings();
  if (state.event) {
    await loadEvent(state.event.id).catch(() => {});
    renderPanel();
    renderRace();
  }
}

/* ---------- race test: many simultaneous bookings for one seat ---------- */

function raceTarget() {
  const picked = state.seats.filter((s) => state.selected.has(s.id));
  if (picked.length === 1 && picked[0].status === 'AVAILABLE') return { seat: picked[0], chosen: true };
  const free = state.seats.find((s) => s.status === 'AVAILABLE');
  return free ? { seat: free, chosen: false } : null;
}

function updateRaceTarget() {
  const el = $('#race-target');
  if (!el) return;
  const target = raceTarget();
  el.textContent = !target
    ? 'No free seats left on this event.'
    : `Target: seat ${target.seat.seatNumber}${target.chosen ? '' : ' (first free seat; select exactly one on the map to choose)'}`;
}

function renderRace() {
  const host = $('#race');
  if (!host || !state.event) return;
  const r = state.race;
  mount(host, html`
    <p class="eyebrow">The point of this project</p>
    <h2>Race test</h2>
    <p class="muted race-intro">Fire a burst of booking requests for the <em>same</em> seat at the same moment.
      Each carries its own <code>Idempotency-Key</code>, so none is treated as a retry. The seat row has a
      <code>@Version</code> column: whichever transaction writes first wins, and every other one is rejected
      with <strong>409 Conflict</strong>.</p>
    <div class="race-controls">
      <label>Requests
        <select id="race-size" ${trusted(r?.running ? 'disabled' : '')}>
          ${RACE_SIZES.map((n) => html`<option value="${n}" ${trusted(n === state.raceSize ? 'selected' : '')}>${n}</option>`)}
        </select>
      </label>
      <p id="race-target" class="muted small race-target"></p>
      <button class="btn btn-primary" data-action="race" ${trusted(r?.running ? 'disabled' : '')}>
        ${r?.running ? 'Racing…' : 'Start race'}</button>
    </div>
    ${r ? raceResults(r) : ''}`);
  updateRaceTarget();
}

const RACE_REASONS = [
  [/already taken/i, 'arrived after the winner committed, saw the seat already HELD, and were refused up front'],
  [/taken by another booking/i, 'read the seat as free, then lost the @Version check when writing'],
];
const reasonLabel = (msg) => RACE_REASONS.find(([re]) => re.test(msg))?.[1] ?? msg;

function raceResults(r) {
  const done = r.results.filter(Boolean);
  const won = done.filter((x) => x.status === 201);
  const conflicts = done.filter((x) => x.status === 409);
  const other = done.filter((x) => x.status !== 201 && x.status !== 409);
  const reasons = new Map();
  for (const x of conflicts) reasons.set(x.reason, (reasons.get(x.reason) ?? 0) + 1);

  const tile = (x, i) => {
    if (!x) return html`<span class="tile tile-wait" title="#${i + 1} in flight"></span>`;
    const kind = x.status === 201 ? 'win' : x.status === 409 ? 'lose' : 'err';
    return html`<span class="tile tile-${kind}" title="#${i + 1} · HTTP ${x.status || 'error'} · ${x.ms} ms${x.reason ? ` · ${x.reason}` : ''}">${kind === 'win' ? '✓' : kind === 'err' ? '!' : ''}</span>`;
  };

  const verdict = won.length === 1
    ? html`<p class="verdict ok">Seat ${r.seatNumber} was booked exactly once.</p>`
    : won.length === 0
      ? html`<p class="verdict bad">No request won. The seat was probably taken just before the race started.</p>`
      : html`<p class="verdict bad">${won.length} requests won. That would be a double booking.</p>`;

  return html`
    <div class="race-board" aria-label="One square per request">${r.results.map(tile)}</div>
    ${r.running ? '' : html`
      <div class="race-summary">
        <div class="stat stat-win"><strong>${won.length}</strong><span>201 Created</span></div>
        <div class="stat stat-lose"><strong>${conflicts.length}</strong><span>409 Conflict</span></div>
        ${other.length ? html`<div class="stat"><strong>${other.length}</strong><span>other errors</span></div>` : ''}
        <div class="stat"><strong>${r.elapsed} ms</strong><span>for all ${r.results.length}</span></div>
      </div>
      <ul class="reasons">
        ${[...reasons].map(([reason, n]) => html`<li><strong>${n}</strong> ${reasonLabel(reason)}</li>`)}
      </ul>
      ${verdict}
      ${r.winnerRef ? html`<button class="btn btn-ghost" data-action="cancel" data-ref="${r.winnerRef}">Release seat ${r.seatNumber}</button>` : ''}`}`;
}

let raceFrame = 0;
const scheduleRaceRender = () => {
  raceFrame ||= requestAnimationFrame(() => { raceFrame = 0; renderRace(); });
};

async function fireOne(seatId) {
  const started = performance.now();
  try {
    const res = await fetch(`${API}/bookings`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${session.access}`,
        'Idempotency-Key': newKey(),
      },
      body: JSON.stringify({ eventId: state.event.id, seatIds: [seatId] }),
    });
    const body = await res.json().catch(() => null);
    return {
      status: res.status,
      ms: Math.round(performance.now() - started),
      reason: res.ok ? null : body?.message ?? res.statusText,
      reference: res.ok ? body?.reference : null,
    };
  } catch {
    return { status: 0, ms: Math.round(performance.now() - started), reason: 'Network error' };
  }
}

async function runRace() {
  if (!session.user) return openAuth('Sign in to run the race test. The demo user works fine.');
  if (state.hold?.status === 'PENDING') return toast('Confirm or release your current hold first.', 'error');
  const target = raceTarget();
  if (!target) return toast('No free seats left on this event.', 'error');

  // A token refresh in the middle of the burst would skew the timing.
  await ensureFreshToken();
  stopPolling();
  const size = state.raceSize;
  state.race = { seatNumber: target.seat.seatNumber, results: Array(size).fill(null), running: true, winnerRef: null };
  renderRace();

  const started = performance.now();
  await Promise.all(Array.from({ length: size }, (_, i) => fireOne(target.seat.id).then((result) => {
    state.race.results[i] = result;
    if (result.status === 201) state.race.winnerRef = result.reference;
    scheduleRaceRender();
  })));
  state.race.elapsed = Math.round(performance.now() - started);
  state.race.running = false;
  state.selected.clear();

  await loadEvent(state.event.id).catch(() => {});
  renderPanel();
  renderRace();
  startPolling(state.event.id);
  $('#race')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

/* ---------- my bookings ---------- */

async function showBookings() {
  window.scrollTo(0, 0);
  if (!session.user) {
    return mount(view, html`
      <section class="section">
        <div class="section-head"><h1>My bookings</h1></div>
        <div class="empty">
          <p>Sign in to see your bookings.</p>
          <div class="hero-actions" style="justify-content:center;margin-top:14px">
            <button class="btn btn-primary" data-action="demo-login" data-role="user">Continue as demo user</button>
            <button class="btn btn-ghost" data-action="open-auth">Sign in</button>
          </div>
        </div>
      </section>`);
  }
  mount(view, html`
    <section class="section">
      <div class="section-head"><h1>My bookings</h1></div>
      <div id="bookings" class="booking-list"><div class="skeleton"></div><div class="skeleton"></div></div>
    </section>`);
  try {
    const page = await api('/bookings/me?size=50&sort=createdAt,desc');
    mount($('#bookings'), page.content.length
      ? html`${page.content.map(bookingRow)}`
      : html`<div class="empty">No bookings yet. <a href="#/">Pick an event</a> to make one.</div>`);
  } catch (err) {
    fail(err);
    mount($('#bookings'), html`<div class="empty">Couldn't load your bookings.</div>`);
  }
}

function bookingRow(b) {
  const pending = b.status === 'PENDING';
  const cancellable = pending || b.status === 'CONFIRMED';
  return html`
    <article class="booking-row">
      <div class="booking-main">
        <h3><a href="#/events/${b.eventId}">${b.eventName}</a></h3>
        ${chips(b.seats)}
        <p class="muted small">Ref <span class="mono">${shortRef(b.reference)}</span> · ${fmtDate(b.createdAt)}</p>
      </div>
      <div class="booking-side">
        ${statusPill(b.status)}
        ${pending ? html`<span class="muted small">expires in <span class="mono" data-expires="${b.expiresAt}">${remaining(b.expiresAt) ?? 'expired'}</span></span>` : ''}
        ${cancellable ? html`<div class="row-actions">
          ${pending ? html`<button class="btn btn-primary btn-sm" data-action="confirm" data-ref="${b.reference}">Confirm</button>` : ''}
          <button class="btn btn-ghost btn-sm" data-action="cancel" data-ref="${b.reference}">Cancel</button>
        </div>` : ''}
      </div>
    </article>`;
}

/* ---------- auth ---------- */

function renderUserArea() {
  const user = session.user;
  mount($('#user-area'), user
    ? html`<span class="user-chip" title="${user.email}">
        <span class="avatar" aria-hidden="true">${user.email[0].toUpperCase()}</span>
        <span class="user-email">${user.email}</span>
        ${user.role === 'ADMIN' ? html`<span class="pill pill-admin">Admin</span>` : ''}
      </span>
      <button class="btn btn-ghost btn-sm" data-action="logout">Sign out</button>`
    : html`<button class="btn btn-primary btn-sm" data-action="open-auth">Sign in</button>`);
}

function onAuthChanged() {
  renderUserArea();
  if (location.hash.startsWith('#/events/') && state.event) {
    renderPanel();
    renderRace();
  } else {
    route();
  }
}

const authDialog = $('#auth-dialog');
const setAuthError = (msg) => { $('#auth-error').textContent = msg; };

function setAuthTab(tab) {
  for (const b of $$('[data-tab]')) b.setAttribute('aria-selected', String(b.dataset.tab === tab));
  $('#login-form').hidden = tab !== 'login';
  $('#register-form').hidden = tab !== 'register';
  $('#auth-title').textContent = tab === 'login' ? 'Sign in' : 'Create an account';
  setAuthError('');
}

function openAuth(note = 'The demo accounts are public, so anyone can try the full flow.') {
  $('#auth-note').textContent = note;
  setAuthTab('login');
  authDialog.showModal();
}

async function login(email, password) {
  session.save(await api('/auth/login', { method: 'POST', body: { email, password } }));
  onAuthChanged();
}

async function submitAuth(form, work) {
  const btn = form.querySelector('button');
  busy(btn, true);
  setAuthError('');
  try {
    await work(new FormData(form));
    form.reset();
    authDialog.close();
  } catch (err) {
    setAuthError(messageOf(err));
  } finally {
    busy(btn, false);
  }
}

$('#login-form').addEventListener('submit', (ev) => {
  ev.preventDefault();
  submitAuth(ev.target, (f) => login(f.get('email').trim(), f.get('password')));
});

$('#register-form').addEventListener('submit', (ev) => {
  ev.preventDefault();
  submitAuth(ev.target, async (f) => {
    const body = { fullName: f.get('fullName').trim(), email: f.get('email').trim(), password: f.get('password') };
    await api('/auth/register', { method: 'POST', body });
    await login(body.email, body.password);
    toast('Account created. You are signed in.', 'ok');
  });
});

async function demoLogin(role, btn) {
  busy(btn, true);
  try {
    await login(...DEMO[role]);
    if (authDialog.open) authDialog.close();
    toast(`Signed in as the demo ${role}.`, 'ok');
  } catch (err) {
    if (authDialog.open) setAuthError(messageOf(err)); else fail(err);
  } finally {
    busy(btn, false);
  }
}

function logout() {
  const refreshToken = session.refresh;
  session.clear();
  if (refreshToken) api('/auth/logout', { method: 'POST', body: { refreshToken } }).catch(() => {});
  onAuthChanged();
  toast('Signed out.');
}

/* ---------- admin: create an event ---------- */

const eventDialog = $('#event-dialog');

function openEventDialog() {
  const inTwoWeeks = new Date(Date.now() + 14 * 864e5);
  inTwoWeeks.setHours(19, 0, 0, 0);
  const pad = (n) => String(n).padStart(2, '0');
  const form = $('#event-form');
  form.reset();
  form.startsAt.value = `${inTwoWeeks.getFullYear()}-${pad(inTwoWeeks.getMonth() + 1)}-${pad(inTwoWeeks.getDate())}T19:00`;
  $('#event-error').textContent = '';
  eventDialog.showModal();
}

$('#event-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const form = ev.target;
  const btn = form.querySelector('button');
  const f = new FormData(form);
  busy(btn, true);
  $('#event-error').textContent = '';
  try {
    const created = await api('/events', {
      method: 'POST',
      body: {
        name: f.get('name').trim(),
        venue: f.get('venue').trim(),
        startsAt: new Date(f.get('startsAt')).toISOString(),
        totalSeats: Number(f.get('totalSeats')),
      },
    });
    eventDialog.close();
    toast(`Created "${created.name}" with ${created.totalSeats} seats.`, 'ok');
    location.hash = `#/events/${created.id}`;
  } catch (err) {
    $('#event-error').textContent = messageOf(err);
  } finally {
    busy(btn, false);
  }
});

/* ---------- one click handler for the whole page ---------- */

document.addEventListener('click', (ev) => {
  const seat = ev.target.closest('[data-seat]');
  if (seat && !seat.disabled && seat.closest('#seatmap')) return toggleSeat(Number(seat.dataset.seat));

  const tab = ev.target.closest('[data-tab]');
  if (tab) return setAuthTab(tab.dataset.tab);

  const el = ev.target.closest('[data-action]');
  if (!el) return;
  switch (el.dataset.action) {
    case 'open-auth': return openAuth();
    case 'demo-login': return demoLogin(el.dataset.role, el);
    case 'logout': return logout();
    case 'hold': return holdSeats(el);
    case 'confirm': return bookingAction('confirm', el.dataset.ref, el);
    case 'cancel': return bookingAction('cancel', el.dataset.ref, el);
    case 'race': return runRace();
    case 'new-event': return openEventDialog();
    case 'new-selection':
      state.hold = null;
      return renderPanel();
    case 'scroll-events':
      return $('#events-section')?.scrollIntoView({ behavior: 'smooth' });
    default:
      return undefined;
  }
});

document.addEventListener('change', (ev) => {
  if (ev.target.id === 'race-size') state.raceSize = Number(ev.target.value);
});

// Hold countdowns tick in place, wherever they are on the page.
setInterval(() => {
  for (const el of $$('[data-expires]')) {
    const left = remaining(el.dataset.expires);
    el.textContent = left ?? 'expired';
    el.classList.toggle('is-expired', !left);
  }
}, 1000);

async function checkHealth() {
  const el = $('#api-status');
  try {
    const res = await fetch('/actuator/health');
    const up = res.ok && (await res.json()).status === 'UP';
    el.className = `status ${up ? 'up' : 'down'}`;
    el.lastElementChild.textContent = up ? 'API online' : 'API unhealthy';
  } catch {
    el.className = 'status down';
    el.lastElementChild.textContent = 'API unreachable';
  }
}

renderUserArea();
window.addEventListener('hashchange', route);
route();
checkHealth();

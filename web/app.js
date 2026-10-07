'use strict';

/* =====================================================================
   إتقان — single-page app (vanilla JS, hash routing, RTL Arabic)
   ===================================================================== */

const app = document.getElementById('app');
const state = { me: null };

/* ---------- small helpers ---------- */
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const initial = (s) => (String(s || '').trim()[0] || '؟');
const homeOf = (me) => (me && me.role === 'trainer' ? '/t' : '/s');
function go(path) {
  if (location.hash === '#' + path) router();
  else location.hash = path;
}

const LOCALE = 'ar-u-nu-latn-ca-gregory';
const fmtDateTime = (ms) => new Intl.DateTimeFormat(LOCALE, { weekday: 'long', day: 'numeric', month: 'long', hour: 'numeric', minute: '2-digit' }).format(new Date(ms));
const fmtDay = (ms) => new Intl.DateTimeFormat(LOCALE, { day: 'numeric' }).format(new Date(ms));
const fmtMonth = (ms) => new Intl.DateTimeFormat(LOCALE, { month: 'short' }).format(new Date(ms));
function fmtClock(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const p = (n) => String(n).padStart(2, '0');
  return h ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`;
}
function fmtUntil(ms) {
  const min = Math.max(1, Math.round(ms / 60000));
  if (min < 60) return `${min} دقيقة`;
  const h = Math.floor(min / 60), m = min % 60;
  if (h < 24) return m ? `${h} ساعة و${m} دقيقة` : `${h} ساعة`;
  const d = Math.round(h / 24);
  return d === 1 ? 'يوم واحد' : d === 2 ? 'يومين' : `${d} أيام`;
}
function fmtSize(bytes) {
  if (!bytes) return '';
  const mb = bytes / 1048576;
  return mb >= 1024 ? `${(mb / 1024).toFixed(1)} جيجابايت` : `${mb.toFixed(1)} ميجابايت`;
}
function toLocalInput(ms) {
  const d = new Date(ms);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}
const hue = (id) => [...String(id)].reduce((a, c) => (a * 31 + c.charCodeAt(0)) % 360, 7);
const pctClass = (p) => (p >= 70 ? '' : p >= 50 ? 'mid' : 'low');

/* ---------- icons ---------- */
const ICONS = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-6h4v6"/>',
  book: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M4 20.5A2.5 2.5 0 0 0 6.5 23H20v-5"/>',
  video: '<rect x="2" y="5" width="14" height="14" rx="2.5"/><path d="m16 10 6-3.5v11L16 14"/>',
  live: '<circle cx="12" cy="12" r="2"/><path d="M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8"/>',
  bank: '<ellipse cx="12" cy="5.5" rx="8" ry="3"/><path d="M4 5.5v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/><path d="M4 11.5v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/>',
  exam: '<path d="M9 3h6l1 2h3v16H5V5h3z"/><path d="m9 13 2 2 4-4"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M16 4.6a3.5 3.5 0 0 1 0 6.8M18 14a6.5 6.5 0 0 1 3.5 6"/>',
  chart: '<path d="M4 20V11M10 20V4M16 20v-6M2 20h20"/>',
  logout: '<path d="M10 4H5v16h5"/><path d="M14 8l4 4-4 4M18 12H9"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13.5 6.5 4 4"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6M9 7V4h6v3M6 7l1 14h10l1-14"/>',
  up: '<path d="m6 15 6-6 6 6"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  play: '<path d="M7 4.5v15l12.5-7.5z"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  upload: '<path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v4h16v-4"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
  external: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v6H4V6h6"/>',
  next: '<path d="m15 6-6 6 6 6"/>',
  prev: '<path d="m9 6 6 6-6 6"/>',
  question: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14"/><path d="M12 17.5h.01"/>',
  spark: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M5.6 18.4l2.8-2.8M15.6 8.4l2.8-2.8"/>',
  image: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="9" cy="10" r="2"/><path d="m21 16-5-5L5 20"/>',
};
const icon = (name, cls = '') => `<svg class="ic ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ''}</svg>`;
const logo = () => `<span class="logo">${icon('check')}</span>`;

/* ---------- labels ---------- */
const LIVE_STATUS = { upcoming: ['قادم', 'badge-upcoming'], live: ['مباشر الآن', 'badge-live'], ended: ['منتهٍ', 'badge-ended'] };
const SOURCE = { upload: ['مرفوع', 'badge-upload'], live: ['تسجيل بث', 'badge-rec'] };
const QTYPE = { mcq: 'اختيار متعدد', tf: 'صح وخطأ' };
const DIFF = { easy: 'سهل', medium: 'متوسط', hard: 'صعب' };
const EXAM_STATUS = { draft: ['مسودة', 'badge-draft'], published: ['منشور', 'badge-published'] };
const badge = (pair) => `<span class="badge ${pair[1]}">${pair[0]}</span>`;

/* ---------- API ---------- */
async function api(path, method = 'GET', body) {
  const opts = { method, headers: {} };
  if (method !== 'GET') opts.headers['X-Requested-With'] = 'itqan';
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch('/api' + path, opts);
  } catch (e) {
    throw new Error('تعذّر الاتصال بالخادم، تأكد أنه يعمل');
  }
  let data = {};
  try { data = await res.json(); } catch (e) { /* empty body */ }
  if (!res.ok) {
    if (res.status === 401 && state.me && !path.startsWith('/auth/')) {
      const role = state.me.role;
      state.me = null;
      go(role === 'trainer' ? '/trainer/login' : '/login');
    }
    throw new Error(data.error || 'حدث خطأ غير متوقع');
  }
  return data;
}

function upload(path, file, params, onProgress) {
  return new Promise((resolve, reject) => {
    const qs = new URLSearchParams(Object.assign({ name: file.name }, params || {})).toString();
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api' + path + '?' + qs);
    xhr.setRequestHeader('X-Requested-With', 'itqan');
    xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
    xhr.upload.onprogress = (e) => { if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total); };
    xhr.onload = () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch (e) { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else reject(new Error(data.error || 'تعذّر رفع الملف'));
    };
    xhr.onerror = () => reject(new Error('انقطع الاتصال أثناء رفع الملف'));
    xhr.send(file);
  });
}

/* ---------- toasts, modals ---------- */
function toast(message, type = '') {
  const el = document.createElement('div');
  el.className = 'toast ' + type;
  el.innerHTML = (type === 'success' ? icon('check') : '') + `<span>${esc(message)}</span>`;
  $('#toasts').append(el);
  setTimeout(() => el.remove(), 3600);
}

function closeModals() { $$('.modal-backdrop').forEach((m) => m.remove()); }

/**
 * Opens a modal form. onSubmit(form, el) may throw to show an error; resolves → modal closes.
 * Pass submitText: null for an informational modal without a submit button.
 */
function modal({ title, body, submitText = 'حفظ', cancelText = 'إلغاء', danger = false, wide = false, onSubmit, onMount }) {
  const el = document.createElement('div');
  el.className = 'modal-backdrop';
  el.innerHTML = `
    <form class="modal ${wide ? 'wide' : ''}" novalidate>
      <header><h3>${esc(title)}</h3><button type="button" class="icon-btn" data-close aria-label="إغلاق">${icon('x')}</button></header>
      <div class="modal-body">${body}</div>
      <div class="form-error" hidden></div>
      <footer>
        ${submitText ? `<button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" type="submit">${esc(submitText)}</button>` : ''}
        <button type="button" class="btn btn-ghost" data-close>${esc(submitText ? cancelText : 'إغلاق')}</button>
      </footer>
    </form>`;
  document.body.append(el);
  const form = $('form', el);
  let busy = false;
  const close = () => { el.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e) => { if (e.key === 'Escape' && !busy) close(); };
  document.addEventListener('keydown', onKey);
  $$('[data-close]', el).forEach((b) => b.addEventListener('click', () => { if (!busy) close(); }));
  el.addEventListener('mousedown', (e) => { if (e.target === el && !busy) close(); });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!onSubmit || busy) return;
    const btn = $('button[type=submit]', form);
    const err = $('.form-error', el);
    err.hidden = true;
    busy = true;
    btn.classList.add('busy');
    try {
      await onSubmit(form, el);
      close();
    } catch (ex) {
      err.textContent = ex.message;
      err.hidden = false;
    } finally {
      busy = false;
      btn.classList.remove('busy');
    }
  });
  if (onMount) onMount(form, el);
  const first = $('input:not([type=hidden]):not([type=radio]):not([type=checkbox]), textarea, select', form);
  if (first) setTimeout(() => first.focus(), 30);
  return { el, form, close };
}

function confirmBox(text, { title = 'تأكيد', ok = 'نعم، متأكد', danger = true } = {}) {
  return new Promise((resolve) => {
    let answered = false;
    const m = modal({
      title, danger, submitText: ok,
      body: `<p class="confirm-text">${esc(text)}</p>`,
      onSubmit: async () => { answered = true; resolve(true); },
    });
    const observer = new MutationObserver(() => {
      if (!document.body.contains(m.el)) { observer.disconnect(); if (!answered) resolve(false); }
    });
    observer.observe(document.body, { childList: true });
  });
}

function setUpbar(form, fraction) {
  const bar = $('.upbar', form);
  if (!bar) return;
  bar.hidden = false;
  $('span', bar).style.width = Math.round(fraction * 100) + '%';
}

/* ---------- shared fragments ---------- */
const field = (label, input, hint = '') => `<label class="field"><span>${label}</span>${input}${hint ? `<small class="hint">${hint}</small>` : ''}</label>`;
const bar = (p) => `<div class="bar"><span style="width:${Math.max(0, Math.min(100, p))}%"></span></div>`;
const barRow = (p) => `<div class="bar-row">${bar(p)}<b>${p}%</b></div>`;
const cover = (c) => (c.cover ? `<img src="${esc(c.cover)}" alt="" loading="lazy">` : `<div class="cover-ph" style="--h:${hue(c.id)}">${esc(initial(c.title))}</div>`);
const pageHead = (title, sub = '', actions = '') => `<div class="page-head"><div><h1>${title}</h1>${sub ? `<p>${sub}</p>` : ''}</div>${actions}</div>`;
const empty = (ic, title, text, action = '') => `<div class="empty"><div class="empty-ic">${icon(ic)}</div><h3>${title}</h3><p>${text}</p>${action}</div>`;
const progressDetail = (p) => `شاهدت ${p.videosWatched} من ${p.videosTotal} فيديوهات، وأنهيت ${p.examsDone} من ${p.examsTotal} اختبار`;
const courseOptions = (courses, selected) => courses.map((c) => `<option value="${esc(c.id)}" ${c.id === selected ? 'selected' : ''}>${esc(c.title)}</option>`).join('');

/* ---------- router ---------- */
const routes = [];
let navSeq = 0;
let cleanups = [];
const onLeave = (fn) => cleanups.push(fn);

function route(path, opts, render) {
  const keys = [];
  const re = new RegExp('^' + path.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '/?$');
  routes.push({ re, keys, opts, render });
}

async function router() {
  cleanups.forEach((fn) => { try { fn(); } catch (e) { /* ignore */ } });
  cleanups = [];
  closeModals();
  const raw = location.hash.replace(/^#/, '') || '/';
  const [path, qs] = raw.split('?');
  const query = Object.fromEntries(new URLSearchParams(qs || ''));
  const seq = ++navSeq;
  for (const r of routes) {
    const m = r.re.exec(path);
    if (!m) continue;
    const params = {};
    r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
    const role = r.opts.role;
    if (role && (!state.me || state.me.role !== role)) {
      return go(state.me ? homeOf(state.me) : role === 'trainer' ? '/trainer/login' : '/login');
    }
    if (r.opts.guest && state.me) return go(homeOf(state.me));
    const ctx = { params, query, stale: () => seq !== navSeq };
    if (!r.opts.keepScroll) window.scrollTo(0, 0);
    try {
      await r.render(ctx);
    } catch (err) {
      if (!ctx.stale()) showError(err);
    }
    return;
  }
  go('/');
}

function showError(err) {
  const target = $('#main') || app;
  target.innerHTML = `<div class="error-box"><h2>تعذّر تحميل الصفحة</h2><p>${esc(err.message)}</p><button class="btn btn-primary" onclick="router()">إعادة المحاولة</button></div>`;
}

/* ---------- shells ---------- */
const TRAINER_NAV = [
  ['dashboard', '/t', 'home', 'الرئيسية'],
  ['courses', '/t/courses', 'book', 'الدورات'],
  ['lives', '/t/lives', 'live', 'البث المباشر'],
  ['banks', '/t/banks', 'bank', 'بنوك الأسئلة'],
  ['exams', '/t/exams', 'exam', 'الاختبارات'],
  ['trainees', '/t/trainees', 'users', 'المتدربون والنتائج'],
];
const TRAINEE_NAV = [
  ['courses', '/s', 'book', 'دوراتي'],
  ['videos', '/s/videos', 'video', 'الفيديوهات'],
  ['lives', '/s/lives', 'live', 'البث المباشر'],
  ['exams', '/s/exams', 'exam', 'الاختبارات'],
  ['banks', '/s/banks', 'bank', 'بنك الأسئلة'],
  ['progress', '/s/progress', 'chart', 'تقدّمي'],
];

function publicShell() {
  const nav = state.me
    ? `<a class="btn btn-primary btn-sm" href="#${homeOf(state.me)}">لوحتي</a>`
    : `<a href="#/login" class="btn btn-ghost btn-sm">دخول المتدرب</a><a href="#/register" class="btn btn-primary btn-sm">سجّل الآن</a>`;
  return `<div class="public">
    <header class="pub-head"><a class="brand" href="#/">${logo()}<span>إتقان</span></a><nav>${nav}</nav></header>
    <main id="main"></main>
    <footer class="pub-foot">منصة إتقان التعليمية · <a href="#/trainer/login">بوابة المدربين</a></footer>
  </div>`;
}

function trainerShell() {
  const me = state.me;
  return `<div class="t-shell">
    <aside class="sidebar">
      <a class="brand" href="#/t">${logo()}<span>إتقان<small>لوحة المدرب</small></span></a>
      <nav>${TRAINER_NAV.map(([k, href, ic, label]) => `<a href="#${href}" data-nav="${k}">${icon(ic)}<span>${label}</span></a>`).join('')}</nav>
      <div class="side-user"><span class="avatar">${esc(initial(me.name))}</span><div><b>${esc(me.name)}</b><small>${esc(me.email)}</small></div>
        <button class="icon-btn" data-logout title="تسجيل الخروج" aria-label="تسجيل الخروج">${icon('logout')}</button></div>
    </aside>
    <div class="side-backdrop" data-close-side></div>
    <div class="t-main">
      <header class="topbar"><button class="icon-btn" data-open-side aria-label="القائمة">${icon('menu')}</button><a class="brand" href="#/t">${logo()}<span>إتقان</span></a></header>
      <main id="main" class="content"></main>
    </div>
  </div>`;
}

function traineeShell() {
  const me = state.me;
  return `<div class="s-shell">
    <header class="s-head"><div class="s-head-in">
      <a class="brand" href="#/s">${logo()}<span>إتقان</span></a>
      <nav class="s-nav">${TRAINEE_NAV.map(([k, href, ic, label]) => `<a href="#${href}" data-nav="${k}">${icon(ic)}${label}</a>`).join('')}</nav>
      <div class="s-user"><span class="avatar">${esc(initial(me.name))}</span><span class="s-name">${esc(me.name)}</span>
        <button class="icon-btn" data-logout title="تسجيل الخروج" aria-label="تسجيل الخروج">${icon('logout')}</button></div>
    </div></header>
    <main id="main" class="content s-content"></main>
    <nav class="tabbar">${TRAINEE_NAV.map(([k, href, ic, label]) => `<a href="#${href}" data-nav="${k}">${icon(ic)}<span>${label}</span></a>`).join('')}</nav>
  </div>`;
}

/** Renders (or reuses) the shell for a role, marks the active nav item and returns <main>. */
function startPage(shell, active) {
  const key = shell + ':' + (state.me ? state.me.id : '');
  if (app.dataset.shell !== key) {
    app.innerHTML = shell === 'trainer' ? trainerShell() : shell === 'trainee' ? traineeShell() : publicShell();
    app.dataset.shell = key;
  }
  $$('[data-nav]', app).forEach((a) => a.classList.toggle('active', a.dataset.nav === active));
  const shellEl = $('.t-shell', app);
  if (shellEl) shellEl.classList.remove('side-open');
  const main = $('#main');
  main.innerHTML = '<div class="loading"><span class="spinner"></span></div>';
  return main;
}

document.addEventListener('click', async (e) => {
  const t = e.target.closest('[data-logout],[data-open-side],[data-close-side]');
  if (!t) return;
  if (t.hasAttribute('data-open-side')) $('.t-shell').classList.add('side-open');
  else if (t.hasAttribute('data-close-side')) $('.t-shell').classList.remove('side-open');
  else {
    const role = state.me && state.me.role;
    try { await api('/auth/logout', 'POST'); } catch (err) { /* ignore */ }
    state.me = null;
    app.dataset.shell = '';
    go(role === 'trainer' ? '/trainer/login' : '/');
  }
});

/* =====================================================================
   Public pages
   ===================================================================== */

route('/', {}, async () => {
  const main = startPage('public');
  const actions = state.me
    ? `<a href="#${homeOf(state.me)}" class="btn btn-primary btn-lg">الذهاب إلى لوحتي</a>`
    : `<a href="#/register" class="btn btn-primary btn-lg">سجّل كمتدرب</a><a href="#/login" class="btn btn-ghost btn-lg">لدي حساب</a>`;
  const li = (t) => `<li>${icon('check')}<span>${t}</span></li>`;
  main.innerHTML = `
    <section class="hero hero-solo">
      <div>
        <span class="eyebrow">${icon('spark')} منصة تعليمية عربية</span>
        <h1>تعلّم <em>بإتقان</em>.</h1>
        <p>التحق بالدورات، وشاهد الفيديوهات، وحل الاختبارات، وتابع تقدّمك أولًا بأول في مكان واحد.</p>
        <div class="hero-actions">${actions}</div>
      </div>
    </section>
    <section class="features features-solo">
      <div class="card"><h2><span class="stat-ic empty-ic" style="margin:0;width:40px;height:40px">${icon('book')}</span>للمتدرب</h2>
        <ul>${li('سجّل حسابك بنفسك والتحق بالدورات المتاحة')}${li('شاهد الفيديوهات واحضر البث المباشر وتسجيلاته')}${li('حل الاختبارات بوقت محدد واطّلع على شرح الإجابات')}${li('تابع نسبة إنجازك في كل دورة أولًا بأول')}</ul></div>
    </section>`;
});

function authPage({ title, sub, fields, submit, footer, trainer, onSubmit }) {
  const main = startPage('public');
  main.innerHTML = `<div class="auth ${trainer ? 'auth-trainer' : ''}"><form class="auth-card" novalidate>
    ${trainer ? `<span class="eyebrow">${icon('users')} بوابة المدربين</span>` : ''}
    <h1>${title}</h1><p class="muted">${sub}</p>
    ${fields}
    <div class="form-error" hidden></div>
    <button class="btn btn-primary btn-block btn-lg" type="submit">${submit}</button>
    <div class="auth-foot">${footer}</div>
  </form></div>`;
  const form = $('form', main);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    const err = $('.form-error', form);
    err.hidden = true;
    btn.classList.add('busy');
    try {
      const data = await onSubmit(new FormData(form));
      state.me = data.user;
      go(homeOf(state.me));
    } catch (ex) {
      err.textContent = ex.message;
      err.hidden = false;
    } finally {
      btn.classList.remove('busy');
    }
  });
  $('input', form).focus();
}

route('/register', { guest: true }, async () => authPage({
  title: 'إنشاء حساب متدرب',
  sub: 'سجّل حسابك وابدأ التعلّم في دقيقة.',
  fields: field('الاسم', '<input type="text" name="name" autocomplete="name" required>')
    + field('البريد الإلكتروني', '<input type="email" name="email" autocomplete="email" required>')
    + field('كلمة المرور', '<input type="password" name="password" autocomplete="new-password" minlength="6" required>', '6 أحرف على الأقل'),
  submit: 'إنشاء الحساب',
  footer: 'لديك حساب؟ <a href="#/login">سجّل الدخول</a>',
  onSubmit: (f) => api('/auth/register', 'POST', { name: f.get('name'), email: f.get('email'), password: f.get('password') }),
}));

route('/login', { guest: true }, async () => authPage({
  title: 'دخول المتدرب',
  sub: 'أهلًا بعودتك، تابع من حيث توقفت.',
  fields: field('البريد الإلكتروني', '<input type="email" name="email" autocomplete="email" required>')
    + field('كلمة المرور', '<input type="password" name="password" autocomplete="current-password" required>'),
  submit: 'دخول',
  footer: 'ليس لديك حساب؟ <a href="#/register">أنشئ حسابًا</a><br>أنت مدرب؟ <a href="#/trainer/login">بوابة المدربين</a>',
  onSubmit: (f) => api('/auth/login', 'POST', { email: f.get('email'), password: f.get('password'), role: 'trainee' }),
}));

route('/trainer/login', { guest: true }, async () => authPage({
  trainer: true,
  title: 'دخول المدربين',
  sub: 'ادخل لإدارة دوراتك ومحتواك ومتابعة متدربيك.',
  fields: field('البريد الإلكتروني', '<input type="email" name="email" autocomplete="email" required>')
    + field('كلمة المرور', '<input type="password" name="password" autocomplete="current-password" required>'),
  submit: 'دخول',
  footer: 'لست مدربًا؟ <a href="#/login">دخول المتدربين</a>',
  onSubmit: (f) => api('/auth/login', 'POST', { email: f.get('email'), password: f.get('password'), role: 'trainer' }),
}));

/* =====================================================================
   Trainer
   ===================================================================== */

const stat = (ic, label, value, href, cls = '') => `<a class="stat ${cls}" href="#${href}"><span class="stat-ic">${icon(ic)}</span><b>${value}</b><span>${label}</span></a>`;

route('/t', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'dashboard');
  const d = await api('/t/dashboard');
  if (ctx.stale()) return;
  const c = d.counts;
  const lives = d.upcomingLives.length
    ? `<div>${d.upcomingLives.map((l) => `<a class="mini-row" href="#/t/lives"><span class="date-box"><b>${fmtDay(l.startsAt)}</b><span>${fmtMonth(l.startsAt)}</span></span><span class="grow"><b>${esc(l.title)}</b><span class="meta"><span>${esc(l.courseTitle)}</span><span>${fmtDateTime(l.startsAt)}</span></span></span>${badge(LIVE_STATUS[l.status])}</a>`).join('')}</div>`
    : empty('live', 'لا توجد بثوث قادمة', 'جدول بثًا مباشرًا ليحضره متدربوك.', '<a class="btn btn-primary btn-sm" href="#/t/lives?new=1">جدولة بث</a>');
  const courses = d.courses.length
    ? `<div>${d.courses.map((x) => `<a class="mini-row" href="#/t/courses/${x.id}"><span class="mini-cover">${cover(x)}</span><span class="grow"><b>${esc(x.title)}</b><span class="meta"><span>${icon('video')} ${x.videos}</span><span>${icon('exam')} ${x.exams}</span><span>${icon('users')} ${x.trainees}</span></span></span></a>`).join('')}</div>`
    : empty('book', 'لم تنشئ أي دورة بعد', 'ابدأ بإنشاء دورتك الأولى ثم أضف إليها الفيديوهات والاختبارات.', '<a class="btn btn-primary btn-sm" href="#/t/courses?new=1">إنشاء دورة</a>');
  main.innerHTML = `
    ${pageHead(`أهلًا، ${esc(state.me.name)}`, 'هذا ملخص سريع لنشاطك على المنصة.')}
    <div class="stats">
      ${stat('book', 'الدورات', c.courses, '/t/courses')}
      ${stat('users', 'المتدربون', c.trainees, '/t/trainees', 'info')}
      ${stat('video', 'الفيديوهات', c.videos, '/t/courses')}
      ${stat('bank', 'بنوك الأسئلة', c.banks, '/t/banks', 'accent')}
      ${stat('live', 'البثوث القادمة', c.upcomingLives, '/t/lives', 'live')}
    </div>
    <div class="two-col">
      <section class="card"><div class="card-head"><h2>البثوث القادمة</h2><a href="#/t/lives" class="link">عرض الكل</a></div>${lives}</section>
      <section class="card"><div class="card-head"><h2>أحدث الدورات</h2><a href="#/t/courses" class="link">كل الدورات</a></div>${courses}</section>
    </div>`;
});

/* ---------- courses ---------- */

function courseModal(course) {
  let saved = course;
  modal({
    title: course ? 'تعديل الدورة' : 'دورة جديدة',
    submitText: course ? 'حفظ التعديلات' : 'إنشاء الدورة',
    body: field('عنوان الدورة', `<input type="text" name="title" maxlength="120" value="${esc(course && course.title)}" required>`)
      + field('الوصف', `<textarea name="description" rows="4" maxlength="3000">${esc(course && course.description)}</textarea>`)
      + field(course ? 'صورة الغلاف <small class="muted">(اختر صورة لتغييرها)</small>' : 'صورة الغلاف', '<input type="file" name="cover" accept="image/*">', 'JPG أو PNG، بحد أقصى 8 ميجابايت')
      + '<div class="upbar" hidden><span></span></div>',
    async onSubmit(form) {
      const body = { title: form.title.value, description: form.description.value };
      const res = saved ? await api(`/t/courses/${saved.id}`, 'PUT', body) : await api('/t/courses', 'POST', body);
      saved = res.course;
      const file = form.cover.files[0];
      if (file) await upload(`/t/courses/${saved.id}/cover`, file, {}, (p) => setUpbar(form, p));
      toast(course ? 'تم حفظ التعديلات' : 'تم إنشاء الدورة', 'success');
      go(`/t/courses/${saved.id}`);
    },
  });
}

route('/t/courses', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'courses');
  const d = await api('/t/courses');
  if (ctx.stale()) return;
  const newBtn = `<button class="btn btn-primary" data-new>${icon('plus')} دورة جديدة</button>`;
  main.innerHTML = pageHead('الدورات', 'أنشئ دوراتك وأدر فيديوهاتها وبثوثها واختباراتها.', d.courses.length ? newBtn : '')
    + (d.courses.length
      ? `<div class="course-grid">${d.courses.map((c) => `
          <a class="course-card" href="#/t/courses/${c.id}">
            <span class="cover">${cover(c)}</span>
            <div class="cc-body"><h3>${esc(c.title)}</h3><p class="muted clamp">${esc(c.description || 'بدون وصف')}</p>
              <div class="meta"><span title="الفيديوهات">${icon('video')} ${c.videos}</span><span title="البثوث">${icon('live')} ${c.lives}</span><span title="الاختبارات">${icon('exam')} ${c.exams}</span><span title="المتدربون">${icon('users')} ${c.trainees}</span></div>
            </div>
          </a>`).join('')}</div>`
      : empty('book', 'لا توجد دورات بعد', 'أنشئ دورتك الأولى بعنوان ووصف وصورة غلاف، ثم ارفع فيديوهاتها.', `<button class="btn btn-primary" data-new>${icon('plus')} إنشاء أول دورة</button>`));
  $$('[data-new]', main).forEach((b) => b.addEventListener('click', () => courseModal()));
  if (ctx.query.new) courseModal();
});

route('/t/courses/:id', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'courses');
  const d = await api(`/t/courses/${ctx.params.id}`);
  if (ctx.stale()) return;
  const c = d.course;
  const tab = ['videos', 'lives', 'exams', 'trainees'].includes(ctx.query.tab) ? ctx.query.tab : 'videos';
  const tabs = [['videos', 'الفيديوهات', d.videos.length], ['lives', 'البث المباشر', d.lives.length], ['exams', 'الاختبارات', d.exams.length], ['trainees', 'المتدربون', d.trainees.length]];
  main.innerHTML = `
    <a class="back" href="#/t/courses">${icon('prev')} الدورات</a>
    <div class="course-hero card">
      <span class="cover">${cover(c)}</span>
      <div class="ch-body"><h1>${esc(c.title)}</h1><p class="muted">${esc(c.description || 'بدون وصف')}</p>
        <div class="actions"><button class="btn btn-ghost btn-sm" data-edit>${icon('edit')} تعديل الدورة</button><button class="btn btn-ghost btn-sm danger" data-del>${icon('trash')} حذف</button></div></div>
    </div>
    <nav class="tabs">${tabs.map(([k, l, n]) => `<a href="#/t/courses/${c.id}?tab=${k}" class="${k === tab ? 'active' : ''}">${l}<span class="count">${n}</span></a>`).join('')}</nav>
    <div id="tab"></div>`;
  $('[data-edit]', main).addEventListener('click', () => courseModal(c));
  $('[data-del]', main).addEventListener('click', async () => {
    if (!(await confirmBox(`سيتم حذف الدورة «${c.title}» مع جميع فيديوهاتها وبثوثها واختباراتها وسجلات متدربيها. لا يمكن التراجع عن ذلك.`, { title: 'حذف الدورة', ok: 'حذف الدورة' }))) return;
    try { await api(`/t/courses/${c.id}`, 'DELETE'); toast('تم حذف الدورة'); go('/t/courses'); } catch (err) { toast(err.message, 'error'); }
  });
  const el = $('#tab', main);
  if (tab === 'videos') renderCourseVideos(el, d);
  else if (tab === 'lives') renderLiveList(el, d.lives, { courses: [{ id: c.id, title: c.title }], courseId: c.id, showCourse: false });
  else if (tab === 'exams') renderExamList(el, d.exams, { courseId: c.id, showCourse: false });
  else renderTraineeTable(el, d.trainees);
});

function renderCourseVideos(el, d) {
  const vids = d.videos;
  const upBtn = `<button class="btn btn-primary" data-up>${icon('upload')} رفع مقطع</button>`;
  el.innerHTML = `<div class="section-head"><h2>فيديوهات الدورة</h2>${vids.length ? upBtn : ''}</div>`
    + (vids.length
      ? `<ol class="video-list">${vids.map((v, i) => `
          <li class="video-row">
            <span class="order">${v.order}</span>
            <button class="thumb" data-play="${v.id}" aria-label="تشغيل">${icon('play')}</button>
            <div class="vr-body"><b>${esc(v.title)}</b>
              <div class="meta">${badge(SOURCE[v.source] || SOURCE.upload)}${v.duration ? `<span>${icon('clock')} ${fmtClock(v.duration)}</span>` : ''}${v.size ? `<span>${fmtSize(v.size)}</span>` : ''}${v.description ? `<span class="clamp1">${esc(v.description)}</span>` : ''}</div></div>
            <div class="row-actions">
              <button class="icon-btn" data-move="${v.id}" data-to="${v.order - 1}" ${i === 0 ? 'disabled' : ''} title="تقديم" aria-label="تقديم">${icon('up')}</button>
              <button class="icon-btn" data-move="${v.id}" data-to="${v.order + 1}" ${i === vids.length - 1 ? 'disabled' : ''} title="تأخير" aria-label="تأخير">${icon('down')}</button>
              <button class="icon-btn" data-edit-v="${v.id}" title="تعديل" aria-label="تعديل">${icon('edit')}</button>
              <button class="icon-btn danger" data-del-v="${v.id}" title="حذف" aria-label="حذف">${icon('trash')}</button>
            </div>
          </li>`).join('')}</ol>`
      : empty('video', 'لا توجد مقاطع في هذه الدورة بعد', 'ارفع أول مقطع فيديو ليبدأ متدربوك التعلّم.', `<button class="btn btn-primary" data-up>${icon('upload')} ارفع أول مقطع</button>`));
  const find = (id) => vids.find((v) => v.id === id);
  $$('[data-up]', el).forEach((b) => b.addEventListener('click', () => uploadVideoModal(d)));
  $$('[data-play]', el).forEach((b) => b.addEventListener('click', () => {
    const v = find(b.dataset.play);
    modal({ title: v.title, wide: true, submitText: null, body: `<video src="${esc(v.url)}" controls autoplay playsinline></video>` });
  }));
  $$('[data-move]', el).forEach((b) => b.addEventListener('click', async () => {
    try { await api(`/t/videos/${b.dataset.move}`, 'PUT', { position: Number(b.dataset.to) }); router(); } catch (err) { toast(err.message, 'error'); }
  }));
  $$('[data-edit-v]', el).forEach((b) => b.addEventListener('click', () => {
    const v = find(b.dataset.editV);
    modal({
      title: 'تعديل المقطع',
      body: field('العنوان', `<input type="text" name="title" maxlength="150" value="${esc(v.title)}">`)
        + field('الوصف', `<textarea name="description" rows="3">${esc(v.description)}</textarea>`)
        + field('الترتيب في الدورة', `<select name="position">${vids.map((_, i) => `<option value="${i + 1}" ${i + 1 === v.order ? 'selected' : ''}>${i + 1}</option>`).join('')}</select>`),
      async onSubmit(form) {
        await api(`/t/videos/${v.id}`, 'PUT', { title: form.title.value, description: form.description.value, position: Number(form.position.value) });
        toast('تم حفظ المقطع', 'success');
        router();
      },
    });
  }));
  $$('[data-del-v]', el).forEach((b) => b.addEventListener('click', async () => {
    const v = find(b.dataset.delV);
    if (!(await confirmBox(`حذف المقطع «${v.title}»؟ ستُحذف معه سجلات مشاهدته.`, { title: 'حذف المقطع', ok: 'حذف' }))) return;
    try { await api(`/t/videos/${v.id}`, 'DELETE'); toast('تم حذف المقطع'); router(); } catch (err) { toast(err.message, 'error'); }
  }));
}

function uploadVideoModal(d) {
  const n = d.videos.length;
  const positions = Array.from({ length: n + 1 }, (_, i) => (i === n
    ? `<option value="${n + 1}" selected>في النهاية (رقم ${n + 1})</option>`
    : `<option value="${i + 1}">رقم ${i + 1} (قبل «${esc(d.videos[i].title)}»)</option>`)).join('');
  modal({
    title: 'رفع مقطع جديد',
    submitText: 'رفع المقطع',
    body: field('عنوان المقطع', '<input type="text" name="title" maxlength="150" required>')
      + field('الوصف', '<textarea name="description" rows="3"></textarea>')
      + field('ملف الفيديو', '<input type="file" name="file" accept="video/*" required>', 'يُفضّل MP4 لأنه يعمل على كل المتصفحات')
      + field('الترتيب في الدورة', `<select name="position">${positions}</select>`)
      + '<div class="upbar" hidden><span></span></div>',
    onMount(form) {
      form.file.addEventListener('change', () => {
        const f = form.file.files[0];
        if (f && !form.title.value.trim()) form.title.value = f.name.replace(/\.[^.]+$/, '');
      });
    },
    async onSubmit(form) {
      const file = form.file.files[0];
      if (!form.title.value.trim()) throw new Error('أدخل عنوان المقطع');
      if (!file) throw new Error('اختر ملف الفيديو');
      await upload(`/t/courses/${d.course.id}/videos`, file, { title: form.title.value, description: form.description.value, position: form.position.value }, (p) => setUpbar(form, p));
      toast('تم رفع المقطع', 'success');
      router();
    },
  });
}

/* ---------- live sessions ---------- */

function liveModal(live, courses, presetCourseId) {
  const start = live ? live.startsAt : Math.ceil((Date.now() + 3600000) / 1800000) * 1800000;
  modal({
    title: live ? 'تعديل البث' : 'جدولة بث مباشر',
    submitText: live ? 'حفظ' : 'جدولة البث',
    body: field('عنوان البث', `<input type="text" name="title" maxlength="150" value="${esc(live && live.title)}" required>`)
      + field('الدورة', `<select name="courseId">${courseOptions(courses, live ? live.courseId : presetCourseId)}</select>`)
      + `<div class="grid-2">${field('التاريخ والوقت', `<input type="datetime-local" name="startsAt" value="${toLocalInput(start)}" required>`)}${field('المدة بالدقائق', `<input type="number" name="duration" min="5" max="600" value="${live ? live.durationMinutes : 60}">`)}</div>`
      + field('رابط البث', `<input type="url" name="url" placeholder="https://meet.google.com/..." value="${esc(live && live.url)}" required>`, 'رابط Zoom أو Google Meet أو YouTube Live أو غيرها'),
    async onSubmit(form) {
      const startsAt = new Date(form.startsAt.value).getTime();
      if (!startsAt) throw new Error('حدد تاريخ البث ووقته');
      const body = { title: form.title.value, courseId: form.courseId.value, startsAt, durationMinutes: Number(form.duration.value), url: form.url.value };
      if (live) await api(`/t/lives/${live.id}`, 'PUT', body);
      else await api('/t/lives', 'POST', body);
      toast(live ? 'تم حفظ البث' : 'تمت جدولة البث', 'success');
      router();
    },
  });
}

function recordingModal(live) {
  modal({
    title: 'رفع تسجيل البث',
    submitText: 'رفع التسجيل',
    body: `<p class="hint" style="margin-bottom:14px">سيُضاف التسجيل تلقائيًا إلى فيديوهات دورة «${esc(live.courseTitle)}» بوسم «تسجيل بث».</p>`
      + field('عنوان المقطع', `<input type="text" name="title" maxlength="150" value="${esc(live.title)}">`)
      + field('ملف التسجيل', '<input type="file" name="file" accept="video/*" required>')
      + '<div class="upbar" hidden><span></span></div>',
    async onSubmit(form) {
      const file = form.file.files[0];
      if (!file) throw new Error('اختر ملف التسجيل');
      await upload(`/t/lives/${live.id}/recording`, file, { title: form.title.value }, (p) => setUpbar(form, p));
      toast('تمت إضافة التسجيل إلى فيديوهات الدورة', 'success');
      router();
    },
  });
}

function trainerLiveRow(l, showCourse) {
  const actions = [];
  if (l.status !== 'ended') actions.push(`<a class="btn btn-sm ${l.status === 'live' ? 'btn-live' : 'btn-ghost'}" href="${esc(l.url)}" target="_blank" rel="noopener">${icon('external')} ${l.status === 'live' ? 'ادخل البث' : 'رابط البث'}</a>`);
  if (l.status === 'live') actions.push(`<button class="btn btn-sm btn-ghost" data-end="${l.id}">إنهاء البث</button>`);
  if (l.status === 'ended' && !l.recordingVideoId) actions.push(`<button class="btn btn-sm btn-primary" data-rec="${l.id}">${icon('upload')} رفع التسجيل</button>`);
  if (l.status === 'ended' && l.recordingVideoId) actions.push(`<a class="btn btn-sm btn-ghost" href="#/t/courses/${l.courseId}?tab=videos">${icon('video')} التسجيل في الفيديوهات</a>`);
  if (l.status !== 'ended') actions.push(`<button class="icon-btn" data-edit-l="${l.id}" title="تعديل" aria-label="تعديل">${icon('edit')}</button>`);
  actions.push(`<button class="icon-btn danger" data-del-l="${l.id}" title="حذف" aria-label="حذف">${icon('trash')}</button>`);
  return `<div class="live-row status-${l.status}">
    <span class="date-box"><b>${fmtDay(l.startsAt)}</b><span>${fmtMonth(l.startsAt)}</span></span>
    <div class="lr-body"><div class="lr-title"><b>${esc(l.title)}</b>${badge(LIVE_STATUS[l.status])}${l.recordingVideoId ? '<span class="badge badge-rec">التسجيل مرفوع</span>' : ''}</div>
      <div class="meta">${showCourse ? `<span>${icon('book')} ${esc(l.courseTitle)}</span>` : ''}<span>${icon('clock')} ${fmtDateTime(l.startsAt)} · ${l.durationMinutes} دقيقة</span></div></div>
    <div class="row-actions">${actions.join('')}</div>
  </div>`;
}

function groupLives(lives) {
  return {
    live: lives.filter((l) => l.status === 'live'),
    upcoming: lives.filter((l) => l.status === 'upcoming'),
    ended: lives.filter((l) => l.status === 'ended').reverse(),
  };
}

function renderLiveList(el, lives, { courses, courseId, showCourse, autoOpen }) {
  const g = groupLives(lives);
  const newBtn = `<button class="btn btn-primary" data-new-live>${icon('plus')} جدولة بث</button>`;
  const section = (title, list) => (list.length ? `<h3 class="group-title">${title}</h3><div class="list">${list.map((l) => trainerLiveRow(l, showCourse)).join('')}</div>` : '');
  let body;
  if (!courses.length) body = empty('book', 'أنشئ دورة أولًا', 'كل بث يرتبط بدورة، فابدأ بإنشاء دورتك الأولى.', '<a class="btn btn-primary" href="#/t/courses?new=1">إنشاء دورة</a>');
  else if (!lives.length) body = empty('live', 'لا توجد بثوث مجدولة', 'جدول بثًا مباشرًا بعنوان وموعد ورابط، وبعد انتهائه ارفع تسجيله ليُضاف للدورة.', `<button class="btn btn-primary" data-new-live>${icon('plus')} جدولة أول بث</button>`);
  else body = section('مباشر الآن', g.live) + section('البثوث القادمة', g.upcoming) + section('البثوث المنتهية', g.ended);
  el.innerHTML = (courseId ? `<div class="section-head"><h2>البث المباشر</h2>${lives.length ? newBtn : ''}</div>` : '') + body;
  const find = (id) => lives.find((l) => l.id === id);
  $$('[data-new-live]', el).forEach((b) => b.addEventListener('click', () => liveModal(null, courses, courseId)));
  $$('[data-edit-l]', el).forEach((b) => b.addEventListener('click', () => liveModal(find(b.dataset.editL), courses)));
  $$('[data-rec]', el).forEach((b) => b.addEventListener('click', () => recordingModal(find(b.dataset.rec))));
  $$('[data-end]', el).forEach((b) => b.addEventListener('click', async () => {
    if (!(await confirmBox('إنهاء البث الآن؟ بعدها يمكنك رفع التسجيل.', { title: 'إنهاء البث', ok: 'إنهاء البث' }))) return;
    try { await api(`/t/lives/${b.dataset.end}/end`, 'POST'); toast('تم إنهاء البث'); router(); } catch (err) { toast(err.message, 'error'); }
  }));
  $$('[data-del-l]', el).forEach((b) => b.addEventListener('click', async () => {
    const l = find(b.dataset.delL);
    if (!(await confirmBox(`حذف البث «${l.title}»؟${l.recordingVideoId ? ' سيبقى تسجيله ضمن فيديوهات الدورة.' : ''}`, { title: 'حذف البث', ok: 'حذف' }))) return;
    try { await api(`/t/lives/${l.id}`, 'DELETE'); toast('تم حذف البث'); router(); } catch (err) { toast(err.message, 'error'); }
  }));
  if (autoOpen && courses.length) liveModal(null, courses, courseId);
}

route('/t/lives', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'lives');
  const d = await api('/t/lives');
  if (ctx.stale()) return;
  const showNew = d.courses.length && d.lives.length;
  main.innerHTML = pageHead('البث المباشر', 'جدول بثوثك المباشرة وتابع حالتها، وارفع التسجيل بعد انتهاء البث.', showNew ? `<button class="btn btn-primary" data-new-live>${icon('plus')} جدولة بث</button>` : '') + '<div id="lives"></div>';
  renderLiveList($('#lives', main), d.lives, { courses: d.courses, showCourse: true, autoOpen: !!ctx.query.new });
  const top = $('.page-head [data-new-live]', main);
  if (top) top.addEventListener('click', () => liveModal(null, d.courses));
});

/* ---------- question banks ---------- */

function bankAudience(b) {
  if (!b.courseId) return 'خاص بك فقط';
  if (b.audience === 'selected') return `يظهر لـ ${b.traineeIds.length} من المتدربين`;
  return 'يظهر لكل متدربي الدورة';
}

function bankModal(bank, courses) {
  modal({
    title: bank ? 'تعديل بنك الأسئلة' : 'بنك أسئلة جديد',
    submitText: bank ? 'حفظ' : 'إنشاء البنك',
    body: field('اسم البنك', `<input type="text" name="name" maxlength="100" value="${esc(bank && bank.name)}" placeholder="مثال: أسئلة الوحدة الأولى" required>`)
      + field('ربط بدورة', `<select name="courseId"><option value="">بدون ربط (خاص بك فقط)</option>${courseOptions(courses, bank && bank.courseId)}</select>`, 'يظهر البنك في صفحة «بنك الأسئلة» لمتدربي الدورة المرتبطة.')
      + `<div class="field" id="audBox"><span>يظهر البنك لـ</span>
          <div><div class="seg"><label><input type="radio" name="audience" value="all"><span>كل متدربي الدورة</span></label><label><input type="radio" name="audience" value="selected"><span>متدربين محددين</span></label></div></div>
          <div id="trList" class="pick-list"></div></div>`,
    onMount(form) {
      const chosen = new Set(bank ? bank.traineeIds : []);
      const audBox = $('#audBox', form);
      const trList = $('#trList', form);
      form.querySelector(`input[name=audience][value=${bank && bank.audience === 'selected' ? 'selected' : 'all'}]`).checked = true;
      let loadedFor = null;
      const draw = async () => {
        const courseId = form.courseId.value;
        audBox.hidden = !courseId;
        const selected = form.audience.value === 'selected';
        trList.hidden = !courseId || !selected;
        if (!courseId || !selected || loadedFor === courseId) return;
        loadedFor = courseId;
        trList.innerHTML = '<span class="hint">جارٍ تحميل المتدربين…</span>';
        try {
          const d = await api(`/t/courses/${courseId}/trainees`);
          if (loadedFor !== courseId) return;
          trList.innerHTML = d.trainees.length
            ? `<div class="pick-head"><span class="hint">اختر المتدربين الذين يظهر لهم البنك</span><button type="button" class="btn btn-ghost btn-sm" data-pick-all>تحديد الكل</button></div>`
              + d.trainees.map((t) => `<label class="pool-q"><input type="checkbox" value="${esc(t.id)}" ${chosen.has(t.id) ? 'checked' : ''}><span class="grow"><span>${esc(t.name)}</span><small class="muted" dir="ltr">${esc(t.email)}</small></span></label>`).join('')
            : '<p class="hint">لا يوجد متدربون ملتحقون بهذه الدورة بعد.</p>';
        } catch (err) {
          trList.innerHTML = `<p class="hint">${esc(err.message)}</p>`;
          loadedFor = null;
        }
      };
      trList.addEventListener('change', (e) => {
        if (e.target.type !== 'checkbox') return;
        if (e.target.checked) chosen.add(e.target.value); else chosen.delete(e.target.value);
      });
      trList.addEventListener('click', (e) => {
        if (!e.target.closest('[data-pick-all]')) return;
        const boxes = $$('input[type=checkbox]', trList);
        const all = boxes.every((b) => b.checked);
        boxes.forEach((b) => { b.checked = !all; if (b.checked) chosen.add(b.value); else chosen.delete(b.value); });
      });
      form.courseId.addEventListener('change', () => { chosen.clear(); draw(); });
      $$('input[name=audience]', form).forEach((r) => r.addEventListener('change', draw));
      draw();
    },
    async onSubmit(form) {
      const audience = form.courseId.value ? form.audience.value : 'all';
      const traineeIds = audience === 'selected' ? $$('#trList input[type=checkbox]:checked', form).map((b) => b.value) : [];
      const body = { name: form.name.value, courseId: form.courseId.value, audience, traineeIds };
      const res = bank ? await api(`/t/banks/${bank.id}`, 'PUT', body) : await api('/t/banks', 'POST', body);
      toast(bank ? 'تم حفظ البنك' : 'تم إنشاء البنك', 'success');
      if (bank) router(); else go(`/t/banks/${res.bank.id}`);
    },
  });
}

route('/t/banks', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'banks');
  const d = await api('/t/banks');
  if (ctx.stale()) return;
  const newBtn = `<button class="btn btn-primary" data-new>${icon('plus')} بنك جديد</button>`;
  main.innerHTML = pageHead('بنوك الأسئلة', 'نظّم أسئلتك في بنوك، ثم اختر منها أسئلة اختباراتك.', d.banks.length ? newBtn : '')
    + (d.banks.length
      ? `<div class="course-grid">${d.banks.map((b) => `
          <div class="bank-card">
            <div class="bank-top"><a href="#/t/banks/${b.id}" class="bank-ic">${icon('bank')}</a>
              <div class="row-actions"><button class="icon-btn" data-edit="${b.id}" title="تعديل" aria-label="تعديل">${icon('edit')}</button><button class="icon-btn danger" data-del="${b.id}" title="حذف" aria-label="حذف">${icon('trash')}</button></div></div>
            <a href="#/t/banks/${b.id}"><h3>${esc(b.name)}</h3></a>
            <span class="meta"><span>${icon('book')} ${b.courseTitle ? esc(b.courseTitle) : 'غير مرتبط بدورة'}</span><span>${icon('users')} ${bankAudience(b)}</span></span>
            <div class="bank-count"><b>${b.questionCount}</b><span>سؤال</span></div>
            <a class="btn btn-ghost btn-sm" href="#/t/banks/${b.id}">فتح البنك</a>
          </div>`).join('')}</div>`
      : empty('bank', 'لا توجد بنوك أسئلة بعد', 'أنشئ بنكًا وسمّه كما تريد، واربطه بدورة إن أردت، ثم أضف إليه الأسئلة.', `<button class="btn btn-primary" data-new>${icon('plus')} إنشاء أول بنك</button>`));
  const find = (id) => d.banks.find((b) => b.id === id);
  $$('[data-new]', main).forEach((b) => b.addEventListener('click', () => bankModal(null, d.courses)));
  $$('[data-edit]', main).forEach((b) => b.addEventListener('click', () => bankModal(find(b.dataset.edit), d.courses)));
  $$('[data-del]', main).forEach((b) => b.addEventListener('click', async () => {
    const bank = find(b.dataset.del);
    if (!(await confirmBox(`حذف البنك «${bank.name}» وجميع أسئلته (${bank.questionCount})؟ ستُزال هذه الأسئلة من أي اختبار يستخدمها.`, { title: 'حذف البنك', ok: 'حذف البنك' }))) return;
    try { await api(`/t/banks/${bank.id}`, 'DELETE'); toast('تم حذف البنك'); router(); } catch (err) { toast(err.message, 'error'); }
  }));
});

function questionModal(bankId, q) {
  let type = q ? q.type : 'mcq';
  let mcqOptions = q && q.type === 'mcq' ? q.options.slice() : ['', '', '', ''];
  let mcqCorrect = q && q.type === 'mcq' ? q.correct : 0;
  let tfCorrect = q && q.type === 'tf' ? q.correct : 0;
  modal({
    title: q ? 'تعديل السؤال' : 'سؤال جديد',
    submitText: q ? 'حفظ السؤال' : 'إضافة السؤال',
    wide: true,
    body: field('نص السؤال', `<textarea name="text" rows="3" maxlength="2000" required>${esc(q && q.text)}</textarea>`)
      + `<div class="grid-2">${field('نوع السؤال', `<select name="type"><option value="mcq">${QTYPE.mcq}</option><option value="tf">${QTYPE.tf}</option></select>`)}`
      + `${field('مستوى الصعوبة', `<select name="difficulty">${Object.entries(DIFF).map(([k, v]) => `<option value="${k}" ${(q ? q.difficulty : 'medium') === k ? 'selected' : ''}>${v}</option>`).join('')}</select>`)}</div>`
      + '<div class="field"><span>الخيارات <small class="muted">(حدّد الإجابة الصحيحة)</small></span><div id="opts"></div></div>'
      + field('شرح الإجابة <small class="muted">(اختياري، يظهر للمتدرب بعد الاختبار)</small>', `<textarea name="explanation" rows="3" maxlength="3000">${esc(q && q.explanation)}</textarea>`),
    onMount(form) {
      form.type.value = type;
      const box = $('#opts', form);
      const draw = () => {
        if (type === 'tf') {
          box.innerHTML = `<div class="tf-row">${['صح', 'خطأ'].map((t, i) => `<label><input type="radio" name="correct" value="${i}" ${tfCorrect === i ? 'checked' : ''}>${t}</label>`).join('')}</div>`;
          return;
        }
        box.innerHTML = mcqOptions.map((o, i) => `<div class="opt-row"><input type="radio" name="correct" value="${i}" ${mcqCorrect === i ? 'checked' : ''} aria-label="الإجابة الصحيحة"><input type="text" data-opt="${i}" value="${esc(o)}" placeholder="الخيار ${i + 1}" maxlength="300">${mcqOptions.length > 2 ? `<button type="button" class="icon-btn danger" data-rm="${i}" aria-label="حذف الخيار">${icon('x')}</button>` : ''}</div>`).join('')
          + (mcqOptions.length < 6 ? `<button type="button" class="btn btn-ghost btn-sm" data-add>${icon('plus')} إضافة خيار</button>` : '');
      };
      box.addEventListener('input', (e) => { if (e.target.dataset.opt != null) mcqOptions[Number(e.target.dataset.opt)] = e.target.value; });
      box.addEventListener('change', (e) => {
        if (e.target.name !== 'correct') return;
        if (type === 'tf') tfCorrect = Number(e.target.value); else mcqCorrect = Number(e.target.value);
      });
      box.addEventListener('click', (e) => {
        const rm = e.target.closest('[data-rm]');
        if (rm) {
          const i = Number(rm.dataset.rm);
          mcqOptions.splice(i, 1);
          if (mcqCorrect === i) mcqCorrect = 0; else if (mcqCorrect > i) mcqCorrect--;
          draw();
        } else if (e.target.closest('[data-add]')) {
          mcqOptions.push('');
          draw();
          const inputs = $$('[data-opt]', box);
          inputs[inputs.length - 1].focus();
        }
      });
      form.type.addEventListener('change', () => { type = form.type.value; draw(); });
      draw();
    },
    async onSubmit(form) {
      let options = [];
      let correct = type === 'tf' ? tfCorrect : mcqCorrect;
      if (type === 'mcq') {
        const filled = mcqOptions.map((o, i) => ({ o: o.trim(), i })).filter((x) => x.o);
        if (!filled.some((x) => x.i === mcqCorrect)) throw new Error('الإجابة الصحيحة المحددة فارغة، اكتب نصها أو اختر خيارًا آخر');
        correct = filled.findIndex((x) => x.i === mcqCorrect);
        options = filled.map((x) => x.o);
      }
      const body = { text: form.text.value, type, options, correct, difficulty: form.difficulty.value, explanation: form.explanation.value };
      if (q) await api(`/t/questions/${q.id}`, 'PUT', body);
      else await api(`/t/banks/${bankId}/questions`, 'POST', body);
      toast(q ? 'تم حفظ السؤال' : 'تمت إضافة السؤال', 'success');
      router();
    },
  });
}

function questionCard(q, num, actions = true) {
  return `<article class="q-card">
    <div class="q-head"><span class="q-num">${num}</span><p class="q-text">${esc(q.text)}</p>
      ${actions ? `<div class="row-actions"><button class="icon-btn" data-edit-q="${q.id}" title="تعديل" aria-label="تعديل">${icon('edit')}</button><button class="icon-btn danger" data-del-q="${q.id}" title="حذف" aria-label="حذف">${icon('trash')}</button></div>` : ''}</div>
    <div class="q-badges"><span class="badge">${QTYPE[q.type]}</span><span class="badge badge-${q.difficulty}">${DIFF[q.difficulty]}</span>${q.bankName ? `<span class="badge">${icon('bank')} ${esc(q.bankName)}</span>` : ''}</div>
    <ul class="q-options">${q.options.map((o, j) => `<li class="${j === q.correct ? 'correct' : ''}">${j === q.correct ? icon('check') : '<span class="dot"></span>'}${esc(o)}</li>`).join('')}</ul>
    ${q.explanation ? `<details class="q-exp"><summary>شرح الإجابة</summary><p>${esc(q.explanation)}</p></details>` : ''}
  </article>`;
}

route('/t/banks/:id', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'banks');
  const d = await api(`/t/banks/${ctx.params.id}`);
  if (ctx.stale()) return;
  const b = d.bank;
  const addBtn = `<button class="btn btn-primary" data-add-q>${icon('plus')} إضافة سؤال</button>`;
  main.innerHTML = `
    <a class="back" href="#/t/banks">${icon('prev')} بنوك الأسئلة</a>
    ${pageHead(esc(b.name), `${b.courseTitle ? `مرتبط بدورة «${esc(b.courseTitle)}»` : 'غير مرتبط بدورة'} · ${b.questionCount} سؤال · ${bankAudience(b)}`, `<div class="row-actions"><button class="btn btn-ghost" data-edit-b>${icon('edit')} تعديل البنك</button>${addBtn}</div>`)}
    ${d.questions.length ? `<div class="toolbar">
      <label class="search">${icon('search')}<input type="search" id="fq" placeholder="ابحث في نص الأسئلة" aria-label="بحث"></label>
      <select id="ft" aria-label="النوع"><option value="">كل الأنواع</option><option value="mcq">${QTYPE.mcq}</option><option value="tf">${QTYPE.tf}</option></select>
      <select id="fd" aria-label="الصعوبة"><option value="">كل المستويات</option>${Object.entries(DIFF).map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}</select>
    </div>` : ''}
    <div id="qlist"></div>`;
  const list = $('#qlist', main);
  const draw = () => {
    const q = ($('#fq', main) || {}).value || '';
    const t = ($('#ft', main) || {}).value || '';
    const df = ($('#fd', main) || {}).value || '';
    const items = d.questions.filter((x) => (!t || x.type === t) && (!df || x.difficulty === df) && (!q || x.text.toLowerCase().includes(q.trim().toLowerCase())));
    if (!d.questions.length) list.innerHTML = empty('question', 'هذا البنك فارغ', 'أضف أول سؤال: اختيار من متعدد أو صح وخطأ، مع مستوى الصعوبة وشرح الإجابة.', `<button class="btn btn-primary" data-add-q>${icon('plus')} إضافة أول سؤال</button>`);
    else if (!items.length) list.innerHTML = empty('search', 'لا توجد أسئلة مطابقة', 'جرّب تغيير كلمات البحث أو الفلاتر.');
    else list.innerHTML = (items.length !== d.questions.length ? `<p class="hint" style="margin-bottom:10px">يظهر ${items.length} من ${d.questions.length} سؤال</p>` : '') + items.map((x, i) => questionCard(x, i + 1)).join('');
    $$('[data-add-q]', list).forEach((btn) => btn.addEventListener('click', () => questionModal(b.id)));
    $$('[data-edit-q]', list).forEach((btn) => btn.addEventListener('click', () => questionModal(b.id, d.questions.find((x) => x.id === btn.dataset.editQ))));
    $$('[data-del-q]', list).forEach((btn) => btn.addEventListener('click', async () => {
      if (!(await confirmBox('حذف هذا السؤال؟ سيُزال أيضًا من أي اختبار يستخدمه.', { title: 'حذف السؤال', ok: 'حذف' }))) return;
      try { await api(`/t/questions/${btn.dataset.delQ}`, 'DELETE'); toast('تم حذف السؤال'); router(); } catch (err) { toast(err.message, 'error'); }
    }));
  };
  ['#fq', '#ft', '#fd'].forEach((s) => { const el = $(s, main); if (el) el.addEventListener('input', draw); });
  $('.page-head [data-add-q]', main).addEventListener('click', () => questionModal(b.id));
  $('[data-edit-b]', main).addEventListener('click', () => bankModal(b, d.courses));
  draw();
});

/* ---------- exams ---------- */

function examRow(e, showCourse) {
  return `<div class="exam-card">
    <span class="ex-ic">${icon('exam')}</span>
    <div class="grow"><div class="lr-title"><b>${esc(e.title)}</b>${badge(EXAM_STATUS[e.status])}</div>
      <div class="meta">${showCourse ? `<span>${icon('book')} ${esc(e.courseTitle)}</span>` : ''}<span>${icon('clock')} ${e.durationMinutes} دقيقة</span><span>${icon('question')} ${e.questionCount} سؤال</span><span>${icon('users')} ${e.attempts} محاولة مكتملة</span></div></div>
    <div class="row-actions">
      <button class="btn btn-sm btn-ghost" data-toggle="${e.id}">${e.status === 'published' ? 'إرجاع لمسودة' : 'نشر'}</button>
      <a class="icon-btn" href="#/t/exams/${e.id}" title="تعديل" aria-label="تعديل">${icon('edit')}</a>
      <button class="icon-btn danger" data-del-e="${e.id}" title="حذف" aria-label="حذف">${icon('trash')}</button>
    </div>
  </div>`;
}

function renderExamList(el, exams, { courseId, showCourse, header = true, noCourses = false }) {
  const newHref = `#/t/exams/new${courseId ? `?course=${courseId}` : ''}`;
  let body;
  if (noCourses) body = empty('book', 'أنشئ دورة أولًا', 'كل اختبار يتبع دورة، فابدأ بإنشاء دورتك الأولى.', '<a class="btn btn-primary" href="#/t/courses?new=1">إنشاء دورة</a>');
  else if (!exams.length) body = empty('exam', 'لا توجد اختبارات بعد', 'أنشئ اختبارًا بعنوان ومدة، واختر أسئلته من بنك أو أكثر.', `<a class="btn btn-primary" href="${newHref}">${icon('plus')} إنشاء أول اختبار</a>`);
  else body = `<div class="list">${exams.map((e) => examRow(e, showCourse)).join('')}</div>`;
  el.innerHTML = (header ? `<div class="section-head"><h2>الاختبارات</h2>${exams.length ? `<a class="btn btn-primary" href="${newHref}">${icon('plus')} اختبار جديد</a>` : ''}</div>` : '') + body;
  const find = (id) => exams.find((e) => e.id === id);
  $$('[data-toggle]', el).forEach((b) => b.addEventListener('click', async () => {
    const e = find(b.dataset.toggle);
    const status = e.status === 'published' ? 'draft' : 'published';
    try {
      await api(`/t/exams/${e.id}`, 'PUT', { title: e.title, courseId: e.courseId, durationMinutes: e.durationMinutes, questionIds: e.questionIds, status });
      toast(status === 'published' ? 'تم نشر الاختبار' : 'أصبح الاختبار مسودة', 'success');
      router();
    } catch (err) { toast(err.message, 'error'); }
  }));
  $$('[data-del-e]', el).forEach((b) => b.addEventListener('click', async () => {
    const e = find(b.dataset.delE);
    if (!(await confirmBox(`حذف الاختبار «${e.title}»؟ ستُحذف معه نتائج المتدربين فيه.`, { title: 'حذف الاختبار', ok: 'حذف' }))) return;
    try { await api(`/t/exams/${e.id}`, 'DELETE'); toast('تم حذف الاختبار'); router(); } catch (err) { toast(err.message, 'error'); }
  }));
}

route('/t/exams', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'exams');
  const d = await api('/t/exams');
  if (ctx.stale()) return;
  main.innerHTML = pageHead('الاختبارات', 'أنشئ اختباراتك من بنوك الأسئلة، واحفظها مسودة أو انشرها للمتدربين.', d.exams.length ? `<a class="btn btn-primary" href="#/t/exams/new">${icon('plus')} اختبار جديد</a>` : '') + '<div id="exams"></div>';
  renderExamList($('#exams', main), d.exams, { showCourse: true, header: false, noCourses: !d.courses.length });
});

route('/t/exams/:id', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'exams');
  const isNew = ctx.params.id === 'new';
  const [pool, existing] = await Promise.all([api('/t/question-pool'), isNew ? null : api(`/t/exams/${ctx.params.id}`)]);
  if (ctx.stale()) return;
  if (!pool.courses.length) {
    main.innerHTML = `<a class="back" href="#/t/exams">${icon('prev')} الاختبارات</a>` + empty('book', 'أنشئ دورة أولًا', 'كل اختبار يتبع دورة، فابدأ بإنشاء دورتك الأولى.', '<a class="btn btn-primary" href="#/t/courses?new=1">إنشاء دورة</a>');
    return;
  }
  const exam = existing ? existing.exam : { title: '', courseId: ctx.query.course || pool.courses[0].id, durationMinutes: 30, status: 'draft', questionIds: [] };
  const selected = exam.questionIds.slice();
  const totalQ = pool.banks.reduce((a, b) => a + b.questions.length, 0);
  main.innerHTML = `
    <a class="back" href="#/t/exams">${icon('prev')} الاختبارات</a>
    ${pageHead(isNew ? 'اختبار جديد' : 'تعديل الاختبار', 'حدد بيانات الاختبار ثم اختر أسئلته من بنك أو أكثر.')}
    <form class="exam-form" novalidate>
      <section class="card">
        <div class="grid-3">
          ${field('عنوان الاختبار', `<input type="text" name="title" maxlength="150" value="${esc(exam.title)}" required>`)}
          ${field('الدورة', `<select name="courseId">${courseOptions(pool.courses, exam.courseId)}</select>`)}
          ${field('المدة بالدقائق', `<input type="number" name="duration" min="1" max="600" value="${exam.durationMinutes}">`)}
        </div>
        <div class="field" style="margin:0"><span>حالة الاختبار</span>
          <div><div class="seg"><label><input type="radio" name="status" value="draft" ${exam.status !== 'published' ? 'checked' : ''}><span>مسودة</span></label><label><input type="radio" name="status" value="published" ${exam.status === 'published' ? 'checked' : ''}><span>منشور</span></label></div></div>
          <small class="hint">المسودة لا تظهر للمتدربين، والمنشور يظهر لكل الملتحقين بالدورة.</small>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h2>اختيار الأسئلة</h2><span class="hint">${totalQ} سؤال في ${pool.banks.length} بنك</span></div>
        ${totalQ ? `<div class="toolbar">
          <label class="search">${icon('search')}<input type="search" id="pq" placeholder="ابحث في الأسئلة" aria-label="بحث"></label>
          <select id="pt" aria-label="النوع"><option value="">كل الأنواع</option><option value="mcq">${QTYPE.mcq}</option><option value="tf">${QTYPE.tf}</option></select>
          <select id="pd" aria-label="الصعوبة"><option value="">كل المستويات</option>${Object.entries(DIFF).map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}</select>
        </div><div id="pool"></div>`
        : empty('bank', 'لا توجد أسئلة بعد', 'أنشئ بنك أسئلة وأضف إليه أسئلة، ثم ارجع لاختيارها هنا. يمكنك حفظ الاختبار مسودة الآن.', '<a class="btn btn-ghost" href="#/t/banks">الذهاب إلى بنوك الأسئلة</a>')}
      </section>
      <div class="form-error" hidden></div>
      <div class="sticky-bar"><span><b id="selCount">0</b> سؤال محدد</span>
        <div class="actions"><a class="btn btn-ghost" href="#/t/exams">إلغاء</a><button class="btn btn-primary" type="submit">${isNew ? 'إنشاء الاختبار' : 'حفظ التعديلات'}</button></div></div>
    </form>`;
  const form = $('form', main);
  const poolEl = $('#pool', main);
  const updateCount = () => { $('#selCount', main).textContent = selected.length; };
  const drawPool = () => {
    if (!poolEl) return;
    const q = ($('#pq', main).value || '').trim().toLowerCase();
    const t = $('#pt', main).value;
    const df = $('#pd', main).value;
    const open = new Set($$('details[open]', poolEl).map((x) => x.dataset.bank));
    const first = !poolEl.dataset.drawn;
    poolEl.dataset.drawn = '1';
    const html = pool.banks.map((b) => {
      const items = b.questions.filter((x) => (!t || x.type === t) && (!df || x.difficulty === df) && (!q || x.text.toLowerCase().includes(q)));
      if (!items.length) return '';
      const sel = b.questions.filter((x) => selected.includes(x.id)).length;
      const isOpen = first ? (sel > 0 || pool.banks.length === 1 || (b.courseId && b.courseId === form.courseId.value)) : (open.has(b.id) || !!q);
      return `<details class="pool-bank" data-bank="${b.id}" ${isOpen ? 'open' : ''}>
        <summary>${icon('bank')}<span class="grow">${esc(b.name)} ${b.courseTitle ? `<small class="muted">· ${esc(b.courseTitle)}</small>` : ''}</span><span class="sel-count">${sel} / ${b.questions.length}</span>
          <button type="button" class="btn btn-ghost btn-sm" data-all="${b.id}">${items.every((x) => selected.includes(x.id)) ? 'إلغاء التحديد' : 'تحديد الكل'}</button></summary>
        ${items.map((x) => `<label class="pool-q"><input type="checkbox" value="${x.id}" ${selected.includes(x.id) ? 'checked' : ''}><span class="grow"><span>${esc(x.text)}</span><span class="meta"><span class="badge">${QTYPE[x.type]}</span><span class="badge badge-${x.difficulty}">${DIFF[x.difficulty]}</span></span></span></label>`).join('')}
      </details>`;
    }).join('');
    poolEl.innerHTML = html || empty('search', 'لا توجد أسئلة مطابقة', 'جرّب تغيير كلمات البحث أو الفلاتر.');
    updateCount();
  };
  if (poolEl) {
    poolEl.addEventListener('change', (e) => {
      if (e.target.type !== 'checkbox') return;
      const id = e.target.value;
      const i = selected.indexOf(id);
      if (e.target.checked && i < 0) selected.push(id);
      if (!e.target.checked && i >= 0) selected.splice(i, 1);
      drawPool();
    });
    poolEl.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-all]');
      if (!btn) return;
      e.preventDefault();
      const details = btn.closest('details');
      const ids = $$('input[type=checkbox]', details).map((x) => x.value);
      const all = ids.every((id) => selected.includes(id));
      ids.forEach((id) => {
        const i = selected.indexOf(id);
        if (all && i >= 0) selected.splice(i, 1);
        if (!all && i < 0) selected.push(id);
      });
      details.open = true;
      drawPool();
    });
    ['#pq', '#pt', '#pd'].forEach((s) => $(s, main).addEventListener('input', drawPool));
    drawPool();
  }
  updateCount();
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    const err = $('.form-error', form);
    err.hidden = true;
    btn.classList.add('busy');
    try {
      const body = { title: form.title.value, courseId: form.courseId.value, durationMinutes: Number(form.duration.value), status: form.status.value, questionIds: selected };
      if (isNew) await api('/t/exams', 'POST', body);
      else await api(`/t/exams/${exam.id}`, 'PUT', body);
      toast(isNew ? 'تم إنشاء الاختبار' : 'تم حفظ الاختبار', 'success');
      go('/t/exams');
    } catch (ex) {
      err.textContent = ex.message;
      err.hidden = false;
      err.scrollIntoView({ behavior: 'smooth', block: 'center' });
    } finally {
      btn.classList.remove('busy');
    }
  });
});

/* ---------- trainees & results ---------- */

function traineeTable(rows) {
  return `<div class="table-wrap"><table class="table">
    <thead><tr><th>المتدرب</th><th>نسبة التقدّم</th><th>الفيديوهات</th><th>درجات الاختبارات</th></tr></thead>
    <tbody>${rows.map((t) => `<tr>
      <td data-label="المتدرب"><b>${esc(t.name)}</b><small class="muted d-block" dir="ltr" style="text-align:right">${esc(t.email)}</small></td>
      <td data-label="نسبة التقدّم">${barRow(t.progress.percent)}</td>
      <td data-label="الفيديوهات">${t.progress.videosWatched} من ${t.progress.videosTotal}</td>
      <td data-label="درجات الاختبارات">${t.exams.length ? t.exams.map((e) => `<span class="score ${e.percent == null ? 'pending' : ''}">${esc(e.title)}: ${e.percent == null ? (e.status === 'in_progress' ? 'جارٍ الحل' : 'لم يُحل') : e.percent + '%'}</span>`).join('') : '<span class="muted">لا توجد اختبارات منشورة</span>'}</td>
    </tr>`).join('')}</tbody></table></div>`;
}

function renderTraineeTable(el, rows) {
  el.innerHTML = '<div class="section-head"><h2>المتدربون الملتحقون</h2></div>'
    + (rows.length ? traineeTable(rows) : empty('users', 'لم يلتحق أي متدرب بهذه الدورة بعد', 'عندما يلتحق المتدربون ستظهر هنا نسبة تقدّمهم ودرجاتهم.'));
}

route('/t/trainees', { role: 'trainer' }, async (ctx) => {
  const main = startPage('trainer', 'trainees');
  const d = await api('/t/trainees');
  if (ctx.stale()) return;
  const total = new Set(d.courses.flatMap((c) => c.trainees.map((t) => t.id))).size;
  if (!d.courses.length) {
    main.innerHTML = pageHead('المتدربون والنتائج') + empty('book', 'لا توجد دورات بعد', 'أنشئ دورة لتبدأ باستقبال المتدربين.', '<a class="btn btn-primary" href="#/t/courses?new=1">إنشاء دورة</a>');
    return;
  }
  main.innerHTML = pageHead('المتدربون والنتائج', `${total} متدرب في دوراتك`, `<select id="fc" style="width:auto;min-width:220px" aria-label="الدورة"><option value="">كل الدورات</option>${courseOptions(d.courses, ctx.query.course)}</select>`) + '<div id="tlist"></div>';
  const draw = () => {
    const id = $('#fc', main).value;
    const courses = d.courses.filter((c) => !id || c.id === id);
    $('#tlist', main).innerHTML = courses.map((c) => `<section class="section" style="margin-top:0;margin-bottom:28px">
      <div class="section-head"><h2>${esc(c.title)}</h2><span class="hint">${c.trainees.length} متدرب</span></div>
      ${c.trainees.length ? traineeTable(c.trainees) : empty('users', 'لا يوجد متدربون ملتحقون', 'عندما يلتحق المتدربون بهذه الدورة ستظهر نتائجهم هنا.')}
    </section>`).join('');
  };
  $('#fc', main).addEventListener('change', draw);
  draw();
});

/* =====================================================================
   Trainee
   ===================================================================== */

function resumeButton(course, r) {
  if (!r || r.type === 'empty') return '<button class="btn btn-ghost btn-block" disabled>لا يوجد محتوى بعد</button>';
  if (r.type === 'video') return `<a class="btn btn-primary btn-block" href="#/s/videos/${r.id}">${icon('play')} ${r.position > 5 ? 'تابع من آخر نقطة' : 'ابدأ المقطع التالي'}</a>`;
  if (r.type === 'exam') return `<a class="btn btn-primary btn-block" href="#/s/exams">${icon('exam')} الاختبار التالي: ${esc(r.title)}</a>`;
  return `<a class="btn btn-ghost btn-block" href="#/s/courses/${course.id}">${icon('check')} أنهيت الدورة، استعرضها</a>`;
}

route('/s', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'courses');
  const d = await api('/s/home');
  if (ctx.stale()) return;
  const mine = d.enrolled.length
    ? `<div class="course-grid">${d.enrolled.map(({ course: c, progress: p, resume }) => `
        <article class="course-card">
          <a class="cover" href="#/s/courses/${c.id}">${cover(c)}</a>
          <div class="cc-body">
            <h3><a href="#/s/courses/${c.id}">${esc(c.title)}</a></h3>
            <span class="meta"><span>${icon('users')} ${esc(c.trainerName)}</span></span>
            ${barRow(p.percent)}
            <p class="detail">${progressDetail(p)}</p>
            ${resumeButton(c, resume)}
          </div>
        </article>`).join('')}</div>`
    : empty('book', 'لم تلتحق بأي دورة بعد', 'اختر دورة من الدورات المتاحة بالأسفل وابدأ التعلّم.');
  const available = d.available.length
    ? `<div class="course-grid">${d.available.map((c) => `
        <article class="course-card">
          <a class="cover" href="#/s/courses/${c.id}">${cover(c)}</a>
          <div class="cc-body">
            <h3><a href="#/s/courses/${c.id}">${esc(c.title)}</a></h3>
            <p class="muted clamp">${esc(c.description || 'بدون وصف')}</p>
            <span class="meta"><span>${icon('users')} ${esc(c.trainerName)}</span><span>${icon('video')} ${c.videos}</span><span>${icon('exam')} ${c.exams}</span></span>
            <button class="btn btn-primary btn-block" data-enroll="${c.id}">${icon('plus')} التحق بالدورة</button>
          </div>
        </article>`).join('')}</div>`
    : empty('spark', d.enrolled.length ? 'التحقت بكل الدورات المتاحة' : 'لا توجد دورات متاحة حاليًا', 'ستظهر هنا الدورات الجديدة فور نشرها.');
  main.innerHTML = pageHead('دوراتي', `مرحبًا ${esc(state.me.name)}، تابع تعلّمك من حيث توقفت.`) + mine
    + `<section class="section"><div class="section-head"><h2>الدورات المتاحة للالتحاق</h2></div>${available}</section>`;
  $$('[data-enroll]', main).forEach((b) => b.addEventListener('click', () => enroll(b, b.dataset.enroll)));
});

async function enroll(btn, courseId) {
  btn.classList.add('busy');
  try {
    await api(`/s/courses/${courseId}/enroll`, 'POST');
    toast('تم التحاقك بالدورة، بالتوفيق!', 'success');
    go(`/s/courses/${courseId}`);
  } catch (err) {
    toast(err.message, 'error');
    btn.classList.remove('busy');
  }
}

function traineeVideoRow(v, enrolled) {
  const tag = enrolled ? 'a' : 'div';
  const info = v.watched ? '<span class="ok">تمت المشاهدة</span>' : v.position > 5 ? `<span>توقفت عند ${fmtClock(v.position)}</span>` : '';
  return `<li><${tag} class="video-row ${v.watched ? 'watched' : ''}" ${enrolled ? `href="#/s/videos/${v.id}"` : ''}>
    <span class="order">${v.watched ? icon('check') : v.order}</span>
    <span class="vr-body"><b>${esc(v.title)}</b><span class="meta">${v.source === 'live' ? badge(SOURCE.live) : ''}${v.duration ? `<span>${icon('clock')} ${fmtClock(v.duration)}</span>` : ''}${info}</span></span>
    ${enrolled ? icon('play', 'go') : icon('lock', 'go')}
  </${tag}></li>`;
}

function traineeLiveRow(l) {
  let action;
  if (l.status === 'live') action = `<a class="btn btn-live" href="${esc(l.url)}" target="_blank" rel="noopener">${icon('external')} انضم الآن</a>`;
  else if (l.status === 'upcoming') action = `<span class="hint">${icon('clock')} يبدأ بعد ${fmtUntil(l.startsAt - Date.now())}</span>`;
  else if (l.recordingVideoId) action = `<a class="btn btn-ghost btn-sm" href="#/s/videos/${l.recordingVideoId}">${icon('play')} شاهد التسجيل</a>`;
  else action = '<span class="hint">لم يُرفع التسجيل بعد</span>';
  return `<div class="live-row status-${l.status}">
    <span class="date-box"><b>${fmtDay(l.startsAt)}</b><span>${fmtMonth(l.startsAt)}</span></span>
    <div class="lr-body"><div class="lr-title"><b>${esc(l.title)}</b>${badge(LIVE_STATUS[l.status])}</div>
      <div class="meta"><span>${icon('book')} ${esc(l.courseTitle)}</span><span>${icon('clock')} ${fmtDateTime(l.startsAt)} · ${l.durationMinutes} دقيقة</span></div></div>
    <div class="row-actions">${action}</div>
  </div>`;
}

function traineeExamCard(e, showCourse = true) {
  const a = e.attempt;
  let side;
  if (!a) side = `<button class="btn btn-primary" data-start="${e.id}">ابدأ الاختبار</button>`;
  else if (a.status === 'in_progress') side = `<a class="btn btn-primary" href="#/s/attempts/${a.id}">${icon('clock')} أكمل الاختبار</a>`;
  else side = `<span class="pct ${pctClass(a.percent)}">${a.percent}%</span><a class="btn btn-ghost btn-sm" href="#/s/attempts/${a.id}">النتيجة وشرح الإجابات</a>`;
  return `<div class="exam-card">
    <span class="ex-ic">${icon('exam')}</span>
    <div class="grow"><b>${esc(e.title)}</b><div class="meta">${showCourse ? `<span>${icon('book')} ${esc(e.courseTitle)}</span>` : ''}<span>${icon('clock')} ${e.durationMinutes} دقيقة</span><span>${icon('question')} ${e.questionCount} سؤال</span>${a && a.status === 'submitted' ? `<span>${a.score} من ${a.total} صحيحة</span>` : ''}</div></div>
    <div class="side">${side}</div>
  </div>`;
}

function bindExamStart(root, exams) {
  $$('[data-start]', root).forEach((b) => b.addEventListener('click', async () => {
    const e = exams.find((x) => x.id === b.dataset.start);
    const ok = await confirmBox(`مدة الاختبار ${e.durationMinutes} دقيقة وعدد أسئلته ${e.questionCount}.\nيبدأ العدّاد فور البدء ولا يمكن إيقافه، وتُسلَّم إجاباتك تلقائيًا عند انتهاء الوقت.\nلديك محاولة واحدة.`, { title: `بدء «${e.title}»`, ok: 'ابدأ الآن', danger: false });
    if (!ok) return;
    try {
      const r = await api(`/s/exams/${e.id}/start`, 'POST');
      go(`/s/attempts/${r.attemptId}`);
    } catch (err) { toast(err.message, 'error'); }
  }));
}

route('/s/courses/:id', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'courses');
  const d = await api(`/s/courses/${ctx.params.id}`);
  if (ctx.stale()) return;
  const c = d.course;
  const p = d.progress;
  const head = `
    <a class="back" href="#/s">${icon('prev')} دوراتي</a>
    <div class="course-hero card">
      <span class="cover">${cover(c)}</span>
      <div class="ch-body"><h1>${esc(c.title)}</h1><span class="meta"><span>${icon('users')} ${esc(c.trainerName)}</span></span>
        <p class="muted">${esc(c.description || 'بدون وصف')}</p>
        ${d.enrolled
          ? `${barRow(p.percent)}<p class="detail">${progressDetail(p)}</p><div class="actions" style="max-width:340px">${resumeButton(c, d.resume)}</div>`
          : `<div class="actions"><button class="btn btn-primary" data-enroll="${c.id}">${icon('plus')} التحق بالدورة</button></div>`}
      </div>
    </div>`;
  const videos = `<section class="section" style="margin-top:8px"><div class="section-head"><h2>الفيديوهات</h2><span class="hint">${d.videos.length} مقطع</span></div>
    ${d.videos.length ? `<ol class="video-list">${d.videos.map((v) => traineeVideoRow(v, d.enrolled)).join('')}</ol>` : empty('video', 'لا توجد مقاطع بعد', 'لم يرفع المدرب أي مقطع في هذه الدورة حتى الآن.')}</section>`;
  let rest = '';
  if (d.enrolled) {
    const lives = d.lives.filter((l) => l.status !== 'ended' || l.recordingVideoId);
    if (lives.length) rest += `<section class="section"><div class="section-head"><h2>البث المباشر</h2></div><div class="list">${lives.map(traineeLiveRow).join('')}</div></section>`;
    rest += `<section class="section"><div class="section-head"><h2>الاختبارات</h2></div>${d.exams.length ? `<div class="list">${d.exams.map((e) => traineeExamCard(e, false)).join('')}</div>` : empty('exam', 'لا توجد اختبارات منشورة', 'ستظهر هنا اختبارات الدورة عندما ينشرها المدرب.')}</section>`;
  }
  main.innerHTML = head + videos + rest;
  $$('[data-enroll]', main).forEach((b) => b.addEventListener('click', () => enroll(b, b.dataset.enroll)));
  if (d.enrolled) bindExamStart(main, d.exams);
});

route('/s/videos', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'videos');
  const d = await api('/s/videos');
  if (ctx.stale()) return;
  if (!d.courses.length) {
    main.innerHTML = pageHead('الفيديوهات') + empty('video', 'لا توجد فيديوهات بعد', 'التحق بدورة لتظهر فيديوهاتها هنا بالترتيب.', '<a class="btn btn-primary" href="#/s">تصفّح الدورات</a>');
    return;
  }
  main.innerHTML = pageHead('الفيديوهات', 'فيديوهات دوراتك بالترتيب، مع علامة على ما شاهدته. تسجيلات البث تظهر هنا أيضًا.')
    + d.courses.map((c) => {
      const watched = c.videos.filter((v) => v.watched).length;
      return `<section class="section" style="margin-top:0;margin-bottom:28px">
        <div class="section-head"><h2><a href="#/s/courses/${c.id}">${esc(c.title)}</a></h2><span class="hint">شاهدت ${watched} من ${c.videos.length}</span></div>
        ${c.videos.length ? `<ol class="video-list">${c.videos.map((v) => traineeVideoRow(v, true)).join('')}</ol>` : empty('video', 'لا توجد مقاطع في هذه الدورة بعد', 'سيظهر هنا ما يرفعه المدرب.')}
      </section>`;
    }).join('');
});

route('/s/videos/:id', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'videos');
  const d = await api(`/s/videos/${ctx.params.id}`);
  if (ctx.stale()) return;
  const v = d.video;
  const watchState = (done) => (done
    ? `<span class="watch-state done">${icon('check')} تمت مشاهدة هذا المقطع</span>`
    : `<span class="watch-state">${icon('clock')} يُحتسب المقطع مُشاهَدًا عند إكمال 80% منه تقريبًا</span>`);
  main.innerHTML = `
    <div class="player-layout">
      <div>
        <a class="back" href="#/s/courses/${d.course.id}">${icon('prev')} ${esc(d.course.title)}</a>
        <div class="player"><video id="vid" src="${esc(v.url)}" controls playsinline preload="metadata"></video></div>
        <div class="v-info">
          <div class="lr-title"><h1>${esc(v.title)}</h1>${v.source === 'live' ? badge(SOURCE.live) : ''}</div>
          <div id="wstate">${watchState(v.watched)}</div>
          ${v.description ? `<p>${esc(v.description)}</p>` : ''}
          <div class="pn">${d.prev ? `<a class="btn btn-ghost" href="#/s/videos/${d.prev.id}">${icon('prev')} السابق</a>` : '<span></span>'}${d.next ? `<a class="btn btn-primary" href="#/s/videos/${d.next.id}">التالي ${icon('next')}</a>` : ''}</div>
        </div>
      </div>
      <aside class="playlist card">
        <h3>محتوى الدورة</h3>
        <div id="pbar">${barRow(d.progress.percent)}</div>
        <ol>${d.playlist.map((p) => `<li><a href="#/s/videos/${p.id}" class="${p.id === v.id ? 'current' : ''} ${p.watched ? 'watched' : ''}"><span class="order">${p.watched ? icon('check') : p.order}</span><span>${esc(p.title)}</span></a></li>`).join('')}</ol>
      </aside>
    </div>`;

  // Watch tracking: count only seconds actually played, report periodically.
  const vid = $('#vid', main);
  let last = null;
  let pending = 0;
  let done = v.watched;
  vid.addEventListener('loadedmetadata', () => {
    if (v.position > 3 && v.position < vid.duration - 3) vid.currentTime = v.position;
  });
  vid.addEventListener('seeking', () => { last = null; });
  vid.addEventListener('timeupdate', () => {
    const t = vid.currentTime;
    if (last != null && !vid.seeking) {
      const dt = t - last;
      if (dt > 0 && dt < 1.5) pending += dt;
    }
    last = t;
  });
  const payload = () => {
    const played = pending;
    pending = 0;
    return { position: vid.currentTime, duration: vid.duration, played };
  };
  const report = async () => {
    if (!vid.duration || !isFinite(vid.duration)) return;
    const body = payload();
    try {
      const r = await api(`/s/videos/${v.id}/progress`, 'POST', body);
      if (ctx.stale()) return;
      $('#pbar', main).innerHTML = barRow(r.progress.percent);
      if (r.justCompleted && !done) {
        done = true;
        $('#wstate', main).innerHTML = watchState(true);
        const item = $(`.playlist a[href="#/s/videos/${v.id}"]`, main);
        if (item) { item.classList.add('watched'); $('.order', item).innerHTML = icon('check'); }
        toast('أحسنت! تم احتساب هذا المقطع كمُشاهَد', 'success');
      }
    } catch (e) {
      pending += body.played;
    }
  };
  const beacon = () => {
    if (!vid.duration || !isFinite(vid.duration)) return;
    fetch(`/api/s/videos/${v.id}/progress`, {
      method: 'POST', keepalive: true,
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'itqan' },
      body: JSON.stringify(payload()),
    }).catch(() => {});
  };
  const timer = setInterval(() => { if (!vid.paused) report(); }, 10000);
  vid.addEventListener('pause', report);
  vid.addEventListener('ended', report);
  window.addEventListener('pagehide', beacon);
  onLeave(() => {
    clearInterval(timer);
    window.removeEventListener('pagehide', beacon);
    vid.pause();
    beacon();
  });
});

route('/s/lives', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'lives');
  const draw = (d) => {
    const g = groupLives(d.lives);
    const section = (title, list) => (list.length ? `<h3 class="group-title">${title}</h3><div class="list">${list.map(traineeLiveRow).join('')}</div>` : '');
    main.innerHTML = pageHead('البث المباشر', 'مواعيد البثوث في دوراتك. زر «انضم الآن» يظهر وقت البث.')
      + (d.lives.length
        ? section('مباشر الآن', g.live) + section('البثوث القادمة', g.upcoming) + section('البثوث المنتهية', g.ended)
        : empty('live', 'لا توجد بثوث مباشرة', 'سيظهر هنا موعد أي بث يجدوله مدربو دوراتك.'));
  };
  const d = await api('/s/lives');
  if (ctx.stale()) return;
  draw(d);
  const timer = setInterval(async () => {
    if (document.hidden) return;
    try { const fresh = await api('/s/lives'); if (!ctx.stale()) draw(fresh); } catch (e) { /* keep last view */ }
  }, 30000);
  onLeave(() => clearInterval(timer));
});

route('/s/exams', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'exams');
  const d = await api('/s/exams');
  if (ctx.stale()) return;
  main.innerHTML = pageHead('الاختبارات', 'الاختبارات المتاحة في دوراتك. حلّها بالوقت المحدد ثم اطّلع على نتيجتك وشرح الإجابات.')
    + (d.exams.length
      ? `<div class="list">${d.exams.map((e) => traineeExamCard(e)).join('')}</div>`
      : empty('exam', 'لا توجد اختبارات متاحة', 'تظهر هنا الاختبارات المنشورة في الدورات التي التحقت بها.', '<a class="btn btn-ghost" href="#/s">دوراتي</a>'));
  bindExamStart(main, d.exams);
});

route('/s/attempts/:id', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'exams');
  const d = await api(`/s/attempts/${ctx.params.id}`);
  if (ctx.stale()) return;
  if (d.submitted) renderResult(main, d);
  else renderTake(main, d, ctx);
});

function renderTake(main, d, ctx) {
  const offset = d.serverNow - Date.now();
  const answers = Object.assign({}, d.answers);
  const total = d.questions.length;
  main.innerHTML = `
    <div class="exam-bar"><div><b>${esc(d.exam.title)}</b><small class="muted">${esc(d.exam.courseTitle)} · ${total} سؤال</small></div><div class="timer" id="timer">${icon('clock')}<span>--:--</span></div></div>
    <form id="examForm" novalidate>
      ${d.questions.map((q, i) => `<fieldset class="q-card"><legend><span class="q-num">${i + 1}</span><span class="q-text">${esc(q.text)}</span></legend>
        <div class="choices">${q.options.map((o, j) => `<label class="choice"><input type="radio" name="${q.id}" value="${j}" ${answers[q.id] === j ? 'checked' : ''}><span>${esc(o)}</span></label>`).join('')}</div></fieldset>`).join('')}
      <div class="exam-submit"><span class="hint" id="answered"></span><button class="btn btn-primary btn-lg" type="submit">تسليم الإجابات</button></div>
    </form>`;
  const form = $('#examForm', main);
  const answeredEl = $('#answered', main);
  const count = () => Object.keys(answers).length;
  const updateAnswered = () => { answeredEl.textContent = `أجبت عن ${count()} من ${total} أسئلة`; };
  updateAnswered();

  let saveTimer = null;
  let finished = false;
  const save = () => api(`/s/attempts/${d.attemptId}/answers`, 'POST', { answers }).catch(() => {});
  form.addEventListener('change', (e) => {
    if (e.target.type !== 'radio') return;
    answers[e.target.name] = Number(e.target.value);
    updateAnswered();
    clearTimeout(saveTimer);
    saveTimer = setTimeout(save, 600);
  });

  const timerEl = $('#timer', main);
  const tick = () => {
    const left = d.deadline - (Date.now() + offset);
    $('span', timerEl).textContent = fmtClock(left / 1000);
    timerEl.classList.toggle('warn', left <= 60000);
    if (left <= 0) submit(true);
  };
  const interval = setInterval(tick, 1000);
  tick();

  async function submit(auto) {
    if (finished) return;
    if (!auto) {
      const missing = total - count();
      const ok = await confirmBox(missing ? `لم تُجب عن ${missing} من الأسئلة. هل تريد التسليم الآن؟` : 'هل تريد تسليم إجاباتك الآن؟', { title: 'تسليم الاختبار', ok: 'سلّم الإجابات', danger: false });
      if (!ok || finished) return;
    }
    finished = true;
    clearInterval(interval);
    clearTimeout(saveTimer);
    try {
      const result = await api(`/s/attempts/${d.attemptId}/submit`, 'POST', { answers });
      if (ctx.stale()) return;
      toast(auto ? 'انتهى الوقت وتم تسليم إجاباتك تلقائيًا' : 'تم تسليم الاختبار', 'success');
      window.scrollTo(0, 0);
      renderResult(main, result);
    } catch (err) {
      finished = false;
      toast(err.message, 'error');
    }
  }
  form.addEventListener('submit', (e) => { e.preventDefault(); submit(false); });
  onLeave(() => {
    clearInterval(interval);
    clearTimeout(saveTimer);
    if (!finished) {
      fetch(`/api/s/attempts/${d.attemptId}/answers`, {
        method: 'POST', keepalive: true,
        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'itqan' },
        body: JSON.stringify({ answers }),
      }).catch(() => {});
    }
  });
}

function renderResult(main, d) {
  const cls = pctClass(d.percent);
  main.innerHTML = `
    <a class="back" href="#/s/exams">${icon('prev')} الاختبارات</a>
    <div class="result-hero card">
      <div class="ring ${cls}" style="--p:${d.percent}"><b>${d.percent}%</b></div>
      <div><h1>${esc(d.exam.title)}</h1><p class="muted">${esc(d.exam.courseTitle)}</p>
        <p class="big">أجبت إجابة صحيحة عن <b>${d.score}</b> من <b>${d.total}</b> أسئلة</p></div>
    </div>
    <div class="section-head"><h2>مراجعة الإجابات</h2></div>
    ${d.questions.map((q, i) => `<article class="q-card ${q.isCorrect ? 'is-right' : 'is-wrong'}">
      <div class="q-head"><span class="q-num">${i + 1}</span><p class="q-text">${esc(q.text)}</p><span class="badge ${q.isCorrect ? 'badge-ok' : 'badge-bad'}">${q.isCorrect ? 'إجابة صحيحة' : q.chosen == null ? 'لم تُجب' : 'إجابة خاطئة'}</span></div>
      <ul class="q-options">${q.options.map((o, j) => `<li class="${j === q.correct ? 'correct' : ''} ${j === q.chosen && j !== q.correct ? 'wrong' : ''}">${j === q.correct ? icon('check') : j === q.chosen ? icon('x') : '<span class="dot"></span>'}<span>${esc(o)}${j === q.chosen ? ' <small>(إجابتك)</small>' : ''}</span></li>`).join('')}</ul>
      ${q.explanation ? `<div class="q-exp open"><b>شرح الإجابة: </b>${esc(q.explanation)}</div>` : ''}
    </article>`).join('')}`;
}

route('/s/progress', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'progress');
  const d = await api('/s/progress');
  if (ctx.stale()) return;
  if (!d.courses.length) {
    main.innerHTML = pageHead('تقدّمي') + empty('chart', 'لا يوجد تقدّم لعرضه بعد', 'التحق بدورة وابدأ المشاهدة وحل الاختبارات لتتابع إنجازك هنا.', '<a class="btn btn-primary" href="#/s">تصفّح الدورات</a>');
    return;
  }
  main.innerHTML = pageHead('تقدّمي', 'نسبة التقدّم = (الفيديوهات المشاهدة + الاختبارات المكتملة) ÷ (إجمالي الفيديوهات + إجمالي الاختبارات)')
    + `<div class="list">${d.courses.map(({ course: c, progress: p, exams }) => `
      <section class="card progress-card">
        <div class="ring sm ${pctClass(p.percent)}" style="--p:${p.percent}"><b>${p.percent}%</b></div>
        <div class="pc-body"><h3><a href="#/s/courses/${c.id}">${esc(c.title)}</a></h3>${bar(p.percent)}
          <div class="pc-stats"><span>الفيديوهات المشاهدة: <b>${p.videosWatched} من ${p.videosTotal}</b></span><span>الاختبارات المكتملة: <b>${p.examsDone} من ${p.examsTotal}</b></span></div></div>
        ${exams.length ? `<div class="pc-exams">${exams.map((e) => (e.percent != null
          ? `<a class="score" href="#/s/attempts/${e.attemptId}">${esc(e.title)}: ${e.percent}%</a>`
          : `<span class="score pending">${esc(e.title)}: ${e.status === 'in_progress' ? 'جارٍ الحل' : 'لم يُحل بعد'}</span>`)).join('')}</div>` : ''}
      </section>`).join('')}</div>`;
});

route('/s/banks', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'banks');
  const d = await api('/s/banks');
  if (ctx.stale()) return;
  const head = pageHead('بنك الأسئلة', 'أسئلة المدربين في كل دورة التحقت بها، راجعها واستعد للاختبارات.');
  if (!d.courses.length) {
    main.innerHTML = head + empty('bank', 'لم تلتحق بأي دورة بعد', 'التحق بدورة لتظهر لك بنوك أسئلتها هنا.', '<a class="btn btn-primary" href="#/s">تصفّح الدورات</a>');
    return;
  }
  main.innerHTML = head + d.courses.map((c) => `<section class="section" style="margin-top:0;margin-bottom:28px">
      <div class="section-head"><h2><a href="#/s/courses/${c.id}">${esc(c.title)}</a></h2><span class="hint">${c.banks.length} بنك</span></div>
      ${c.banks.length ? `<div class="course-grid">${c.banks.map((b) => `
        <a class="bank-card" href="#/s/banks/${b.id}">
          <span class="bank-ic">${icon('bank')}</span>
          <h3>${esc(b.name)}</h3>
          <div class="bank-count"><b>${b.questionCount}</b><span>سؤال</span></div>
        </a>`).join('')}</div>`
      : empty('bank', 'لا توجد أسئلة لك في هذه الدورة بعد', 'ستظهر هنا البنوك التي يضيفها مدرب الدورة لك.')}
    </section>`).join('');
});

route('/s/banks/:id', { role: 'trainee' }, async (ctx) => {
  const main = startPage('trainee', 'banks');
  const d = await api(`/s/banks/${ctx.params.id}`);
  if (ctx.stale()) return;
  const b = d.bank;
  main.innerHTML = `
    <a class="back" href="#/s/banks">${icon('prev')} بنك الأسئلة</a>
    ${pageHead(esc(b.name), `${esc(b.courseTitle)} · ${b.questionCount} سؤال`)}
    ${d.questions.length ? `<div class="toolbar">
      <label class="search">${icon('search')}<input type="search" id="fq" placeholder="ابحث في نص الأسئلة" aria-label="بحث"></label>
      <select id="ft" aria-label="النوع"><option value="">كل الأنواع</option><option value="mcq">${QTYPE.mcq}</option><option value="tf">${QTYPE.tf}</option></select>
      <select id="fd" aria-label="الصعوبة"><option value="">كل المستويات</option>${Object.entries(DIFF).map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}</select>
    </div>
    <p class="practice-score" id="score"></p>` : ''}
    <div id="qlist"></div>`;
  const list = $('#qlist', main);
  const picked = {};   // questionId -> chosen option index
  const results = {};  // questionId -> { chosen, isCorrect, correct, explanation }
  const updateScore = () => {
    const done = Object.values(results);
    const el = $('#score', main);
    if (el) el.textContent = done.length ? `أجبت عن ${done.length} من ${d.questions.length} سؤال، منها ${done.filter((x) => x.isCorrect).length} صحيحة` : 'اختر إجابتك لكل سؤال ثم اضغط «تحقق» لتعرف النتيجة وشرحها.';
  };
  const card = (q, num) => {
    const res = results[q.id];
    const options = q.options.map((o, j) => {
      let cls = '';
      if (res && j === res.correct) cls = 'correct';
      else if (res && j === res.chosen) cls = 'wrong';
      return `<label class="choice ${cls}"><input type="radio" name="p-${q.id}" value="${j}" ${(res ? res.chosen : picked[q.id]) === j ? 'checked' : ''} ${res ? 'disabled' : ''}>${res && j === res.correct ? icon('check') : res && j === res.chosen ? icon('x') : ''}<span>${esc(o)}</span></label>`;
    }).join('');
    const footer = res
      ? `<div class="practice-result ${res.isCorrect ? 'ok' : 'bad'}"><b>${res.isCorrect ? 'إجابة صحيحة، أحسنت!' : 'إجابة خاطئة'}</b>${res.explanation ? `<p>${esc(res.explanation)}</p>` : ''}</div>`
      : `<button type="button" class="btn btn-primary btn-sm" data-check="${q.id}">تحقق من الإجابة</button>`;
    return `<article class="q-card practice">
      <div class="q-head"><span class="q-num">${num}</span><p class="q-text">${esc(q.text)}</p></div>
      <div class="q-badges"><span class="badge">${QTYPE[q.type]}</span><span class="badge badge-${q.difficulty}">${DIFF[q.difficulty]}</span></div>
      <div class="choices">${options}</div>
      <div class="practice-foot">${footer}</div>
    </article>`;
  };
  const draw = () => {
    const q = (($('#fq', main) || {}).value || '').trim().toLowerCase();
    const t = ($('#ft', main) || {}).value || '';
    const df = ($('#fd', main) || {}).value || '';
    const items = d.questions.filter((x) => (!t || x.type === t) && (!df || x.difficulty === df) && (!q || x.text.toLowerCase().includes(q)));
    if (!d.questions.length) list.innerHTML = empty('question', 'هذا البنك فارغ', 'لم يضف المدرب أسئلة إلى هذا البنك بعد.');
    else if (!items.length) list.innerHTML = empty('search', 'لا توجد أسئلة مطابقة', 'جرّب تغيير كلمات البحث أو الفلاتر.');
    else list.innerHTML = items.map((x, i) => card(x, i + 1)).join('');
    updateScore();
  };
  list.addEventListener('change', (e) => {
    if (e.target.type === 'radio') picked[e.target.name.slice(2)] = Number(e.target.value);
  });
  list.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-check]');
    if (!btn) return;
    const id = btn.dataset.check;
    if (picked[id] == null) { toast('اختر إجابة أولًا', 'error'); return; }
    btn.classList.add('busy');
    try {
      const r = await api(`/s/questions/${id}/check`, 'POST', { answer: picked[id] });
      results[id] = { chosen: picked[id], isCorrect: r.isCorrect, correct: r.correct, explanation: r.explanation };
      draw();
    } catch (err) {
      toast(err.message, 'error');
      btn.classList.remove('busy');
    }
  });
  ['#fq', '#ft', '#fd'].forEach((s) => { const el = $(s, main); if (el) el.addEventListener('input', draw); });
  draw();
});

/* ---------- boot ---------- */
(async function init() {
  try {
    state.me = (await api('/me')).user;
  } catch (e) {
    state.me = null;
  }
  window.addEventListener('hashchange', router);
  router();
})();

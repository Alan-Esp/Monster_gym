(function () {
  'use strict';

  const D = window.GYM_DATA;
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  // money recibe pesos; lo que viene de la API (centavos) se convierte con pesos() al mostrar
  const money = n => '$' + n.toLocaleString('es-MX', { minimumFractionDigits: Number.isInteger(n) ? 0 : 2, maximumFractionDigits: 2 });
  const pesos = cents => cents / 100;
  const initials = name => name.split(' ').map(w => w[0]).slice(0, 2).join('');
  const STATUS = { a: 'Activa', p: 'Por vencer', v: 'Vencida' };
  const STATUS_K = { activa: 'a', por_vencer: 'p', vencida: 'v' };
  const METHOD_LABEL = { efectivo: 'Efectivo', tarjeta: 'Tarjeta', transferencia: 'Transferencia' };
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const fmtPhone = p => p ? p.replace(/^(\d{2})(\d{4})(\d{4})$/, '$1 $2 $3') : '—';
  const MONTHS = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
  // '2026-10-28' → '28 oct' (con año corto si no es el actual: '14 mar 27')
  function fmtDay(iso) {
    if (!iso) return '—';
    const [y, m, d] = iso.split('-').map(Number);
    return d + ' ' + MONTHS[m - 1] + (y !== new Date().getFullYear() ? ' ' + String(y).slice(2) : '');
  }

  /* ---------- Navegación ---------- */
  const NAV_PARENT = { nuevo: 'socios', cierre: 'caja' };
  function go(screen) {
    $$('.screen').forEach(s => s.classList.toggle('is-active', s.dataset.screen === screen));
    const navKey = NAV_PARENT[screen] || screen;
    $$('#nav .pill').forEach(p => p.classList.toggle('is-active', p.dataset.go === navKey));
    if (screen === 'cierre') renderClose();
    if (screen === 'inicio') loadHome();
    if (screen === 'acceso') loadAccess();
    if (screen === 'visitas') loadVisits();
    if (screen === 'socios') loadMembers();
    if (screen === 'nuevo') resetNewMember();
    if (screen === 'caja') loadCaja();
    window.scrollTo(0, 0);
  }

  /* ---------- Login y turno ---------- */
  function showGate(name) {
    $$('.gate').forEach(g => g.classList.toggle('is-active', g.dataset.gate === name));
    $('#app').hidden = !!name;
    window.scrollTo(0, 0);
  }
  const hhmm = d => d.toTimeString().slice(0, 5);
  const ROLE_LABEL = { administrador: 'Administrador', recepcion: 'Recepción', entrenador: 'Entrenador' };
  const canOpenShift = me => me.role === 'administrador' || me.role === 'recepcion';

  let currentUser = null;

  /* Barra de usuario: nombre, iniciales, rol y turno (datos de /api/me) */
  function renderUser(me) {
    currentUser = me;
    const first = me.name.split(' ')[0];
    const shiftAt = me.shift ? me.shift.openedAt.slice(11, 16) : null;
    const label = [me.name, ROLE_LABEL[me.role] || me.role, shiftAt ? 'Turno desde ' + shiftAt : 'Sin turno'].join(' · ');
    $$('[data-user-initials]').forEach(el => { el.textContent = me.initials; });
    $$('[data-user-name]').forEach(el => { el.textContent = me.name; });
    $$('[data-user-first]').forEach(el => { el.textContent = first + '.'; });
    const avatar = $('#userInitials');
    avatar.title = label + ' — clic para salir';
    avatar.setAttribute('aria-label', label + '. Cerrar sesión');
    $('.shift-chip').style.display = me.shift ? '' : 'none';
    $('#shiftOpenedAt').textContent = shiftAt || '--:--';
    // La pantalla de cierre todavía usa data.js; se le pasa la hora real de apertura
    D.shift.openedAt = me.shift ? new Date(me.shift.openedAt) : null;
  }

  function enterApp(me) {
    renderUser(me);
    loadPlans();
    if (!me.shift && canOpenShift(me)) { showGate('apertura'); return; }
    showGate(null);
    go('inicio');
  }

  function showLoginError(message) {
    const pin = $('#loginPin');
    pin.setCustomValidity(message);
    pin.reportValidity();
  }
  $('#loginUser').addEventListener('input', () => $('#loginPin').setCustomValidity(''));
  $('#loginPin').addEventListener('input', e => e.target.setCustomValidity(''));

  $('#loginForm').addEventListener('submit', async e => {
    e.preventDefault();
    const user = $('#loginUser').value.trim(), pin = $('#loginPin').value;
    if (!user || !pin) { showLoginError('Escribe tu usuario y tu PIN o contraseña.'); return; }
    const btn = $('#loginForm [type="submit"]');
    btn.disabled = true;
    try {
      const me = await API.login(user, pin);
      $('#loginPin').value = '';
      enterApp(me);
    } catch (err) {
      showLoginError(err.message);
    } finally {
      btn.disabled = false;
    }
  });
  $('[data-login]').addEventListener('click', () =>
    showLoginError('El acceso con huella estará disponible cuando se conecte el lector.'));

  async function logout() {
    try { await API.logout(); } catch (err) { /* la sesión ya no existe */ }
    $('#loginForm').reset();
    showGate('login');
  }
  $$('[data-logout]').forEach(el => el.addEventListener('click', () => {
    if (el.id === 'userInitials' && !confirm('¿Cerrar sesión?')) return;
    logout();
  }));
  $('#userInitials').addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') e.target.click(); });
  API.onUnauthorized(() => showGate('login'));

  $('#openingCash').addEventListener('input', e => { e.target.value = e.target.value.replace(/[^0-9]/g, ''); });
  $('#openShift').addEventListener('click', async e => {
    const pesos = parseInt($('#openingCash').value, 10) || 0;
    e.target.disabled = true;
    try {
      await API.shifts.open(pesos * 100);
      D.shift.openingCash = pesos;
      enterApp(await API.me());
    } catch (err) {
      if (err.status !== 401) alert(err.message);
    } finally {
      e.target.disabled = false;
    }
  });
  // El cierre real se conecta en otra fase; por ahora termina la sesión para no dejarla abierta
  $('#closeShift').addEventListener('click', () => { D.shift.openedAt = null; logout(); });

  /* ---------- Cierre de turno ---------- */
  const BILLS = [1000, 500, 200, 100, 50, 20], COINS = [10, 5, 2, 1];
  function renderDenoms() {
    const row = v => `
      <div class="denom">
        <span class="denom__face">$${v}</span>
        <input class="input denom__qty" inputmode="numeric" data-denom="${v}" value="${D.cashCount[v] || 0}">
        <span class="denom__sub" data-sub="${v}"></span>
      </div>`;
    $('#denoms').innerHTML =
      '<span class="denoms__group">BILLETES</span>' + BILLS.map(row).join('') +
      '<span class="denoms__group">MONEDAS</span>' + COINS.map(row).join('');
    $('#denoms').addEventListener('input', e => {
      const inp = e.target.closest('[data-denom]');
      if (!inp) return;
      inp.value = inp.value.replace(/[^0-9]/g, '');
      D.cashCount[inp.dataset.denom] = parseInt(inp.value, 10) || 0;
      renderClose();
    });
  }
  function renderClose() {
    const sum = (list, m) => list.filter(p => p.method === m).reduce((a, p) => a + p.amount, 0);
    const memCash = sum(D.payments, 'Efectivo');
    const expected = D.shift.openingCash + memCash + D.shift.refriCash;
    let counted = 0;
    [...BILLS, ...COINS].forEach(v => {
      const sub = v * (D.cashCount[v] || 0);
      counted += sub;
      const el = $(`[data-sub="${v}"]`);
      if (el) el.textContent = money(sub);
    });
    const diff = counted - expected;
    const opened = D.shift.openedAt || new Date();
    const mins = Math.max(0, Math.round((Date.now() - opened.getTime()) / 60000));

    $$('[data-opened]').forEach(el => { el.textContent = hhmm(opened); });
    $('#shiftDuration').textContent = Math.floor(mins / 60) + ':' + String(mins % 60).padStart(2, '0') + ' h';
    $('#closePayCount').textContent = D.payments.length;
    $('#countedTotal').textContent = money(counted);
    $('#closeOpening').textContent = money(D.shift.openingCash);
    $('#closeMemCash').textContent = money(memCash);
    $('#closeRefriCash').textContent = money(D.shift.refriCash);
    $('#closeExpected').textContent = money(expected);
    $('#closeCounted').textContent = money(counted);
    $('#closeCard').textContent = money(sum(D.payments, 'Tarjeta') + D.shift.refriCard);
    $('#closeTransfer').textContent = money(sum(D.payments, 'Transferencia'));

    const state = diff < 0 ? 'is-short' : diff > 0 ? 'is-over' : '';
    const d = $('#closeDiff'), n = $('#closeDiffNote');
    d.className = 'diff ' + state; n.className = 'diff-note ' + state;
    d.textContent = (diff > 0 ? '+' : diff < 0 ? '−' : '') + money(Math.abs(diff));
    n.textContent = diff < 0 ? 'Falta efectivo' : diff > 0 ? 'Sobra efectivo' : 'Cuadra';
  }
  document.addEventListener('click', e => {
    const el = e.target.closest('[data-go]');
    if (!el) return;
    e.preventDefault();
    if (el.dataset.filter) memberFilter = el.dataset.filter;
    if (el.dataset.memberId) cajaMember = shownMembers.get(Number(el.dataset.memberId)) || null;
    go(el.dataset.go);
  });

  /* ---------- Reloj y fecha ---------- */
  function tick() {
    const d = new Date();
    const h = d.getHours();
    const t = d.toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit', hour12: true }).split(' ')[0];
    $('#clock').textContent = t;
    $('#ampm').textContent = h < 12 ? 'a.m.' : 'p.m.';
    $('#greeting').textContent = h < 12 ? 'Buenos días' : h < 19 ? 'Buenas tardes' : 'Buenas noches';
    const date = d.toLocaleDateString('es-MX', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).toUpperCase();
    $$('[data-date]').forEach(el => { el.textContent = date; });
    $$('[data-now]').forEach(el => { el.textContent = hhmm(d); });
  }

  /* ---------- Estado del lector ---------- */
  function renderFingerprint() {
    const on = D.fingerprintConnected;
    $('#fpStatus').classList.toggle('is-on', on);
    $('#fpLabel').textContent = on ? 'Huella conectada' : 'Sin huella';
    $('#scanSub').textContent = on ? 'Pide al socio que coloque su dedo en el lector' : 'Lector desconectado · usa el acceso manual';
    $('#toggleFp').classList.toggle('is-on', on);
    $('#cfgFpDesc').textContent = on ? 'Conectado' : 'Desconectado';
  }

  /* ---------- Totales ---------- */
  function totals() {
    const mem = D.payments.reduce((a, p) => a + p.amount, 0);
    const refri = D.products.reduce((a, p) => a + p.sold * p.price, 0);
    return { mem, refri, total: mem + refri };
  }
  /* t: { mem, refri, total } en pesos; solo actualiza los totales dentro de root (cada pantalla tiene su fuente) */
  function renderIncome(t, root) {
    $$('[data-inc="total"]', root).forEach(el => { el.textContent = money(t.total); });
    $$('[data-inc="mem"]', root).forEach(el => { el.textContent = money(t.mem); });
    $$('[data-inc="refri"]', root).forEach(el => { el.textContent = money(t.refri); });
    const split = $('#memSplit', root);
    if (split) split.style.width = (t.total ? Math.round(t.mem / t.total * 100) : 0) + '%';
  }

  /* ---------- Inicio ---------- */
  async function loadHome() {
    try {
      renderHome(await API.dashboard());
    } catch (err) { /* 401 ya muestra el login; otros errores: se reintenta en el siguiente ciclo */ }
  }
  // Actualiza Inicio cada minuto mientras esté visible
  setInterval(() => {
    if (!$('#app').hidden && $('[data-screen="inicio"]').classList.contains('is-active')) loadHome();
  }, 60000);

  /* d: respuesta de GET /api/dashboard */
  function renderHome(d) {
    const a = d.aforo, pct = a.capacity ? Math.round(a.inside / a.capacity * 100) : 0;
    $('#aforoIn').textContent = a.inside;
    $('#aforoCap').textContent = a.capacity;
    $('#aforoFill').style.width = Math.min(pct, 100) + '%';
    $('#aforoPct').textContent = pct + '% de capacidad';
    $('#aforoPeak').textContent = a.peakHour ? 'Pico ' + a.peakHour : 'Sin visitas hoy';
    $('#visitsToday').textContent = d.visitsToday;
    const delta = d.visitsDeltaVsYesterday;
    $('#visitsDelta').textContent = (delta > 0 ? '+' : delta < 0 ? '−' : '') + Math.abs(delta) + ' vs. ayer';
    $('#activeMembers').textContent = d.activeMembers;
    $('#countExpired').textContent = d.expiredCount;
    $('#countSoon').textContent = d.expiringCount;

    const max = Math.max(...d.visitsByHour, 1);
    $('#hourBars').innerHTML = d.visitsByHour.map((v, i) =>
      `<div class="hours__bar${i === d.currentHourIndex ? ' is-now' : ''}" style="height:${Math.round(v / max * 100)}%"></div>`).join('');
    const hourLabel = h => String(h).padStart(2, '0') + ':00';
    const last = d.firstHour + d.visitsByHour.length - 1;
    $$('.hours__axis span').forEach((el, i, all) => {
      el.textContent = hourLabel(Math.round(d.firstHour + (last - d.firstHour) * i / (all.length - 1)));
    });

    $('#topProducts').innerHTML = d.topProducts.length ? d.topProducts.map((p, i) => `
      <div class="top-list__row"><span class="top-list__n">0${i + 1}</span><span class="top-list__name">${esc(p.name)}</span><span class="top-list__units">${p.sold}</span></div>`).join('')
      : '<div class="top-list__row"><span class="top-list__name">Sin ventas hoy</span></div>';

    $('#recentGrid').innerHTML = d.recentVisits.length ? d.recentVisits.map(v => {
      const k = STATUS_K[v.status];
      return `
      <div class="recent">
        <div class="avatar avatar--42 avatar--ring t-${k}"><span style="color:#c9cec3">${esc(initials(v.name))}</span></div>
        <div class="col" style="gap:3px;min-width:0;flex:1">
          <span class="recent__name ellipsis">${esc(v.name)}</span>
          <span class="recent__status t-${k}">Membresía ${STATUS[k].toLowerCase()}</span>
        </div>
        <span class="recent__time">${v.time}</span>
      </div>`;
    }).join('') : '<div class="empty">Aún no hay entradas</div>';

    const inc = d.income;
    renderIncome({ mem: pesos(inc.membershipsCents), refri: pesos(inc.fridgeCents), total: pesos(inc.totalCents) },
      $('[data-screen="inicio"]'));
  }

  /* ---------- Carrusel ---------- */
  function initCarousel() {
    const root = $('#carousel');
    const slides = $$('.slide', root);
    const n = slides.length;
    const DURATION = 6000, STEP = 50;
    let index = 0, prev = 0, progress = 0, paused = false;

    $('#dots').innerHTML = slides.map((s, i) =>
      `<div class="dot" data-i="${i}"><div class="dot__track"><div class="dot__fill"></div></div><span class="ellipsis">${s.dataset.label}</span></div>`).join('');
    const dots = $$('.dot', root);

    const offset = (k, i) => { let d = ((k - i) % n + n) % n; if (d > n / 2) d -= n; return d; };

    function layout() {
      slides.forEach((s, k) => {
        const o = offset(k, index);
        const jump = Math.abs(o - offset(k, prev)) > 1;
        s.classList.toggle('no-anim', jump);
        s.style.left = `calc(19% + ${o} * (62% + 24px))`;
        s.style.opacity = o === 0 ? 1 : Math.abs(o) === 1 ? 0.4 : 0;
        s.style.transform = o === 0 ? 'scale(1)' : 'scale(0.92)';
        s.style.cursor = o === 0 ? 'default' : 'pointer';
      });
      dots.forEach((d, k) => {
        d.classList.toggle('is-active', k === index);
        $('.dot__fill', d).style.width = k < index ? '100%' : k === index ? (progress * 100) + '%' : '0%';
      });
    }
    function goTo(k) { prev = index; index = ((k % n) + n) % n; progress = 0; layout(); }

    slides.forEach((s, k) => s.addEventListener('click', e => {
      if (e.target.closest('[data-go]')) return;
      if (k !== index) goTo(k);
    }));
    dots.forEach((d, k) => d.addEventListener('click', () => goTo(k)));
    $('#prevSlide').addEventListener('click', () => goTo(index - 1));
    $('#nextSlide').addEventListener('click', () => goTo(index + 1));
    root.addEventListener('mouseenter', () => { paused = true; });
    root.addEventListener('mouseleave', () => { paused = false; });

    setInterval(() => {
      if (paused || !$('[data-screen="inicio"]').classList.contains('is-active')) return;
      progress += STEP / DURATION;
      if (progress >= 1) goTo(index + 1);
      else $('.dot__fill', dots[index]).style.width = (progress * 100) + '%';
    }, STEP);

    layout();
  }

  /* ---------- Acceso ---------- */
  const METHOD_ENTRY = { huella: 'Huella', manual: 'Manual' };

  /**
   * Busca el socio escrito en input (nombre, número o teléfono). Devuelve el socio si hay una sola
   * coincidencia o el número exacto; si no, muestra el aviso en el campo y devuelve null. (Acceso y Caja)
   */
  async function pickMember(input) {
    const q = input.value.trim();
    if (!q) return null;
    try {
      const data = await API.members.list({ q });
      const digits = q.replace(/\D/g, '');
      const exact = digits && digits.length === q.replace(/[#\s]/g, '').length
        ? data.items.find(m => m.number === digits.padStart(4, '0')) : null;
      if (exact || data.items.length === 1) return exact || data.items[0];
      input.setCustomValidity(data.total === 0 ? 'No se encontró ningún socio.'
        : `${data.total} socios coinciden: escribe más o usa el número de socio.`);
      input.reportValidity();
    } catch (err) {
      if (err.status !== 401) alert(err.message);
    }
    return null;
  }

  async function loadAccess() {
    try {
      renderAccess(await API.visits.day());
    } catch (err) {
      if (err.status !== 401) $('#lastEntries').innerHTML = `<div class="empty">${esc(err.message)}</div>`;
    }
  }

  /* day: respuesta de GET /api/visits (hoy) */
  function renderAccess(day) {
    $('#lastEntries').innerHTML = day.items.length ? day.items.slice(0, 5).map(v => `
      <div class="list-row"><span class="list-row__time">${v.time}</span><span class="ellipsis" style="flex:1">${esc(v.name)}</span><span style="font-size:12px;color:var(--dim)">${METHOD_ENTRY[v.method]}</span></div>`).join('')
      : '<div class="empty">Aún no hay entradas hoy</div>';
    const input = $('#accessSearch');
    const canRegister = currentUser && canOpenShift(currentUser);
    input.disabled = !canRegister || !day.manualAccess;
    input.placeholder = !canRegister ? 'Tu rol no registra entradas'
      : !day.manualAccess ? 'Acceso manual desactivado en Configuración' : 'Buscar socio…';
  }

  /* Resultado de la entrada en la tarjeta del lector: verde activa, ámbar por vencer, rojo vencida */
  let scanTimer;
  const SCAN_CLASSES = ['t-a', 't-p', 't-v'];
  function showEntry(v) {
    const k = STATUS_K[v.statusAtEntry], m = v.member;
    clearTimeout(scanTimer);
    [$('#rings'), $('#scanTitle')].forEach(el => { el.classList.remove(...SCAN_CLASSES); el.classList.add('t-' + k); });
    $('#scanTitle').textContent = m.fullName;
    const vigencia = !m.validUntil ? 'sin membresía pagada'
      : (k === 'v' ? 'venció el ' : 'vence el ') + fmtDay(m.validUntil);
    $('#scanSub').textContent = `Entrada registrada ${v.checkedInAt.slice(11, 16)} · Membresía ${STATUS[k].toLowerCase()} · ${vigencia}`;
    scanTimer = setTimeout(resetScan, 6000);
  }
  function resetScan() {
    [$('#rings'), $('#scanTitle')].forEach(el => el.classList.remove(...SCAN_CLASSES));
    $('#scanTitle').textContent = 'Esperando huella';
    renderFingerprint();
  }

  async function registerEntry(member, confirm) {
    try {
      const v = await API.visits.register({ memberId: member.id, method: 'manual', confirm: !!confirm });
      $('#accessSearch').value = '';
      showEntry(v);
      loadAccess();
    } catch (err) {
      // Socio vencido o doble registro: quien atiende confirma explícitamente
      if (!confirm && (err.code === 'SOCIO_VENCIDO' || err.code === 'DOBLE_REGISTRO')) {
        if (window.confirm(err.message)) registerEntry(member, true);
      } else if (err.status !== 401) {
        alert(err.message);
      }
    }
  }
  $('#accessSearch').addEventListener('input', e => e.target.setCustomValidity(''));
  $('#accessSearch').addEventListener('keydown', async e => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    const member = await pickMember(e.target);
    if (member) registerEntry(member, false);
  });

  /* ---------- Socios ---------- */
  // Pestaña → valor de status en la API (counts usa la misma clave)
  const FILTERS = { Todos: null, Activos: 'activa', 'Por vencer': 'por_vencer', Vencidos: 'vencida' };
  let memberFilter = 'Todos';
  let memberRequest = 0;
  const shownMembers = new Map(); // id → ítem mostrado, para preseleccionarlo en Caja
  function setMemberFilter(f) { memberFilter = f; loadMembers(); }

  async function loadMembers() {
    const req = ++memberRequest;
    try {
      const data = await API.members.list({ q: $('#memberSearch').value.trim(), status: FILTERS[memberFilter] });
      if (req === memberRequest) renderMembers(data); // ignora respuestas viejas
    } catch (err) {
      if (req === memberRequest && err.status !== 401) {
        $('#membersTable').innerHTML = `<div class="empty">${esc(err.message)}</div>`;
      }
    }
  }

  /* data: respuesta de GET /api/members */
  function renderMembers(data) {
    shownMembers.clear();
    data.items.forEach(m => shownMembers.set(m.id, m));
    $('#membersTotal').textContent = data.registered;
    $('#memberFilters').innerHTML = Object.keys(FILTERS).map(l => {
      const n = data.counts[FILTERS[l] || 'todos'];
      return `<button class="pill pill--tall${l === memberFilter ? ' is-active' : ''}" data-filter-pick="${l}">${l}<span class="pill__count">${n}</span></button>`;
    }).join('');
    $('#membersTable').innerHTML = data.items.length ? data.items.map(m => {
      const k = STATUS_K[m.status];
      return `
      <div class="table__row cols-socios">
        <span class="cell-person"><span class="avatar">${esc(initials(m.fullName))}</span><span class="ellipsis">${esc(m.fullName)}</span></span>
        <span class="cell-mono">#${esc(m.number)}</span>
        <span class="cell-soft">${fmtPhone(m.phone)}</span>
        <span class="cell-soft">${esc(m.plan || '—')}</span>
        <span class="cell-mono" style="color:var(--muted-2)">${fmtDay(m.validUntil)}</span>
        <span><span class="chip chip--${k}">${STATUS[k]}</span></span>
        <button class="btn-sm" data-go="caja" data-member-id="${m.id}">Cobrar</button>
      </div>`;
    }).join('') : '<div class="empty">No se encontraron socios</div>';
    $('#membersCount').textContent = data.total;
  }

  /* ---------- Visitas ---------- */
  async function loadVisits() {
    try {
      renderVisits(await API.visits.day());
    } catch (err) {
      if (err.status !== 401) $('#visitsTable').innerHTML = `<div class="empty">${esc(err.message)}</div>`;
    }
  }

  /* day: respuesta de GET /api/visits */
  function renderVisits(day) {
    $('#fpPct').textContent = day.fingerprintPct + '%';
    $$('[data-visits-today]').forEach(el => { el.textContent = day.total; });
    $('#insideNow').textContent = day.insideNow;
    $('#visitsWeek').textContent = day.weekCount;
    $('#visitsTable').innerHTML = day.items.length ? day.items.map(v => {
      const k = STATUS_K[v.status];
      return `
      <div class="table__row cols-visitas">
        <span class="mono" style="font-size:13px;color:var(--muted)">${v.time}</span>
        <span class="cell-person"><span class="avatar avatar--34">${esc(initials(v.name))}</span><span class="ellipsis">${esc(v.name)}</span></span>
        <span class="cell-mono">#${esc(v.number)}</span>
        <span class="cell-soft">${METHOD_ENTRY[v.method]}</span>
        <span><span class="chip chip--${k}">${STATUS[k]}</span></span>
      </div>`;
    }).join('') : '<div class="empty">Aún no hay entradas hoy</div>';
  }

  /* ---------- Punto de venta ---------- */
  function renderPos() {
    $('#products').innerHTML = D.products.map(p => {
      const out = p.stock === 0, low = !out && p.stock <= p.min;
      return `
      <div class="card product${out ? ' is-out' : low ? ' is-low' : ''}">
        <div class="product__head">
          <div class="col" style="gap:3px;min-width:0"><span class="product__name">${esc(p.name)}</span><span class="product__size">${p.size}</span></div>
          <span class="product__price">${money(p.price)}</span>
        </div>
        <div class="col" style="gap:8px">
          <div class="product__meta"><span>Stock <b class="product__stock">${p.stock}</b>/${p.cap}</span><span>Vendido <b>${p.sold}</b></span></div>
          <div class="product__bar"><i style="width:${Math.round(p.stock / p.cap * 100)}%"></i></div>
          <span class="product__tag">${out ? 'Agotado' : low ? 'Stock bajo' : ''}</span>
        </div>
        <button class="product__add"${out ? ' disabled' : ''}>${out ? 'Agotado' : 'Agregar'}</button>
      </div>`;
    }).join('');
    $('#soldUnits').textContent = D.products.reduce((a, p) => a + p.sold, 0);
    $('#stockUnits').textContent = D.products.reduce((a, p) => a + p.stock, 0);
    $('#lowCount').textContent = D.products.filter(p => p.stock <= p.min).length;
    $('#salesList').innerHTML = D.sales.map(s => `
      <div class="list-row">
        <span class="list-row__time">${s.time}</span>
        <div class="col" style="gap:2px;flex:1;min-width:0"><span class="ellipsis" style="font-size:13px">${esc(s.items)}</span><span style="font-size:11px;color:var(--dim)">${s.pay}</span></div>
        <span class="mono" style="font-size:13px">${money(s.total)}</span>
      </div>`).join('');
  }

  /* ---------- Planes (Nuevo socio / Caja) ---------- */
  let plans = []; // GET /api/plans
  const defaultPlanId = () => (plans.find(p => p.name === 'Mensual') || plans[0] || {}).id;

  async function loadPlans() {
    try {
      plans = await API.plans.list();
      $('#newPlans').innerHTML = planButtons(plans, defaultPlanId());
      $('#payPlans').innerHTML = planButtons(plans, cajaPlanId || defaultPlanId());
    } catch (err) { /* sin sesión o sin conexión: se reintenta al entrar a cada pantalla */ }
  }

  function planButtons(list, selectedId) {
    return list.map(p => `
      <button class="plan${p.id === selectedId ? ' is-active' : ''}" data-plan-id="${p.id}">
        <span class="plan__name">${esc(p.name)}</span><span class="plan__price">${money(pesos(p.priceCents))}</span>
      </button>`).join('');
  }

  /* ---------- Nuevo socio ---------- */
  function updateNewSubmit() {
    const withPlan = $('#withPlan').classList.contains('is-on');
    const btn = $('#newSubmit');
    btn.textContent = withPlan ? 'Registrar socio y cobrar' : 'Registrar socio';
    btn.disabled = !$('#newName').value.trim();
  }
  function resetNewMember() {
    ['#newName', '#newPhone'].forEach(s => { $(s).value = ''; $(s).setCustomValidity(''); });
    $('#withPlan').classList.add('is-on');
    $('#newPlans').style.display = '';
    if (plans.length) $('#newPlans').innerHTML = planButtons(plans, defaultPlanId());
    else loadPlans();
    updateNewSubmit();
  }
  $('#newName').addEventListener('input', e => { e.target.setCustomValidity(''); updateNewSubmit(); });
  $('#newPhone').addEventListener('input', e => e.target.setCustomValidity(''));
  $('#newSubmit').addEventListener('click', async e => {
    const btn = e.target;
    const withPlan = $('#withPlan').classList.contains('is-on');
    const planBtn = $('#newPlans .plan.is-active');
    if (withPlan && !planBtn) { alert('Elige el plan de la membresía.'); return; }
    btn.disabled = true;
    try {
      const res = await API.members.create({ fullName: $('#newName').value, phone: $('#newPhone').value.trim() || null });
      if (withPlan) {
        // Se abre Caja con el socio y el plan elegidos; ahí se escoge el método y se cobra
        cajaMember = res.member;
        cajaPlanId = Number(planBtn.dataset.planId);
        go('caja');
      } else {
        go('socios');
      }
    } catch (err) {
      const f = err.fields || {};
      if (f.fullName || f.phone) {
        const input = f.fullName ? $('#newName') : $('#newPhone');
        input.setCustomValidity(f.fullName || f.phone);
        input.reportValidity();
      } else if (err.status !== 401) {
        alert(err.message);
      }
    } finally {
      updateNewSubmit();
    }
  });

  /* ---------- Caja ---------- */
  let cajaMember = null;  // { id, number, fullName, status }
  let cajaPlanId = null;
  let cajaReady = false;  // hay turno abierto y el rol puede cobrar

  const memberLabel = m => `#${m.number} · ${m.fullName} · ${STATUS[STATUS_K[m.status]]}`;
  function selectMember(m) {
    cajaMember = m;
    const input = $('#payMember');
    input.value = memberLabel(m);
    input.setCustomValidity('');
    updatePayBtn();
  }
  function updatePayBtn() {
    const plan = plans.find(p => p.id === cajaPlanId);
    const btn = $('#payBtn');
    btn.textContent = 'Cobrar ' + (plan ? money(pesos(plan.priceCents)) : '');
    btn.disabled = !(cajaReady && cajaMember && plan);
  }

  async function loadCaja() {
    if (!plans.length) await loadPlans();
    if (!cajaPlanId) cajaPlanId = defaultPlanId();
    if (!currentUser || !canOpenShift(currentUser)) {
      cajaReady = false;
      renderCaja([], null, 'Tu rol no puede cobrar.');
    } else {
      try {
        const [payments, summary] = await Promise.all([API.payments.currentShift(), API.shifts.current()]);
        cajaReady = true;
        renderCaja(payments, summary);
      } catch (err) {
        cajaReady = false;
        if (err.status === 401) return;
        renderCaja([], null, err.code === 'SIN_TURNO' ? 'Abre un turno para cobrar.' : err.message);
      }
    }
    if (cajaMember) selectMember(cajaMember); else updatePayBtn();
  }

  /* payments: GET /api/payments?shift=current · summary: GET /api/shifts/current (null si no hay turno) */
  function renderCaja(payments, summary, notice) {
    const root = $('[data-screen="caja"]');
    $('#cajaHead').textContent = summary
      ? `TURNO · ${summary.employeeName.toUpperCase()} · DESDE ${summary.openedAt.slice(11, 16)}`
      : `${(currentUser ? currentUser.name : '').toUpperCase()} · SIN TURNO ABIERTO`;
    $('#payPlans').innerHTML = planButtons(plans, cajaPlanId);
    $('#paymentsList').innerHTML = notice ? `<div class="empty">${esc(notice)}</div>`
      : !payments.length ? '<div class="empty">Aún no hay pagos en este turno</div>'
      : payments.map(p => `
      <div class="list-row" style="padding:10px 0">
        <span class="list-row__time">${p.paidAt.slice(11, 16)}</span>
        <span class="ellipsis" style="flex:1">${esc(p.member.fullName)}</span>
        <span class="pay-row__plan">${esc(p.plan.name)}</span>
        <span class="pay-row__method">${p.voidedAt ? 'Cancelado' : METHOD_LABEL[p.method]}</span>
        <span class="pay-row__amount">${money(pesos(p.amountCents))}</span>
      </div>`).join('');

    const byPlan = summary ? summary.memberships.byPlan : [];
    const counts = plans.map(p => (byPlan.find(b => b.plan === p.name) || { count: 0 }).count);
    const top = Math.max(...counts, 0);
    $('#planCounts').innerHTML = plans.map((p, i) => `
      <div class="count"><span class="count__n"${top > 0 && counts[i] === top ? ' style="color:var(--lime)"' : ''}>${counts[i]}</span><span class="count__label">${esc(p.name)}</span></div>`).join('');
    $('#payCount').textContent = summary ? summary.memberships.count : 0;
    const mem = summary ? pesos(summary.memberships.totalCents) : 0, refri = summary ? pesos(summary.fridge.totalCents) : 0;
    renderIncome({ mem, refri, total: mem + refri }, root);
  }

  async function findCajaMember() {
    if (cajaMember) return;
    const member = await pickMember($('#payMember'));
    if (member) selectMember(member);
  }
  $('#payMember').addEventListener('input', e => {
    e.target.setCustomValidity('');
    if (cajaMember && e.target.value !== memberLabel(cajaMember)) { cajaMember = null; updatePayBtn(); }
  });
  $('#payMember').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); findCajaMember(); } });
  $('#payMember').addEventListener('change', findCajaMember);

  $('#payBtn').addEventListener('click', async e => {
    const btn = e.target;
    const method = ($('#payMethods .pill.is-active') || {}).dataset?.method || 'efectivo';
    btn.disabled = true;
    try {
      const r = await API.payments.create({ memberId: cajaMember.id, planId: cajaPlanId, method });
      alert(`Cobro registrado · Folio ${r.folio}\n${r.member.fullName} · ${r.plan.name}\n`
        + `Vigencia: ${fmtDay(r.startsOn)} al ${fmtDay(r.endsOn)}\n`
        + `Total: ${money(pesos(r.amountCents))} · ${METHOD_LABEL[r.method]}`);
      cajaMember = null;
      $('#payMember').value = '';
      // El método vuelve a Efectivo para no repetir por error el del cobro anterior
      $$('#payMethods .pill').forEach(b => b.classList.toggle('is-active', b.dataset.method === 'efectivo'));
      await loadCaja();
    } catch (err) {
      if (err.status !== 401) alert(err.message);
      updatePayBtn();
    }
  });

  /* ---------- Admin ---------- */
  function renderAdmin() {
    $('#employeesTable').innerHTML = D.employees.map(e => `
      <div class="table__row cols-emp">
        <span class="cell-person"><span class="avatar">${initials(e.name)}</span>${esc(e.name)}</span>
        <span class="cell-soft">${e.role}</span>
        <span class="cell-soft">${e.shift}</span>
        <span><span class="chip ${e.active ? 'chip--a' : 'chip--off'}">${e.active ? 'Activo' : 'Inactivo'}</span></span>
      </div>`).join('');
    $('#priceGrid').innerHTML = D.plans.map(p => `
      <div class="card price-card">
        <div class="section-head" style="align-items:center"><span style="font-size:15px;font-weight:700">${p.name}</span><span class="muted" style="font-size:12px">${p.days} días</span></div>
        <span class="stat__value" style="font-size:40px;line-height:0.9">${money(p.price)}</span>
        <button class="btn-sm">Editar precio</button>
      </div>`).join('');
    const m = D.monthlyIncome, last = m.length - 1;
    $('#months').innerHTML = m.map((x, i) => `
      <div class="month">
        <span class="month__v">$${x.value}k</span>
        <div class="month__bar${i === last - 1 ? ' is-best' : i === last ? ' is-current' : ''}" style="height:${Math.round(x.value / 150 * 100)}%"></div>
        <span class="month__label">${x.label}</span>
      </div>`).join('');
    const s = D.settings;
    $('#cfgName').value = s.gymName;
    $('#cfgCapacity').value = s.capacity;
    $('#cfgWarn').value = s.warnDays;
    $$('[data-toggle]').forEach(t => t.classList.toggle('is-on', !!s[t.dataset.toggle]));
  }

  /* ---------- Controles de selección (estado visual) ---------- */
  function bindSelections() {
    document.addEventListener('click', e => {
      const f = e.target.closest('[data-filter-pick]');
      if (f) { setMemberFilter(f.dataset.filterPick); return; }

      const plan = e.target.closest('.plan');
      if (plan) {
        $$('.plan', plan.parentElement).forEach(p => p.classList.toggle('is-active', p === plan));
        if (plan.parentElement.id === 'payPlans') {
          cajaPlanId = Number(plan.dataset.planId);
          updatePayBtn();
        }
        return;
      }

      const pill = e.target.closest('[data-select] .pill');
      if (pill) { $$('.pill', pill.parentElement).forEach(p => p.classList.toggle('is-active', p === pill)); return; }

      const tab = e.target.closest('#adminTabs .pill');
      if (tab) {
        $$('#adminTabs .pill').forEach(p => p.classList.toggle('is-active', p === tab));
        $$('.admin-panel').forEach(p => p.classList.toggle('is-active', p.dataset.panel === tab.dataset.tab));
        $('#adminTitle').textContent = tab.dataset.tab;
        return;
      }

      const tg = e.target.closest('[data-toggle]');
      if (tg) { D.settings[tg.dataset.toggle] = !D.settings[tg.dataset.toggle]; tg.classList.toggle('is-on'); return; }

      if (e.target.closest('#toggleFp')) { D.fingerprintConnected = !D.fingerprintConnected; renderFingerprint(); return; }

      if (e.target.closest('#withPlan')) {
        const on = $('#withPlan').classList.toggle('is-on');
        $('#newPlans').style.display = on ? '' : 'none';
        updateNewSubmit();
      }
    });
    let searchTimer;
    $('#memberSearch').addEventListener('input', () => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(loadMembers, 300);
    });
  }

  /* ---------- Inicio de la app ---------- */
  tick();
  setInterval(tick, 1000);
  renderFingerprint();
  // Refri y Cierre siguen con data.js hasta su fase; Inicio, Acceso, Visitas y Caja cargan de la API al abrirse
  [$('[data-screen="pos"]'), $('[data-screen="cierre"]')].forEach(root => renderIncome(totals(), root));
  initCarousel();
  renderPos();
  renderAdmin();
  renderDenoms();
  bindSelections();

  // Si ya hay sesión entra directo; si no, el 401 deja visible el login
  API.me().then(enterApp).catch(() => {});
})();

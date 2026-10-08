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
    if (screen === 'cierre') loadClose();
    if (screen === 'pos') loadPos();
    if (screen === 'admin') loadAdmin();
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
    $('#nav [data-go="admin"]').style.display = me.role === 'administrador' ? '' : 'none';
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
      enterApp(await API.me());
    } catch (err) {
      if (err.status !== 401) alert(err.message);
    } finally {
      e.target.disabled = false;
    }
  });
  /* ---------- Tickets ---------- */
  // El servidor imprime en segundo plano: el cobro o el cierre ya quedó guardado aunque el ticket falle.
  // print = { jobId, status } de la respuesta; el resultado se consulta con GET /api/print-jobs/{id}.
  const PRINT_DONE = ['entregado', 'en_cola_windows', 'fallido'];
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  // Consulta el trabajo hasta que termine (o falle un intento, si untilError) o pasen maxMs. Devuelve el último estado.
  async function waitPrint(print, maxMs, untilError) {
    if (!print || !print.jobId) return print;
    let job = print;
    for (const until = Date.now() + maxMs; Date.now() < until;) {
      await sleep(1500);
      try { job = await API.printer.job(print.jobId); } catch (err) { return job; }
      if (PRINT_DONE.includes(job.status) || (untilError && job.error)) return job;
    }
    return job;
  }

  // Renglón para el aviso del cobro o del cierre, según cómo quedó el ticket
  function printNote(job, what) {
    if (!job) return '';
    if (job.status === 'desactivada') return `\n\nTicket ${what}: la impresión está desactivada.`;
    if (job.status === 'entregado') return `\n\nTicket ${what}: enviado a la impresora.`;
    if (job.status === 'en_cola_windows') return `\n\nTicket ${what}: en la cola de Windows (si no sale, revisa que la impresora esté encendida).`;
    if (job.status === 'fallido') return `\n\nNo se imprimió el ticket ${what}: ${job.error}`;
    if (job.error) return `\n\nEl ticket ${what} aún no sale: ${job.error}`;
    return `\n\nTicket ${what}: en cola de impresión.`;
  }

  // Sin bloquear la pantalla: avisa solo si el ticket terminó en fallido
  async function watchPrint(print, what, where) {
    if (!print || !print.jobId) return;
    const job = await waitPrint(print, 180000, false);
    if (job && job.status === 'fallido') alert(`No se imprimió el ticket ${what}.\n${job.error}\n\nPuedes reimprimirlo desde ${where}.`);
  }

  // Reimpresión desde un botón: lo deshabilita mientras espera y avisa si falla
  async function reprint(btn, call, what) {
    const label = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Imprimiendo…';
    try {
      const job = await waitPrint(await call(), 90000, false);
      if (job && job.status === 'fallido') alert(`No se imprimió la copia ${what}.\n${job.error}`);
      else if (job && !PRINT_DONE.includes(job.status)) alert(`La copia ${what} sigue esperando a la impresora.`);
    } catch (err) {
      if (err.status !== 401) alert(err.message);
    } finally {
      btn.disabled = false;
      btn.textContent = label;
    }
  }

  /* ---------- Cierre de turno ---------- */
  const BILLS = [1000, 500, 200, 100, 50, 20], COINS = [10, 5, 2, 1];
  let cashCount = {};        // denominación (pesos) → piezas contadas
  let closeSummary = null;   // GET /api/shifts/current
  const signedMoney = p => (p > 0 ? '+' : p < 0 ? '−' : '') + money(Math.abs(p));
  const countedPesos = () => [...BILLS, ...COINS].reduce((a, v) => a + v * (cashCount[v] || 0), 0);

  function renderDenoms() {
    const row = v => `
      <div class="denom">
        <span class="denom__face">$${v}</span>
        <input class="input denom__qty" inputmode="numeric" data-denom="${v}" value="${cashCount[v] || 0}">
        <span class="denom__sub" data-sub="${v}"></span>
      </div>`;
    $('#denoms').innerHTML =
      '<span class="denoms__group">BILLETES</span>' + BILLS.map(row).join('') +
      '<span class="denoms__group">MONEDAS</span>' + COINS.map(row).join('');
  }
  $('#denoms').addEventListener('input', e => {
    const inp = e.target.closest('[data-denom]');
    if (!inp) return;
    inp.value = inp.value.replace(/[^0-9]/g, '');
    cashCount[inp.dataset.denom] = parseInt(inp.value, 10) || 0;
    renderClose(closeSummary);
  });

  async function loadClose() {
    closeSummary = null;
    try {
      closeSummary = await API.shifts.current();
    } catch (err) {
      if (err.status === 401) return;
      alert(err.code === 'SIN_TURNO' ? 'No tienes un turno abierto.' : err.message);
    }
    renderClose(closeSummary);
  }

  /* s: corte parcial de GET /api/shifts/current (null si no hay turno) */
  function renderClose(s) {
    let counted = 0;
    [...BILLS, ...COINS].forEach(v => {
      const sub = v * (cashCount[v] || 0);
      counted += sub;
      const el = $(`[data-sub="${v}"]`);
      if (el) el.textContent = money(sub);
    });
    const zero = { efectivo: 0, tarjeta: 0, transferencia: 0 };
    const memCash = s ? pesos(s.memberships.byMethod.efectivo) : 0;
    const refriCash = s ? pesos(s.fridge.byMethod.efectivo) : 0;
    const byMethod = s ? s.byMethod : zero;
    const expected = s ? pesos(s.expectedCashCents) : 0;
    const diff = counted - expected;
    const opened = s ? new Date(s.openedAt) : null;
    const mins = opened ? Math.max(0, Math.round((Date.now() - opened.getTime()) / 60000)) : 0;

    $('#closeHead').textContent = s
      ? `TURNO · ${s.employeeName.toUpperCase()} · DESDE ${s.openedAt.slice(11, 16)}` : 'SIN TURNO ABIERTO';
    $$('[data-opened]').forEach(el => { el.textContent = opened ? hhmm(opened) : '--:--'; });
    $('#shiftDuration').textContent = Math.floor(mins / 60) + ':' + String(mins % 60).padStart(2, '0') + ' h';
    $('#closePayCount').textContent = s ? s.memberships.count + s.fridge.salesCount : 0;
    const mem = s ? pesos(s.memberships.totalCents) : 0, refri = s ? pesos(s.fridge.totalCents) : 0;
    renderIncome({ mem, refri, total: mem + refri }, $('[data-screen="cierre"]'));
    $('#countedTotal').textContent = money(counted);
    $('#closeOpening').textContent = money(s ? pesos(s.openingCashCents) : 0);
    $('#closeMemCash').textContent = money(memCash);
    $('#closeRefriCash').textContent = money(refriCash);
    $('#closeExpected').textContent = money(expected);
    $('#closeCounted').textContent = money(counted);
    $('#closeCard').textContent = money(pesos(byMethod.tarjeta));
    $('#closeTransfer').textContent = money(pesos(byMethod.transferencia));

    const state = diff < 0 ? 'is-short' : diff > 0 ? 'is-over' : '';
    const d = $('#closeDiff'), n = $('#closeDiffNote');
    d.className = 'diff ' + state; n.className = 'diff-note ' + state;
    d.textContent = signedMoney(diff);
    n.textContent = diff < 0 ? 'Falta efectivo' : diff > 0 ? 'Sobra efectivo' : 'Cuadra';
    $('#closeShift').disabled = !s;
  }

  $('#closeShift').addEventListener('click', async e => {
    if (!closeSummary) return;
    const counted = countedPesos(), expected = pesos(closeSummary.expectedCashCents);
    if (!confirm(`¿Cerrar el turno?\nEsperado en caja: ${money(expected)}\nContado: ${money(counted)}\n`
      + `Diferencia: ${signedMoney(counted - expected)}\n\nUn turno cerrado ya no se modifica.`)) return;
    const btn = e.target, label = btn.textContent;
    btn.disabled = true;
    try {
      const r = await API.shifts.close(Math.round(counted * 100), $('#closeNotes').value.trim() || null);
      // El turno ya quedó cerrado; antes de salir se espera un poco al ticket para avisar si no salió
      btn.textContent = 'Imprimiendo ticket…';
      const job = await waitPrint(r.print, 25000, true);
      const failed = job && (job.status === 'fallido' || (job.error && !PRINT_DONE.includes(job.status)));
      alert(`Turno cerrado.\nEsperado: ${money(pesos(r.expectedCashCents))}\nContado: ${money(pesos(r.countedCashCents))}\n`
        + `Diferencia: ${signedMoney(pesos(r.differenceCents))}` + printNote(job, 'de cierre')
        + (failed ? '\nUn administrador puede reimprimirlo en Admin > Turnos.' : ''));
      cashCount = {};
      $('#closeNotes').value = '';
      renderDenoms();
      logout();
    } catch (err) {
      if (err.status !== 401) alert(err.message);
      btn.disabled = false;
    } finally {
      btn.textContent = label;
    }
  });
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
  let products = [];          // GET /api/products
  const cart = new Map();     // productId → piezas en la venta actual
  let posReady = false;       // turno abierto y rol que puede vender
  let restockMode = false;    // "Registrar entrada de stock": los botones de producto suman inventario
  let posSales = [], posNotice = null;  // última lista de ventas del turno (o aviso)

  async function loadPos() {
    const canSell = currentUser && canOpenShift(currentUser);
    let sales = [], notice = null;
    try {
      products = await API.products.list();
    } catch (err) {
      if (err.status !== 401) alert(err.message);
      return;
    }
    if (!canSell) {
      notice = 'Tu rol no puede vender.';
    } else {
      try {
        sales = await API.sales.currentShift();
      } catch (err) {
        if (err.status === 401) return;
        notice = err.code === 'SIN_TURNO' ? 'Abre un turno para vender.' : err.message;
      }
    }
    posReady = canSell && !notice;
    if (!canSell) restockMode = false;
    // Quita del carrito lo que ya no existe o ya no alcanza
    for (const [id, qty] of cart) {
      const p = products.find(x => x.id === id);
      if (!p || p.stock === 0) cart.delete(id); else if (qty > p.stock) cart.set(id, p.stock);
    }
    posSales = sales;
    posNotice = notice;
    renderPos(products, posSales, posNotice);
  }

  /* list: GET /api/products · sales: GET /api/sales?shift=current · notice: aviso en lugar de las ventas */
  function renderPos(list, sales, notice) {
    const canSell = currentUser && canOpenShift(currentUser);
    $('#products').innerHTML = list.map(p => {
      const out = p.outOfStock, low = !out && p.lowStock;
      const inCart = cart.get(p.id) || 0;
      const label = restockMode ? '+ Entrada' : out ? 'Agotado' : inCart >= p.stock ? 'Sin más stock' : inCart ? `Agregar (${inCart})` : 'Agregar';
      const disabled = restockMode ? false : out || inCart >= p.stock || !posReady;
      return `
      <div class="card product${out ? ' is-out' : low ? ' is-low' : ''}">
        <div class="product__head">
          <div class="col" style="gap:3px;min-width:0"><span class="product__name">${esc(p.name)}</span><span class="product__size">${esc(p.size || '')}</span></div>
          <span class="product__price">${money(pesos(p.priceCents))}</span>
        </div>
        <div class="col" style="gap:8px">
          <div class="product__meta"><span>Stock <b class="product__stock">${p.stock}</b>/${p.capacity}</span><span>Vendido <b>${p.soldToday}</b></span></div>
          <div class="product__bar"><i style="width:${Math.min(100, Math.round(p.stock / p.capacity * 100))}%"></i></div>
          <span class="product__tag">${out ? 'Agotado' : low ? 'Stock bajo' : ''}</span>
        </div>
        <button class="product__add" data-product-id="${p.id}"${disabled ? ' disabled' : ''}>${label}</button>
      </div>`;
    }).join('');
    $('#soldUnits').textContent = list.reduce((a, p) => a + p.soldToday, 0);
    $('#stockUnits').textContent = list.reduce((a, p) => a + p.stock, 0);
    $('#lowCount').textContent = list.filter(p => p.lowStock).length;
    const refri = pesos(list.reduce((a, p) => a + p.soldTodayCents, 0));
    renderIncome({ mem: 0, refri, total: refri }, $('[data-screen="pos"]'));

    const restockBtn = $('#restockMode');
    restockBtn.disabled = !canSell;
    restockBtn.textContent = restockMode ? 'Terminar entrada de stock' : 'Registrar entrada de stock';

    $('#salesList').innerHTML = notice ? `<div class="empty">${esc(notice)}</div>`
      : !sales.length ? '<div class="empty">Aún no hay ventas en este turno</div>'
      : sales.map(s => `
      <div class="list-row">
        <span class="list-row__time">${s.createdAt.slice(11, 16)}</span>
        <div class="col" style="gap:2px;flex:1;min-width:0"><span class="ellipsis" style="font-size:13px">${esc(s.items.map(i => i.qty + '× ' + i.name).join(', '))}</span><span style="font-size:11px;color:var(--dim)">${s.voidedAt ? 'Cancelada' : METHOD_LABEL[s.method]}</span></div>
        <span class="mono" style="font-size:13px">${money(pesos(s.totalCents))}</span>
      </div>`).join('');
    renderCart();
  }

  /* Venta actual: líneas dentro del recuadro de la venta */
  function renderCart() {
    let total = 0;
    const lines = [...cart].map(([id, qty]) => {
      const p = products.find(x => x.id === id);
      const sub = p.priceCents * qty;
      total += sub;
      return `
      <div class="list-row" style="gap:8px;padding:6px 12px;text-align:left">
        <span class="ellipsis" style="flex:1;font-size:13px">${esc(p.name)}</span>
        <button class="btn-round" data-cart-dec="${id}" aria-label="Quitar uno">−</button>
        <span class="mono" style="width:24px;text-align:center">${qty}</span>
        <button class="btn-round" data-cart-inc="${id}" aria-label="Agregar uno"${qty >= p.stock ? ' disabled' : ''}>+</button>
        <span class="mono" style="width:64px;text-align:right;font-size:13px">${money(pesos(sub))}</span>
      </div>`;
    });
    $('#cartLines').innerHTML = lines.length ? lines.join('') : 'Agrega productos del refri';
    $('#cartTotal').textContent = money(pesos(total));
    const pay = $('#cartPay');
    pay.textContent = 'Cobrar ' + money(pesos(total));
    pay.disabled = !posReady || !cart.size;
  }

  function changeCart(id, delta) {
    const p = products.find(x => x.id === id);
    if (!p) return;
    const qty = Math.min(p.stock, (cart.get(id) || 0) + delta);
    if (qty <= 0) cart.delete(id); else cart.set(id, qty);
    renderPos(products, posSales, posNotice);
  }

  $('#products').addEventListener('click', async e => {
    const btn = e.target.closest('[data-product-id]');
    if (!btn || btn.disabled) return;
    const id = Number(btn.dataset.productId);
    if (!restockMode) { changeCart(id, 1); return; }
    const p = products.find(x => x.id === id);
    const answer = prompt(`Entrada de stock · ${p.name}\nStock actual: ${p.stock} de ${p.capacity}\n¿Cuántas piezas entran?`);
    if (answer === null) return;
    const qty = parseInt(answer, 10);
    if (!(qty > 0) || String(qty) !== answer.trim()) { alert('Escribe una cantidad entera mayor a cero.'); return; }
    try {
      await API.products.restock(id, qty, 'reabasto');
      loadPos();
    } catch (err) {
      if (err.status !== 401) alert(err.message);
    }
  });
  $('#cartLines').addEventListener('click', e => {
    const dec = e.target.closest('[data-cart-dec]'), inc = e.target.closest('[data-cart-inc]');
    if (dec) changeCart(Number(dec.dataset.cartDec), -1);
    if (inc) changeCart(Number(inc.dataset.cartInc), 1);
  });
  $('#restockMode').addEventListener('click', () => { restockMode = !restockMode; loadPos(); });

  $('#cartPay').addEventListener('click', async e => {
    const btn = e.target;
    const method = ($('#posMethods .pill.is-active') || {}).dataset?.method || 'efectivo';
    const items = [...cart].map(([productId, qty]) => ({ productId, qty }));
    btn.disabled = true;
    try {
      const s = await API.sales.create({ method, items });
      alert(`Venta registrada · Folio ${s.folio}\n${s.items.map(i => i.qty + '× ' + i.name).join(', ')}\n`
        + `Total: ${money(pesos(s.totalCents))} · ${METHOD_LABEL[s.method]}`);
      cart.clear();
      $$('#posMethods .pill').forEach(b => b.classList.toggle('is-active', b.dataset.method === 'efectivo'));
    } catch (err) {
      if (err.status === 401) return;
      alert(err.message);  // p. ej. STOCK_INSUFICIENTE: otra caja vendió el último
    }
    loadPos();
  });

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
        <button class="btn-sm" data-reprint-payment="${p.id}" data-folio="${esc(p.folio)}" title="Reimprimir ticket (COPIA)">Ticket</button>
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
        + `Total: ${money(pesos(r.amountCents))} · ${METHOD_LABEL[r.method]}` + printNote(r.print, 'del cobro'));
      watchPrint(r.print, `del folio ${r.folio}`, 'Pagos del turno (botón Ticket)');  // no se espera
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

  $('#paymentsList').addEventListener('click', e => {
    const btn = e.target.closest('[data-reprint-payment]');
    if (btn) reprint(btn, () => API.payments.reprint(Number(btn.dataset.reprintPayment)), `del folio ${btn.dataset.folio}`);
  });

  /* ---------- Admin ---------- */
  const SHIFT_LABELS = ['Matutino', 'Vespertino', 'Nocturno', 'Fin de semana'];
  const MONTH_NAMES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre',
    'Octubre', 'Noviembre', 'Diciembre'];
  let employees = [];
  let editingEmployee = null;  // id en edición, 'new' para el alta, null si ninguno
  let editingPlan = null;
  let settings = null;

  async function loadAdmin() {
    if (!currentUser || currentUser.role !== 'administrador') { go('inicio'); return; }
    try {
      const [emps, planList, summary, months, cfg, shiftList] = await Promise.all([API.employees.list(),
        API.plans.list(), API.reports.summary(), API.reports.incomeMonthly(6), API.settings.get(), API.shifts.history()]);
      employees = emps;
      plans = planList;
      settings = cfg;
      editingEmployee = editingPlan = null;
      renderAdmin({ employees, plans, summary, months, settings, shifts: shiftList });
    } catch (err) {
      if (err.status !== 401) alert(err.message);
    }
    loadPrinterStatus();
  }

  function renderAdmin(d) {
    renderEmployees(d.employees);
    renderPrices(d.plans);
    renderReports(d.summary, d.months);
    renderShifts(d.shifts);
    renderSettings(d.settings);
  }

  /* ----- Turnos: historial de cortes con reimpresión del ticket de cierre ----- */
  function renderShifts(list) {
    $('#shiftsList').innerHTML = !list.length ? '<div class="empty">Aún no hay turnos</div>' : list.map(s => {
      const diff = s.differenceCents;
      const tone = diff === null ? 'muted' : diff < 0 ? 't-v' : diff > 0 ? 't-p' : 't-a';
      return `
      <div class="list-row" style="padding:10px 0">
        <span class="mono" style="font-size:12px;color:var(--muted);flex-shrink:0">${fmtDay(s.openedAt.slice(0, 10))} · ${s.openedAt.slice(11, 16)}–${s.closedAt ? s.closedAt.slice(11, 16) : '…'}</span>
        <span class="ellipsis" style="flex:1">${esc(s.employeeName)}</span>
        <span class="mono">${money(pesos(s.totalCents))}</span>
        <span class="mono ${tone}" style="width:84px;text-align:right" title="Diferencia de efectivo">${diff === null ? '—' : signedMoney(pesos(diff))}</span>
        ${s.closedAt
          ? `<button class="btn-sm" data-reprint-shift="${s.id}" title="Reimprimir ticket de cierre (COPIA)">Reimprimir</button>`
          : '<span class="chip chip--a">Abierto</span>'}
      </div>`;
    }).join('');
  }
  $('#shiftsList').addEventListener('click', e => {
    const btn = e.target.closest('[data-reprint-shift]');
    if (btn) reprint(btn, () => API.shifts.reprint(Number(btn.dataset.reprintShift)), 'del cierre');
  });

  /* ----- Impresora: estado (sin abrir la conexión Bluetooth) y ticket de prueba ----- */
  async function loadPrinterStatus() {
    const desc = $('#printerStatusDesc');
    try {
      const st = await API.printer.status();
      const state = st.ready === true ? 'Lista' : st.ready === false ? 'Con problema' : 'Sin confirmar';
      let text = `${state}. ${st.detail}`;
      if (st.pending) text += ` ${st.pending} ticket(s) en cola.`;
      if (st.failedToday) text += ` ${st.failedToday} ticket(s) fallido(s) hoy.`;
      if (st.mode === 'fake') text = 'Modo desarrollo. ' + text;
      desc.textContent = text;
      desc.classList.toggle('t-v', st.ready === false);
      desc.classList.toggle('t-a', st.ready === true);
      $('#cfgPortDesc').textContent = st.availablePorts.length
        ? 'Puertos en esta PC: ' + st.availablePorts.join(', ') : 'Puerto serie saliente del Bluetooth';
      $('#cfgPrinterNameDesc').textContent = st.availablePrinters.length
        ? 'Instaladas: ' + st.availablePrinters.join(', ') : 'Nombre exacto en Windows';
    } catch (err) {
      if (err.status !== 401) desc.textContent = 'No se pudo consultar: ' + err.message;
    }
  }
  $('#printerRefresh').addEventListener('click', loadPrinterStatus);
  $('#printerTest').addEventListener('click', async e => {
    const btn = e.target, label = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Imprimiendo…';
    try {
      const job = await waitPrint(await API.printer.test(), 90000, false);
      if (job.status === 'entregado') {
        alert('La impresora recibió la prueba.\nRevisa en el papel la línea de acentos (áéíóúñÑü¿¡$). '
          + 'Si salen signos raros, cambia la página de códigos y el juego de caracteres.');
      } else if (job.status === 'en_cola_windows') {
        alert('Windows aceptó la prueba. Si no sale el papel, la impresora está apagada o desconectada '
          + '(la cola de Windows no lo detecta).');
      } else if (job.status === 'fallido') {
        alert('No se imprimió la prueba.\n' + job.error);
      } else {
        alert('La prueba sigue esperando a la impresora.' + (job.error ? '\n' + job.error : ''));
      }
    } catch (err) {
      if (err.status !== 401) alert(err.message);
    } finally {
      btn.disabled = false;
      btn.textContent = label;
      loadPrinterStatus();
    }
  });

  /* ----- Empleados: la fila en edición se dibuja como formulario dentro de la misma tabla ----- */
  function employeeForm(e) {
    const isNew = !e;
    e = e || { name: '', username: '', role: 'recepcion', shiftLabel: 'Matutino', active: true };
    const opt = (value, label, sel) => `<option value="${esc(value)}"${value === sel ? ' selected' : ''}>${esc(label)}</option>`;
    const shifts = SHIFT_LABELS.includes(e.shiftLabel) || !e.shiftLabel ? SHIFT_LABELS : [e.shiftLabel, ...SHIFT_LABELS];
    return `
      <div class="table__row" data-emp-form="${isNew ? 'new' : e.id}" style="display:flex;flex-wrap:wrap;gap:10px;align-items:center">
        <input class="input" data-f="name" placeholder="Nombre completo" value="${esc(e.name)}" style="flex:2 1 180px">
        <input class="input" data-f="username" placeholder="Usuario" value="${esc(e.username)}" autocomplete="off" style="flex:1 1 130px">
        <select class="input" data-f="role" style="flex:1 1 130px">${Object.keys(ROLE_LABEL).map(r => opt(r, ROLE_LABEL[r], e.role)).join('')}</select>
        <select class="input" data-f="shiftLabel" style="flex:1 1 130px">${shifts.map(s => opt(s, s, e.shiftLabel || 'Matutino')).join('')}</select>
        ${isNew
          ? '<input class="input" data-f="pin" type="password" placeholder="PIN o contraseña" autocomplete="new-password" style="flex:1 1 130px">'
          : `<button class="toggle${e.active ? ' is-on' : ''}" data-f="active" aria-label="Activo" title="Activo"></button>
             <button class="btn-sm" data-emp-pin="${e.id}">Cambiar PIN</button>`}
        <button class="btn-sm" data-emp-save>Guardar</button>
        <button class="btn-sm" data-emp-cancel>Cancelar</button>
      </div>`;
  }

  function renderEmployees(list) {
    const rows = list.map(e => editingEmployee === e.id ? employeeForm(e) : `
      <div class="table__row cols-emp" data-emp-id="${e.id}" title="Clic para editar" style="cursor:pointer">
        <span class="cell-person"><span class="avatar">${esc(initials(e.name))}</span><span class="ellipsis">${esc(e.name)} <span class="muted" style="font-size:12px">@${esc(e.username)}</span></span></span>
        <span class="cell-soft">${ROLE_LABEL[e.role]}</span>
        <span class="cell-soft">${esc(e.shiftLabel || '—')}</span>
        <span><span class="chip ${e.locked ? 'chip--v' : e.active ? 'chip--a' : 'chip--off'}">${e.locked ? 'Bloqueado' : e.active ? 'Activo' : 'Inactivo'}</span></span>
      </div>`);
    $('#employeesTable').innerHTML = (editingEmployee === 'new' ? employeeForm(null) : '') + rows.join('');
    const first = $('#employeesTable [data-emp-form] [data-f="name"]');
    if (first) first.focus();
  }

  $('#addEmployee').addEventListener('click', () => { editingEmployee = 'new'; renderEmployees(employees); });
  $('#employeesTable').addEventListener('click', async e => {
    const row = e.target.closest('[data-emp-id]');
    if (row && editingEmployee === null) { editingEmployee = Number(row.dataset.empId); renderEmployees(employees); return; }
    const form = e.target.closest('[data-emp-form]');
    if (!form) return;
    const field = f => form.querySelector(`[data-f="${f}"]`);
    if (e.target.closest('[data-f="active"]')) { field('active').classList.toggle('is-on'); return; }
    if (e.target.closest('[data-emp-cancel]')) { editingEmployee = null; renderEmployees(employees); return; }

    const pinBtn = e.target.closest('[data-emp-pin]');
    if (pinBtn) {
      const pin = prompt('Nuevo PIN o contraseña (mínimo 4 caracteres):');
      if (pin === null) return;
      try {
        await API.employees.changePin(Number(pinBtn.dataset.empPin), pin);
        alert('PIN actualizado. Si la cuenta estaba bloqueada, ya quedó desbloqueada.');
        loadAdmin();
      } catch (err) {
        if (err.status !== 401) alert((err.fields && err.fields.pin) || err.message);
      }
      return;
    }

    if (!e.target.closest('[data-emp-save]')) return;
    const isNew = form.dataset.empForm === 'new';
    const data = { name: field('name').value, username: field('username').value, role: field('role').value,
      shiftLabel: field('shiftLabel').value };
    if (isNew) data.pin = field('pin').value; else data.active = field('active').classList.contains('is-on');
    try {
      if (isNew) await API.employees.create(data);
      else await API.employees.update(Number(form.dataset.empForm), data);
      editingEmployee = null;
      employees = await API.employees.list();
      renderEmployees(employees);
    } catch (err) {
      if (err.status === 401) return;
      const bad = Object.keys(err.fields || {}).find(f => field(f));
      if (bad) { field(bad).setCustomValidity(err.fields[bad]); field(bad).reportValidity(); }
      else alert(err.message);
    }
  });
  $('#employeesTable').addEventListener('input', e => { if (e.target.setCustomValidity) e.target.setCustomValidity(''); });

  /* ----- Precios: "Editar precio" convierte la tarjeta en un campo ----- */
  function renderPrices(list) {
    $('#priceGrid').innerHTML = list.map(p => `
      <div class="card price-card">
        <div class="section-head" style="align-items:center"><span style="font-size:15px;font-weight:700">${esc(p.name)}</span><span class="muted" style="font-size:12px">${p.days} días</span></div>
        ${editingPlan === p.id
          ? `<label class="money-input"><span>$</span><input data-price-input="${p.id}" inputmode="decimal" value="${pesos(p.priceCents)}"></label>
             <div class="row" style="gap:8px"><button class="btn-sm" data-price-save="${p.id}">Guardar</button><button class="btn-sm" data-price-cancel>Cancelar</button></div>`
          : `<span class="stat__value" style="font-size:40px;line-height:0.9">${money(pesos(p.priceCents))}</span>
             <button class="btn-sm" data-price-edit="${p.id}">Editar precio</button>`}
      </div>`).join('');
    const input = $('#priceGrid [data-price-input]');
    if (input) { input.focus(); input.select(); }
  }

  async function savePrice(id) {
    const input = $(`#priceGrid [data-price-input="${id}"]`);
    const value = input.value.trim().replace(',', '.');
    if (!/^\d+(\.\d{1,2})?$/.test(value)) {
      input.setCustomValidity('Escribe el precio en pesos, por ejemplo 300 o 299.50.');
      input.reportValidity();
      return;
    }
    try {
      await API.plans.updatePrice(id, Math.round(parseFloat(value) * 100));
      editingPlan = null;
      plans = await API.plans.list();  // Caja y Nuevo socio usan los mismos planes
      renderPrices(plans);
    } catch (err) {
      if (err.status === 401) return;
      input.setCustomValidity((err.fields && err.fields.priceCents) || err.message);
      input.reportValidity();
    }
  }
  $('#priceGrid').addEventListener('click', e => {
    const edit = e.target.closest('[data-price-edit]'), save = e.target.closest('[data-price-save]');
    if (edit) { editingPlan = Number(edit.dataset.priceEdit); renderPrices(plans); }
    if (save) savePrice(Number(save.dataset.priceSave));
    if (e.target.closest('[data-price-cancel]')) { editingPlan = null; renderPrices(plans); }
  });
  $('#priceGrid').addEventListener('input', e => e.target.setCustomValidity(''));
  $('#priceGrid').addEventListener('keydown', e => {
    const input = e.target.closest('[data-price-input]');
    if (input && e.key === 'Enter') savePrice(Number(input.dataset.priceInput));
    if (input && e.key === 'Escape') { editingPlan = null; renderPrices(plans); }
  });

  /* ----- Reportes ----- */
  function renderReports(summary, months) {
    $('#repActive').textContent = summary.activeMembers;
    $('#repNew').textContent = summary.newThisMonth;
    $('#repRenewal').textContent = summary.renewalRate === null ? '—' : summary.renewalRate + '%';
    $('#repRenewal').title = `De ${summary.expiredLast30Days} socio(s) cuya membresía venció en los últimos 30 días`;
    const current = months.find(m => m.current);
    $('#repMonthNote').textContent = current ? MONTH_NAMES[Number(current.month.slice(5)) - 1] + ' en curso' : '';
    const max = Math.max(...months.map(m => m.totalCents), 1);
    const past = months.filter(m => !m.current && m.totalCents > 0);
    const best = past.length ? past.reduce((a, b) => (b.totalCents > a.totalCents ? b : a)) : null;
    const kilo = c => {
      const p = pesos(c);
      return p >= 1000 ? '$' + (p >= 10000 ? Math.round(p / 1000) : (p / 1000).toFixed(1)) + 'k' : money(p);
    };
    $('#months').innerHTML = months.map(m => `
      <div class="month" title="${esc(m.label)}: membresías ${money(pesos(m.membershipsCents))} · refri ${money(pesos(m.fridgeCents))}">
        <span class="month__v">${kilo(m.totalCents)}</span>
        <div class="month__bar${m === best ? ' is-best' : m.current ? ' is-current' : ''}" style="height:${Math.round(m.totalCents / max * 100)}%"></div>
        <span class="month__label">${esc(m.label)}</span>
      </div>`).join('');
  }

  /* ----- Configuración: se guarda al salir del campo (o Enter) y al tocar un interruptor ----- */
  // Los campos de impresión vienen agrupados en settings.printing; aquí se ven con el nombre que usa el PUT
  const flatSettings = s => ({ ...s, printEnabled: s.printing.enabled, printerTransport: s.printing.transport,
    printerPort: s.printing.port, printerName: s.printing.printerName, printerCodepage: s.printing.codepage,
    printerCharset: s.printing.charset, ticketFooter: s.printing.ticketFooter });

  function renderSettings(s) {
    const f = flatSettings(s);
    $('#cfgName').value = f.gymName;
    $('#cfgCapacity').value = f.capacity;
    $('#cfgWarn').value = f.warnDays;
    $('#cfgPrinterTransport').value = f.printerTransport;
    $('#cfgPrinterPort').value = f.printerPort;
    $('#cfgPrinterName').value = f.printerName;
    $('#cfgCodepage').value = f.printerCodepage;
    $('#cfgCharset').value = f.printerCharset;
    $('#cfgFooter').value = f.ticketFooter;
    $$('[data-toggle]').forEach(t => t.classList.toggle('is-on', !!f[t.dataset.toggle]));
  }

  async function saveSetting(input, key, value) {
    try {
      settings = await API.settings.update({ [key]: value });
      input.setCustomValidity('');
      renderSettings(settings);
      if (/^print|^ticket/.test(key)) loadPrinterStatus();
    } catch (err) {
      if (err.status === 401) return;
      const message = (err.fields && err.fields[key]) || err.message;
      if (input.tagName !== 'INPUT') { alert(message); return; }  // interruptores
      input.setCustomValidity(message);
      input.reportValidity();
    }
  }
  const text = v => v.trim();
  [['#cfgName', 'gymName', text], ['#cfgCapacity', 'capacity', Number], ['#cfgWarn', 'warnDays', Number],
    ['#cfgPrinterTransport', 'printerTransport', text], ['#cfgPrinterPort', 'printerPort', v => v.trim().toUpperCase()],
    ['#cfgPrinterName', 'printerName', text], ['#cfgCodepage', 'printerCodepage', Number],
    ['#cfgCharset', 'printerCharset', text], ['#cfgFooter', 'ticketFooter', text]]
    .forEach(([sel, key, parse]) => {
      const input = $(sel);
      input.addEventListener('input', () => input.setCustomValidity(''));
      input.addEventListener('keydown', e => { if (e.key === 'Enter') input.blur(); });
      input.addEventListener('change', () => {
        const raw = input.value.trim();
        if (parse === Number && !/^\d+$/.test(raw)) {
          input.setCustomValidity('Escribe un número entero.');
          input.reportValidity();
          return;
        }
        if (settings && String(flatSettings(settings)[key]) === raw) return;
        saveSetting(input, key, parse(raw));
      });
    });

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
      if (tg) { saveSetting(tg, tg.dataset.toggle, !tg.classList.contains('is-on')); return; }

      // Estado del lector simulado hasta la Fase 6
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
  // Cada pantalla carga sus datos de la API al abrirse (ver go)
  initCarousel();
  renderDenoms();
  bindSelections();

  // Si ya hay sesión entra directo; si no, el 401 deja visible el login
  API.me().then(enterApp).catch(() => {});
})();

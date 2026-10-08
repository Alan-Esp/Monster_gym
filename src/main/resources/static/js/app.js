(function () {
  'use strict';

  const D = window.GYM_DATA;
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const money = n => '$' + n.toLocaleString('es-MX');
  const initials = name => name.split(' ').map(w => w[0]).slice(0, 2).join('');
  const STATUS = { a: 'Activa', p: 'Por vencer', v: 'Vencida' };
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  /* ---------- Navegación ---------- */
  const NAV_PARENT = { nuevo: 'socios', cierre: 'caja' };
  function go(screen) {
    $$('.screen').forEach(s => s.classList.toggle('is-active', s.dataset.screen === screen));
    const navKey = NAV_PARENT[screen] || screen;
    $$('#nav .pill').forEach(p => p.classList.toggle('is-active', p.dataset.go === navKey));
    if (screen === 'cierre') renderClose();
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

  /* Barra de usuario: nombre, iniciales, rol y turno (datos de /api/me) */
  function renderUser(me) {
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
    if (el.dataset.filter) setMemberFilter(el.dataset.filter);
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
  function renderIncome() {
    const t = totals();
    $$('[data-inc="total"]').forEach(el => { el.textContent = money(t.total); });
    $$('[data-inc="mem"]').forEach(el => { el.textContent = money(t.mem); });
    $$('[data-inc="refri"]').forEach(el => { el.textContent = money(t.refri); });
    $('#memSplit').style.width = Math.round(t.mem / t.total * 100) + '%';
  }

  /* ---------- Inicio ---------- */
  function renderHome() {
    const a = D.aforo, pct = Math.round(a.inside / a.capacity * 100);
    $('#aforoIn').textContent = a.inside;
    $('#aforoCap').textContent = a.capacity;
    $('#aforoFill').style.width = pct + '%';
    $('#aforoPct').textContent = pct + '% de capacidad';
    $('#aforoPeak').textContent = 'Pico ' + a.peak;
    $('#visitsToday').textContent = D.visitsToday;
    $('#visitsDelta').textContent = D.visitsDelta;
    $('#activeMembers').textContent = D.activeMembers;
    $('#countExpired').textContent = D.members.filter(m => m.k === 'v').length;
    $('#countSoon').textContent = D.members.filter(m => m.k === 'p').length;

    const max = Math.max(...D.visitsByHour);
    $('#hourBars').innerHTML = D.visitsByHour.map((v, i) =>
      `<div class="hours__bar${i === D.currentHourIndex ? ' is-now' : ''}" style="height:${Math.round(v / max * 100)}%"></div>`).join('');

    $('#topProducts').innerHTML = [...D.products].sort((x, y) => y.sold - x.sold).slice(0, 3).map((p, i) => `
      <div class="top-list__row"><span class="top-list__n">0${i + 1}</span><span class="top-list__name">${esc(p.name)}</span><span class="top-list__units">${p.sold}</span></div>`).join('');

    $('#recentGrid').innerHTML = D.visitsLog.slice(0, 6).map(v => `
      <div class="recent">
        <div class="avatar avatar--42 avatar--ring t-${v.k}"><span style="color:#c9cec3">${initials(v.name)}</span></div>
        <div class="col" style="gap:3px;min-width:0;flex:1">
          <span class="recent__name ellipsis">${esc(v.name)}</span>
          <span class="recent__status t-${v.k}">Membresía ${STATUS[v.k].toLowerCase()}</span>
        </div>
        <span class="recent__time">${v.time}</span>
      </div>`).join('');
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
  function renderAccess() {
    $('#lastEntries').innerHTML = D.visitsLog.slice(0, 5).map(v => `
      <div class="list-row"><span class="list-row__time">${v.time}</span><span class="ellipsis" style="flex:1">${esc(v.name)}</span><span style="font-size:12px;color:var(--dim)">${v.method}</span></div>`).join('');
  }

  /* ---------- Socios ---------- */
  const FILTERS = { Todos: null, Activos: 'a', 'Por vencer': 'p', Vencidos: 'v' };
  let memberFilter = 'Todos';
  function setMemberFilter(f) { memberFilter = f; renderMembers(); }
  function renderMembers() {
    const q = $('#memberSearch').value.trim().toLowerCase().replace(/ /g, '');
    $('#membersTotal').textContent = D.members.length;
    $('#memberFilters').innerHTML = Object.keys(FILTERS).map(l => {
      const n = FILTERS[l] ? D.members.filter(m => m.k === FILTERS[l]).length : D.members.length;
      return `<button class="pill pill--tall${l === memberFilter ? ' is-active' : ''}" data-filter-pick="${l}">${l}<span class="pill__count">${n}</span></button>`;
    }).join('');
    const list = D.members.filter(m =>
      (!FILTERS[memberFilter] || m.k === FILTERS[memberFilter]) &&
      (!q || m.name.toLowerCase().replace(/ /g, '').includes(q) || m.id.includes(q) || m.phone.replace(/ /g, '').includes(q)));
    $('#membersTable').innerHTML = list.length ? list.map(m => `
      <div class="table__row cols-socios">
        <span class="cell-person"><span class="avatar">${initials(m.name)}</span><span class="ellipsis">${esc(m.name)}</span></span>
        <span class="cell-mono">#${m.id}</span>
        <span class="cell-soft">${m.phone}</span>
        <span class="cell-soft">${m.plan}</span>
        <span class="cell-mono" style="color:var(--muted-2)">${m.exp}</span>
        <span><span class="chip chip--${m.k}">${STATUS[m.k]}</span></span>
        <button class="btn-sm" data-go="caja">Cobrar</button>
      </div>`).join('') : '<div class="empty">No se encontraron socios</div>';
    $('#membersCount').textContent = list.length;
  }

  /* ---------- Visitas ---------- */
  function renderVisits() {
    const fp = D.visitsLog.filter(v => v.method === 'Huella').length;
    $('#fpPct').textContent = Math.round(fp / D.visitsLog.length * 100) + '%';
    $$('[data-visits-today]').forEach(el => { el.textContent = D.visitsToday; });
    $('#insideNow').textContent = D.aforo.inside;
    $('#visitsTable').innerHTML = D.visitsLog.map(v => `
      <div class="table__row cols-visitas">
        <span class="mono" style="font-size:13px;color:var(--muted)">${v.time}</span>
        <span class="cell-person"><span class="avatar avatar--34">${initials(v.name)}</span><span class="ellipsis">${esc(v.name)}</span></span>
        <span class="cell-mono">#${v.id}</span>
        <span class="cell-soft">${v.method}</span>
        <span><span class="chip chip--${v.k}">${STATUS[v.k]}</span></span>
      </div>`).join('');
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
  function planButtons(selected) {
    return D.plans.map(p => `
      <button class="plan${p.name === selected ? ' is-active' : ''}" data-plan="${p.name}">
        <span class="plan__name">${p.name}</span><span class="plan__price">${money(p.price)}</span>
      </button>`).join('');
  }

  /* ---------- Caja ---------- */
  function renderCaja() {
    $('#payPlans').innerHTML = planButtons('Mensual');
    $('#newPlans').innerHTML = planButtons('Mensual');
    $('#paymentsList').innerHTML = D.payments.map(p => `
      <div class="list-row" style="padding:10px 0">
        <span class="list-row__time">${p.time}</span>
        <span class="ellipsis" style="flex:1">${esc(p.name)}</span>
        <span class="pay-row__plan">${p.plan}</span>
        <span class="pay-row__method">${p.method}</span>
        <span class="pay-row__amount">${money(p.amount)}</span>
      </div>`).join('');
    $('#planCounts').innerHTML = ['Mensual', 'Quincenal', 'Semanal'].map((l, i) => `
      <div class="count"><span class="count__n"${i === 0 ? ' style="color:var(--lime)"' : ''}>${D.payments.filter(p => p.plan === l).length}</span><span class="count__label">${l}</span></div>`).join('');
    $('#payCount').textContent = D.payments.length;
  }

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
          const price = D.plans.find(p => p.name === plan.dataset.plan).price;
          $('#payBtn').textContent = 'Cobrar ' + money(price);
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
      }
    });
    $('#memberSearch').addEventListener('input', renderMembers);
  }

  /* ---------- Inicio de la app ---------- */
  tick();
  setInterval(tick, 1000);
  renderFingerprint();
  renderIncome();
  renderHome();
  initCarousel();
  renderAccess();
  renderMembers();
  renderVisits();
  renderPos();
  renderCaja();
  renderAdmin();
  renderDenoms();
  bindSelections();

  // Si ya hay sesión entra directo; si no, el 401 deja visible el login
  API.me().then(enterApp).catch(() => {});
})();

// Cliente de la API. Envía la cookie de sesión y el token CSRF; los errores llegan como ApiError
// con el mensaje en español del servidor. Un 401 (fuera del login) avisa a app.js para mostrar el login.
(function () {
  'use strict';

  class ApiError extends Error {
    constructor(status, code, message, fields) {
      super(message);
      this.status = status;
      this.code = code;
      this.fields = fields || null;
    }
  }

  let unauthorizedHandler = null;

  function csrfToken() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return m ? decodeURIComponent(m[1]) : null;
  }

  async function request(method, url, body, retried) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET') {
      const token = csrfToken();
      if (token) headers['X-XSRF-TOKEN'] = token;
    }

    let res;
    try {
      res = await fetch(url, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : JSON.stringify(body)
      });
    } catch (e) {
      throw new ApiError(0, 'SIN_CONEXION', 'No se pudo conectar con el servidor.');
    }

    const data = res.status === 204 ? null : await res.json().catch(() => null);
    if (res.ok) return data;

    const err = (data && data.error) || {};
    const error = new ApiError(res.status, err.code || 'ERROR',
      err.message || 'Ocurrió un error inesperado.', err.fields);

    // Si la cookie XSRF-TOKEN no existía o cambió, el servidor ya mandó una nueva: se reintenta una vez
    if (error.code === 'CSRF_INVALIDO' && !retried) return request(method, url, body, true);
    if (res.status === 401 && url !== '/api/auth/login' && unauthorizedHandler) unauthorizedHandler(error);
    throw error;
  }

  window.API = {
    ApiError,
    onUnauthorized(fn) { unauthorizedHandler = fn; },
    get: url => request('GET', url),
    post: (url, body) => request('POST', url, body),
    put: (url, body) => request('PUT', url, body),

    login: (username, pin) => request('POST', '/api/auth/login', { username, pin }),
    logout: () => request('POST', '/api/auth/logout'),
    me: () => request('GET', '/api/me'),
    shifts: {
      open: openingCashCents => request('POST', '/api/shifts/open', { openingCashCents }),
      current: () => request('GET', '/api/shifts/current'),
      close: (countedCashCents, notes) => request('POST', '/api/shifts/close', { countedCashCents, notes }),
      history: (limit = 50) => request('GET', '/api/shifts?limit=' + limit),
      reprint: id => request('POST', '/api/shifts/' + encodeURIComponent(id) + '/ticket')
    },
    // Tickets: el cobro y el cierre devuelven print: { jobId, status }; el resultado se consulta con job(id)
    printer: {
      status: () => request('GET', '/api/printer/status'),
      test: () => request('POST', '/api/printer/test'),
      job: id => request('GET', '/api/print-jobs/' + encodeURIComponent(id))
    },
    products: {
      list: () => request('GET', '/api/products'),
      // reason: reabasto (suma), merma (resta) o ajuste (con signo)
      restock: (id, qty, reason) => request('POST', '/api/products/' + encodeURIComponent(id) + '/restock', { qty, reason })
    },
    sales: {
      create: data => request('POST', '/api/sales', data),
      currentShift: () => request('GET', '/api/sales?shift=current'),
      void: (id, reason) => request('POST', '/api/sales/' + encodeURIComponent(id) + '/void', { reason })
    },
    employees: {
      list: () => request('GET', '/api/employees'),
      create: data => request('POST', '/api/employees', data),
      update: (id, data) => request('PUT', '/api/employees/' + encodeURIComponent(id), data),
      changePin: (id, pin) => request('PUT', '/api/employees/' + encodeURIComponent(id) + '/pin', { pin })
    },
    settings: {
      get: () => request('GET', '/api/settings'),
      update: data => request('PUT', '/api/settings', data)
    },
    reports: {
      incomeMonthly: (months = 6) => request('GET', '/api/reports/income-monthly?months=' + months),
      summary: () => request('GET', '/api/reports/summary')
    },
    members: {
      // params: { q, status, page }; los vacíos no se envían
      list: (params = {}) => {
        const qs = new URLSearchParams();
        Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.set(k, v); });
        const s = qs.toString();
        return request('GET', '/api/members' + (s ? '?' + s : ''));
      },
      get: id => request('GET', '/api/members/' + encodeURIComponent(id)),
      create: data => request('POST', '/api/members', data),
      update: (id, data) => request('PUT', '/api/members/' + encodeURIComponent(id), data)
    },
    plans: {
      list: () => request('GET', '/api/plans'),
      updatePrice: (id, priceCents) => request('PUT', '/api/plans/' + encodeURIComponent(id), { priceCents })
    },
    visits: {
      register: data => request('POST', '/api/visits', data),
      day: date => request('GET', '/api/visits' + (date ? '?date=' + encodeURIComponent(date) : ''))
    },
    dashboard: () => request('GET', '/api/dashboard'),
    payments: {
      create: data => request('POST', '/api/payments', data),
      currentShift: () => request('GET', '/api/payments?shift=current'),
      void: (id, reason) => request('POST', '/api/payments/' + encodeURIComponent(id) + '/void', { reason }),
      reprint: id => request('POST', '/api/payments/' + encodeURIComponent(id) + '/ticket')
    }
  };
})();

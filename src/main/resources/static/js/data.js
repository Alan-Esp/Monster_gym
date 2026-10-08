// Datos de ejemplo. Reemplazar por llamadas a la API cuando exista el backend.
window.GYM_DATA = {
  user: { name: 'Sergio Ramírez', initials: 'SR', role: 'Administrador', shift: 'Turno matutino' },
  fingerprintConnected: false,
  aforo: { inside: 23, capacity: 120, peak: '18:00' },
  visitsToday: 84,
  visitsDelta: '+12 vs. ayer',
  visitsByHour: [4, 9, 12, 7, 5, 4, 6, 8, 11, 9, 6, 3],
  currentHourIndex: 10,
  activeMembers: 412,

  plans: [
    { name: 'Semanal', price: 150, days: 7 },
    { name: 'Quincenal', price: 280, days: 15 },
    { name: 'Mensual', price: 450, days: 30 },
    { name: 'Trimestral', price: 1200, days: 90 },
    { name: 'Anual', price: 4200, days: 365 }
  ],

  // k: a = activa, p = por vencer, v = vencida
  members: [
    { id: '0412', name: 'Daniela Ortega', phone: '55 1234 8890', plan: 'Mensual', exp: '28 oct', k: 'a' },
    { id: '0087', name: 'Luis Fernando Cruz', phone: '55 9087 1123', plan: 'Anual', exp: '14 mar 27', k: 'a' },
    { id: '0233', name: 'Mariana Salas', phone: '55 3321 7765', plan: 'Mensual', exp: '7 oct', k: 'p' },
    { id: '0398', name: 'Jorge Medina', phone: '55 4410 2290', plan: 'Semanal', exp: '5 oct', k: 'v' },
    { id: '0151', name: 'Andrea Villalobos', phone: '55 7781 0043', plan: 'Trimestral', exp: '30 nov', k: 'a' },
    { id: '0276', name: 'Ricardo Peña', phone: '55 2209 6618', plan: 'Mensual', exp: '22 oct', k: 'a' },
    { id: '0019', name: 'Karla Domínguez', phone: '55 6630 4471', plan: 'Mensual', exp: '8 oct', k: 'p' },
    { id: '0305', name: 'Óscar Galindo', phone: '55 1188 3920', plan: 'Semanal', exp: '9 oct', k: 'p' },
    { id: '0144', name: 'Paola Herrera', phone: '55 9902 5537', plan: 'Quincenal', exp: '10 oct', k: 'p' },
    { id: '0360', name: 'Tomás Aguilar', phone: '55 4471 8802', plan: 'Mensual', exp: '2 oct', k: 'v' },
    { id: '0201', name: 'Fernanda Ríos', phone: '55 3056 7719', plan: 'Quincenal', exp: '19 oct', k: 'a' }
  ],

  visitsLog: [
    { time: '16:38', id: '0412', name: 'Daniela Ortega', method: 'Huella', k: 'a' },
    { time: '16:21', id: '0087', name: 'Luis Fernando Cruz', method: 'Huella', k: 'a' },
    { time: '16:05', id: '0233', name: 'Mariana Salas', method: 'Manual', k: 'p' },
    { time: '15:47', id: '0201', name: 'Fernanda Ríos', method: 'Huella', k: 'a' },
    { time: '15:30', id: '0151', name: 'Andrea Villalobos', method: 'Huella', k: 'a' },
    { time: '15:12', id: '0276', name: 'Ricardo Peña', method: 'Huella', k: 'a' },
    { time: '14:55', id: '0019', name: 'Karla Domínguez', method: 'Manual', k: 'p' },
    { time: '14:31', id: '0305', name: 'Óscar Galindo', method: 'Huella', k: 'p' }
  ],

  payments: [
    { time: '16:12', name: 'Ricardo Peña', plan: 'Mensual', amount: 450, method: 'Tarjeta' },
    { time: '15:40', name: 'Fernanda Ríos', plan: 'Quincenal', amount: 280, method: 'Efectivo' },
    { time: '14:58', name: 'Andrea Villalobos', plan: 'Trimestral', amount: 1200, method: 'Transferencia' },
    { time: '13:20', name: 'Daniela Ortega', plan: 'Mensual', amount: 450, method: 'Efectivo' },
    { time: '12:05', name: 'Óscar Galindo', plan: 'Semanal', amount: 150, method: 'Efectivo' },
    { time: '10:47', name: 'Paola Herrera', plan: 'Quincenal', amount: 280, method: 'Tarjeta' },
    { time: '09:30', name: 'Hugo Navarro', plan: 'Mensual', amount: 450, method: 'Efectivo' },
    { time: '08:15', name: 'Sofía Treviño', plan: 'Semanal', amount: 150, method: 'Efectivo' },
    { time: '07:02', name: 'Diego Montes', plan: 'Mensual', amount: 450, method: 'Tarjeta' }
  ],

  products: [
    { id: 1, name: 'Agua natural', size: '1 L', price: 15, stock: 18, cap: 24, sold: 9, min: 5 },
    { id: 2, name: 'Agua mineral', size: '600 ml', price: 18, stock: 10, cap: 24, sold: 5, min: 5 },
    { id: 3, name: 'Bebida isotónica', size: '600 ml', price: 25, stock: 4, cap: 18, sold: 11, min: 5 },
    { id: 4, name: 'Bebida energética', size: '473 ml', price: 38, stock: 12, cap: 24, sold: 7, min: 5 },
    { id: 5, name: 'Proteína lista', size: '330 ml', price: 55, stock: 2, cap: 12, sold: 6, min: 3 },
    { id: 6, name: 'Café frío', size: '300 ml', price: 40, stock: 8, cap: 12, sold: 3, min: 3 },
    { id: 7, name: 'Barra de proteína', size: '60 g', price: 35, stock: 15, cap: 20, sold: 4, min: 4 },
    { id: 8, name: 'Jugo verde', size: '500 ml', price: 45, stock: 0, cap: 10, sold: 6, min: 3 }
  ],

  sales: [
    { time: '16:32', items: '2× Agua natural', total: 30, pay: 'Efectivo' },
    { time: '16:10', items: '1× Proteína lista, 1× Barra de proteína', total: 90, pay: 'Tarjeta' },
    { time: '15:48', items: '1× Bebida isotónica', total: 25, pay: 'Efectivo' },
    { time: '15:20', items: '1× Bebida energética', total: 38, pay: 'Efectivo' }
  ],

  // Turno y conteo de efectivo para la pantalla de cierre (refri = suma de "sales" por método)
  shift: { openingCash: 500, openedAt: null, refriCash: 93, refriCard: 90 },
  cashCount: {},

  employees: [
    { name: 'Sergio Ramírez', role: 'Administrador', shift: 'Matutino', active: true },
    { name: 'Ana López', role: 'Recepción', shift: 'Vespertino', active: true },
    { name: 'Miguel Torres', role: 'Entrenador', shift: 'Matutino', active: true },
    { name: 'Lucía Méndez', role: 'Recepción', shift: 'Fin de semana', active: false }
  ],

  monthlyIncome: [
    { label: 'May', value: 118 }, { label: 'Jun', value: 126 }, { label: 'Jul', value: 121 },
    { label: 'Ago', value: 139 }, { label: 'Sep', value: 147 }, { label: 'Oct', value: 96 }
  ],

  settings: { gymName: 'Monster Gym', capacity: 120, warnDays: 3, manualAccess: true, sound: true }
};

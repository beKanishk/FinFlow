'use strict';

// ─── State ───────────────────────────────────────────────────────────────────
let token          = localStorage.getItem('ff_token');
let walletId       = localStorage.getItem('ff_wallet_id');
let txPage         = 0;
let txTotal        = 0;
let pendingPayment = null;   // { paymentOrderId, paymentReference, amount, paymentMethod }
const TX_PAGE_SIZE = 10;

// ─── API Helper ──────────────────────────────────────────────────────────────
async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(path, {
    ...options,
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });

  if (res.status === 401 && path !== '/auth/token') {
    logout('Session expired. Please log in again.');
    throw new Error('Session expired. Please log in again.');
  }

  // /auth/token returns a plain JWT string, not JSON
  if (path === '/auth/token') {
    if (!res.ok) throw new Error('Invalid credentials');
    return res.text();
  }

  const json = await res.json();
  if (!json.success) throw new Error(json.error || 'Request failed');
  return json.data;
}

// ─── Alerts ──────────────────────────────────────────────────────────────────
function showAlert(containerId, message, type = 'danger') {
  const el = document.getElementById(containerId);
  if (!el) return;
  el.className = `alert alert-${type}`;
  el.textContent = message;
  el.classList.remove('d-none');
  if (type === 'success') setTimeout(() => clearAlert(containerId), 3000);
}

function clearAlert(containerId) {
  const el = document.getElementById(containerId);
  if (!el) return;
  el.className = 'd-none';
  el.textContent = '';
}

// ─── Auth ─────────────────────────────────────────────────────────────────────
async function login(username, password) {
  token = await api('/auth/token', {
    method: 'POST',
    body: { username, password },
  });
  localStorage.setItem('ff_token', token);
  localStorage.setItem('ff_username', username);
  await enterDashboard(username);
}

async function register(username, password, name, email) {
  await api('/users/register', {
    method: 'POST',
    body: { username, password, name, email },
  });
  await login(username, password);
}

function logout(message) {
  token = null;
  walletId = null;
  localStorage.clear();
  showSection('auth');
  if (message) showAlert('auth-alert', message, 'warning');
}

// ─── Dashboard entry ─────────────────────────────────────────────────────────
async function enterDashboard(username) {
  document.getElementById('nav-username').textContent = username || localStorage.getItem('ff_username') || '';
  showSection('dashboard');
  try {
    const wallet = await api('/wallets/me');
    walletId = wallet.walletId;
    localStorage.setItem('ff_wallet_id', walletId);
    renderWallet(wallet);
    showSection('wallet');
    await loadTransactions();
  } catch {
    showSection('create-wallet');
  }
}

// ─── Wallet ───────────────────────────────────────────────────────────────────
async function createWallet(currency) {
  const wallet = await api('/wallets', {
    method: 'POST',
    body: { currency },
  });
  walletId = wallet.walletId;
  localStorage.setItem('ff_wallet_id', walletId);
  renderWallet(wallet);
  showSection('wallet');
  await loadTransactions();
}

async function refreshWallet() {
  const wallet = await api(`/wallets/${walletId}`);
  renderWallet(wallet);
}

async function freezeWallet() {
  const wallet = await api(`/wallets/${walletId}/freeze`, { method: 'PUT' });
  renderWallet(wallet);
}

async function unfreezeWallet() {
  const wallet = await api(`/wallets/${walletId}/unfreeze`, { method: 'PUT' });
  renderWallet(wallet);
}

function renderWallet(wallet) {
  walletId = wallet.walletId;
  const balance  = document.getElementById('wallet-balance');
  const currency = document.getElementById('wallet-currency-display');
  const badge    = document.getElementById('wallet-status-badge');
  const freezeBtn   = document.getElementById('freeze-btn');
  const unfreezeBtn = document.getElementById('unfreeze-btn');

  balance.textContent  = Number(wallet.amount).toFixed(2);
  currency.textContent = wallet.currency;

  const frozen = wallet.freeze || wallet.status === 'FROZEN';
  badge.textContent = wallet.status;
  badge.className   = `badge ${frozen ? 'bg-warning text-dark' : 'bg-success'}`;

  freezeBtn.classList.toggle('d-none', frozen);
  unfreezeBtn.classList.toggle('d-none', !frozen);
}

// ─── Payment ──────────────────────────────────────────────────────────────────
const paymentModal = new bootstrap.Modal(document.getElementById('payment-modal'));

async function initiatePayment(amount, paymentMethod) {
  const result = await api('/payments/initiate', {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: { walletId, amount: Number(amount), paymentMethod },
  });
  pendingPayment = result;

  document.getElementById('modal-method').textContent    = paymentMethod.replace('_', ' ');
  document.getElementById('modal-amount').textContent    = `${Number(amount).toFixed(2)}`;
  document.getElementById('modal-reference').textContent = result.paymentReference;
  document.getElementById('gateway-payment-id').value    = 'GW-TXN-' + Math.random().toString(36).substring(2, 10).toUpperCase();
  document.getElementById('payment-modal-alert').className = 'd-none';

  paymentModal.show();
}

async function simulateWebhook(status) {
  if (!pendingPayment) return;
  const gatewayPaymentId = document.getElementById('gateway-payment-id').value || undefined;
  try {
    await api('/payments/webhook', {
      method: 'POST',
      body: { paymentOrderId: pendingPayment.paymentOrderId, status, gatewayPaymentId },
    });
    paymentModal.hide();
    pendingPayment = null;
    await refreshWallet();
    await loadTransactions();
    showAlert('action-alert', 'Payment successful — money added to wallet', 'success');
  } catch (err) {
    const el = document.getElementById('payment-modal-alert');
    el.className = 'alert alert-danger';
    el.textContent = err.message;
    if (status === 'FAILED') {
      paymentModal.hide();
      pendingPayment = null;
      showAlert('action-alert', 'Payment failed', 'danger');
    }
  }
}

// ─── Transactions ─────────────────────────────────────────────────────────────

async function withdraw(amount, description) {
  const result = await api(`/wallets/${walletId}/withdraw`, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: { amount: Number(amount), description: description || undefined },
  });
  await refreshWallet();
  await loadTransactions();
  return result;
}

async function transfer(destinationUsername, amount, description) {
  const result = await api(`/wallets/${walletId}/transfer`, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: { destinationUsername, amount: Number(amount), description: description || undefined },
  });
  await refreshWallet();
  await loadTransactions();
  return result;
}

async function loadTransactions(page = 0) {
  txPage = page;
  const status = document.getElementById('filter-status').value || undefined;
  const type   = document.getElementById('filter-type').value   || undefined;
  const from   = document.getElementById('filter-from').value   || undefined;
  const to     = document.getElementById('filter-to').value     || undefined;

  const filters = {};
  if (status) filters.status = status;
  if (type)   filters.type   = type;
  if (from)   filters.from   = from;
  if (to)     filters.to     = to;

  try {
    const result = await api(`/wallets/${walletId}/transactions/search`, {
      method: 'POST',
      body: { ...filters, page: txPage, size: TX_PAGE_SIZE },
    });
    txTotal = result.totalPages;
    renderTransactions(result);
  } catch (e) {
    document.getElementById('tx-table-body').innerHTML =
      `<tr><td colspan="7" class="text-center text-danger">${e.message}</td></tr>`;
  }
}

function renderTransactions(data) {
  const tbody = document.getElementById('tx-table-body');

  if (!data.content || data.content.length === 0) {
    tbody.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-3">No transactions found</td></tr>';
  } else {
    tbody.innerHTML = data.content.map(tx => {
      const date   = tx.createdAt ? new Date(tx.createdAt).toLocaleString() : '—';
      const amount = Number(tx.amount).toFixed(2);
      const typeBadge   = typeToBadge(tx.type);
      const statusBadge = statusToBadge(tx.status);
      let parties = '';
      if (tx.type === 'TRANSFER') {
        if (tx.sourceUsername)      parties += `<span class="text-muted small ms-1">← ${tx.sourceUsername}</span>`;
        if (tx.destinationUsername) parties += `<span class="text-muted small ms-1">→ ${tx.destinationUsername}</span>`;
      }
      const method = tx.paymentMethod
        ? `<span class="badge bg-secondary">${tx.paymentMethod.replace('_', ' ')}</span>` : '—';
      return `
        <tr>
          <td class="text-nowrap small">${date}</td>
          <td>${typeBadge}${parties}</td>
          <td>${statusBadge}</td>
          <td>${method}</td>
          <td class="text-end fw-semibold">${amount}</td>
          <td>${tx.currency}</td>
          <td class="text-muted small">${tx.description || '—'}</td>
        </tr>`;
    }).join('');
  }

  document.getElementById('tx-page-info').textContent =
    `Page ${data.page + 1} of ${Math.max(data.totalPages, 1)} · ${data.totalElements} total`;

  document.getElementById('prev-page-btn').disabled = data.page === 0;
  document.getElementById('next-page-btn').disabled = data.page + 1 >= data.totalPages;
}

function typeToBadge(type) {
  const map = { DEPOSIT: 'bg-success', WITHDRAWAL: 'bg-danger', TRANSFER: 'bg-primary' };
  return `<span class="badge ${map[type] || 'bg-secondary'}">${type}</span>`;
}

function statusToBadge(status) {
  const map = { COMPLETED: 'bg-success', PENDING: 'bg-warning text-dark', FAILED: 'bg-danger' };
  return `<span class="badge ${map[status] || 'bg-secondary'}">${status}</span>`;
}

// ─── Section visibility ───────────────────────────────────────────────────────
function showSection(name) {
  document.getElementById('auth-section').classList.toggle('d-none', name !== 'auth');
  document.getElementById('dashboard-section').classList.toggle('d-none', name === 'auth');
  if (name !== 'auth') {
    document.getElementById('create-wallet-section').classList.toggle('d-none', name !== 'create-wallet');
    document.getElementById('wallet-section').classList.toggle('d-none', name !== 'wallet');
  }
}

function showActionForm(name) {
  ['deposit', 'withdraw', 'transfer'].forEach(f => {
    document.getElementById(`${f}-form`).classList.toggle('d-none', f !== name);
  });
  document.querySelectorAll('#actionTabs .nav-link').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.action === name);
  });
}

// ─── Event Listeners ─────────────────────────────────────────────────────────
document.querySelectorAll('#authTabs .nav-link').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('#authTabs .nav-link').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    const isLogin = btn.dataset.tab === 'login';
    document.getElementById('login-form').classList.toggle('d-none', !isLogin);
    document.getElementById('register-form').classList.toggle('d-none', isLogin);
    clearAlert('auth-alert');
  });
});

document.getElementById('login-form').addEventListener('submit', async e => {
  e.preventDefault();
  clearAlert('auth-alert');
  try {
    await login(
      document.getElementById('login-username').value,
      document.getElementById('login-password').value,
    );
  } catch (err) {
    showAlert('auth-alert', err.message);
  }
});

document.getElementById('register-form').addEventListener('submit', async e => {
  e.preventDefault();
  clearAlert('auth-alert');
  try {
    await register(
      document.getElementById('reg-username').value,
      document.getElementById('reg-password').value,
      document.getElementById('reg-name').value,
      document.getElementById('reg-email').value,
    );
  } catch (err) {
    showAlert('auth-alert', err.message);
  }
});

document.getElementById('logout-btn').addEventListener('click', logout);

document.getElementById('create-wallet-btn').addEventListener('click', async () => {
  clearAlert('dashboard-alert');
  try {
    await createWallet(document.getElementById('wallet-currency').value);
  } catch (err) {
    showAlert('dashboard-alert', err.message);
  }
});

document.getElementById('freeze-btn').addEventListener('click', async () => {
  try { await freezeWallet(); } catch (err) { showAlert('dashboard-alert', err.message); }
});
document.getElementById('unfreeze-btn').addEventListener('click', async () => {
  try { await unfreezeWallet(); } catch (err) { showAlert('dashboard-alert', err.message); }
});

document.querySelectorAll('#actionTabs .nav-link').forEach(btn => {
  btn.addEventListener('click', () => {
    showActionForm(btn.dataset.action);
    clearAlert('action-alert');
  });
});

document.querySelectorAll('.payment-method-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.payment-method-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById('payment-method').value = btn.dataset.method;
  });
});

document.getElementById('deposit-form').addEventListener('submit', async e => {
  e.preventDefault();
  clearAlert('action-alert');
  try {
    await initiatePayment(
      document.getElementById('deposit-amount').value,
      document.getElementById('payment-method').value,
    );
    e.target.reset();
    document.querySelectorAll('.payment-method-btn').forEach(b => b.classList.remove('active'));
    document.querySelector('.payment-method-btn[data-method="UPI"]').classList.add('active');
    document.getElementById('payment-method').value = 'UPI';
  } catch (err) {
    showAlert('action-alert', err.message);
  }
});

document.getElementById('simulate-success-btn').addEventListener('click', () => simulateWebhook('SUCCESS'));
document.getElementById('simulate-fail-btn').addEventListener('click',    () => simulateWebhook('FAILED'));

document.getElementById('withdraw-form').addEventListener('submit', async e => {
  e.preventDefault();
  clearAlert('action-alert');
  try {
    await withdraw(
      document.getElementById('withdraw-amount').value,
      document.getElementById('withdraw-desc').value,
    );
    showAlert('action-alert', 'Withdrawal successful', 'success');
    e.target.reset();
  } catch (err) {
    showAlert('action-alert', err.message);
  }
});

document.getElementById('transfer-form').addEventListener('submit', async e => {
  e.preventDefault();
  clearAlert('action-alert');
  try {
    await transfer(
      document.getElementById('transfer-username').value,
      document.getElementById('transfer-amount').value,
      document.getElementById('transfer-desc').value,
    );
    showAlert('action-alert', 'Transfer successful', 'success');
    e.target.reset();
  } catch (err) {
    showAlert('action-alert', err.message);
  }
});

document.getElementById('apply-filter-btn').addEventListener('click', () => loadTransactions(0));
document.getElementById('refresh-history-btn').addEventListener('click', () => loadTransactions(0));
document.getElementById('prev-page-btn').addEventListener('click', () => loadTransactions(txPage - 1));
document.getElementById('next-page-btn').addEventListener('click', () => loadTransactions(txPage + 1));

// ─── Dark mode ────────────────────────────────────────────────────────────────
function applyTheme(dark) {
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  document.documentElement.setAttribute('data-bs-theme', dark ? 'dark' : 'light');
  const icon = dark ? '☀️' : '🌙';
  document.getElementById('dark-toggle').textContent      = icon;
  document.getElementById('dark-toggle-auth').textContent = icon;
  localStorage.setItem('ff_theme', dark ? 'dark' : 'light');
}

function toggleTheme() {
  applyTheme(document.documentElement.getAttribute('data-theme') !== 'dark');
}

document.getElementById('dark-toggle').addEventListener('click', toggleTheme);
document.getElementById('dark-toggle-auth').addEventListener('click', toggleTheme);

// ─── Init ─────────────────────────────────────────────────────────────────────
applyTheme(localStorage.getItem('ff_theme') === 'dark');

if (token && walletId) {
  enterDashboard();
} else {
  showSection('auth');
}

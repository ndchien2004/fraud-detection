// Simulator UI: plain JavaScript, no build step.
// REST calls go to /simulator/*, live updates arrive over STOMP/WebSocket at /ws/live-feed.
"use strict";

const $ = (id) => document.getElementById(id);
const vnd = new Intl.NumberFormat("vi-VN");
const MAX_FEED_ROWS = 100;
let catalog = { cards: [], merchants: [], cities: [] };

// ---------- helpers ----------

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.detail || data.title || `HTTP ${res.status}`);
  }
  return data;
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") node.className = v;
    else node.setAttribute(k, v);
  }
  for (const c of children) node.append(c instanceof Node ? c : document.createTextNode(c ?? ""));
  return node;
}

const badge = (decision, big = false) => el("span", { class: `badge ${decision}${big ? " big" : ""}` }, decision);
const time = (iso) => new Date(iso).toLocaleTimeString("vi-VN", { hour12: false });
const score = (r) => (r.riskScore == null ? "—" : r.riskScore.toFixed(2));
// show a city name instead of raw coordinates (transactions scatter a few km around the centre)
const cityName = (tx) => {
  const c = catalog.cities.find((c) =>
    Math.abs(c.location.lat - tx.location.lat) < 0.2 && Math.abs(c.location.lon - tx.location.lon) < 0.2);
  return c ? c.name : `${tx.location.lat.toFixed(2)}, ${tx.location.lon.toFixed(2)}`;
};

// ---------- catalog + manual form ----------

async function loadCatalog() {
  catalog = await api("GET", "/simulator/catalog");
  const card = $("m-card");
  for (const c of catalog.cards) {
    card.append(el("option", { value: c.cardId },
      `${c.cardId} · ${c.homeCityName} · TB ${vnd.format(Math.round(c.historicalAverage))}đ`));
  }
  for (const m of catalog.merchants) $("m-merchant").append(el("option", { value: m.code }, m.name));
  for (const c of catalog.cities) $("m-city").append(el("option", { value: c.code }, c.name));
  card.addEventListener("change", applyCardDefaults);
  applyCardDefaults();
}

function applyCardDefaults() {
  const c = catalog.cards.find((c) => c.cardId === $("m-card").value);
  if (!c) return;
  $("m-city").value = c.homeCity;
  $("m-amount").value = c.typicalAmount;
}

$("manual-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = $("m-submit");
  const box = $("manual-result");
  button.disabled = true;
  try {
    const { transaction, result } = await api("POST", "/simulator/manual-transaction", {
      cardId: $("m-card").value,
      amount: Number($("m-amount").value),
      merchant: $("m-merchant").value,
      city: $("m-city").value,
    });
    renderManualResult(box, transaction, result);
  } catch (e) {
    box.hidden = false;
    box.className = "result error";
    box.replaceChildren(`Lỗi: ${e.message}`);
  } finally {
    button.disabled = false;
  }
});

function renderManualResult(box, tx, r) {
  const f = r.features;
  const rows = f ? [
    ["Số giao dịch 5 phút", f.so_giao_dich_5_phut],
    ["Tổng tiền 1 giờ", `${vnd.format(f.tong_tien_1_gio)}đ`],
    ["Trung bình lịch sử", `${vnd.format(Math.round(f.trung_binh_lich_su))}đ`],
    ["Lệch so với trung bình", `${f.lech_so_voi_trung_binh >= 0 ? "+" : ""}${(f.lech_so_voi_trung_binh * 100).toFixed(0)}%`],
    ["Di chuyển bất thường", f.khoang_cach_bat_thuong ? "Có" : "Không"],
    ["Latency", `${r.latencyMs} ms`],
  ] : [["Latency", `${r.latencyMs} ms`]];
  box.hidden = false;
  box.className = "result";
  box.replaceChildren(
    el("div", { class: "headline" },
      badge(r.decision, true),
      el("span", {}, r.triggeredRule ? `Rule: ${r.triggeredRule}` : `Điểm rủi ro ML: ${score(r)}`),
      el("span", { class: "hint", style: "margin:0" }, `${tx.transactionId} · ${vnd.format(tx.amount)}đ`)),
    el("div", { class: "features" }, ...rows.map(([k, v]) => el("div", {}, el("span", {}, k), el("span", {}, String(v))))),
  );
}

// ---------- auto mode + stats ----------

let rateTimer;

$("auto-enabled").addEventListener("change", () => sendAutoMode());
$("auto-rate").addEventListener("input", () => {
  $("auto-rate-value").value = $("auto-rate").value;
  clearTimeout(rateTimer);
  rateTimer = setTimeout(() => $("auto-enabled").checked && sendAutoMode(), 300);
});

async function sendAutoMode() {
  try {
    const status = await api("POST", "/simulator/auto-mode", {
      enabled: $("auto-enabled").checked,
      ratePerSecond: Number($("auto-rate").value),
    });
    renderAutoStatus(status);
  } catch (e) {
    alert(`Không đổi được chế độ tự động: ${e.message}`);
  }
}

function renderAutoStatus(status) {
  $("auto-enabled").checked = status.enabled;
  $("auto-label").textContent = status.enabled ? "Bật" : "Tắt";
  if (document.activeElement !== $("auto-rate")) {
    $("auto-rate").value = status.ratePerSecond;
    $("auto-rate-value").value = status.ratePerSecond;
  }
}

async function refreshStats() {
  try {
    const { totals, autoMode } = await api("GET", "/simulator/stats");
    $("stat-total").textContent = vnd.format(totals.totalSent);
    $("stat-ok").textContent = vnd.format(totals.choQua);
    $("stat-review").textContent = vnd.format(totals.xemXet);
    $("stat-block").textContent = vnd.format(totals.chan);
    const extra = [];
    if (totals.failed) extra.push(`${vnd.format(totals.failed)} lỗi gọi decision-api`);
    if (totals.dropped) extra.push(`${vnd.format(totals.dropped)} bị bỏ qua vì quá tải`);
    $("stat-extra").textContent = extra.join(" · ");
    renderAutoStatus(autoMode);
  } catch {
    // simulator restarting: try again next tick
  }
}

// ---------- scenarios ----------

let currentRun = null;
// The server starts a scenario before its HTTP answer (with the runId) reaches us, so the first
// WebSocket events can arrive before we know they are ours: keep them and replay them.
let earlyEvents = [];

document.querySelectorAll("[data-scenario]").forEach((button) =>
  button.addEventListener("click", () => startScenario(button.dataset.scenario)));

async function startScenario(name) {
  setScenarioButtons(true);
  try {
    currentRun = await api("POST", `/simulator/scenario/${name}`);
    $("scenario-panel").hidden = false;
    $("sc-title").textContent = currentRun.title;
    $("sc-card").textContent = currentRun.cardId;
    $("sc-expect").textContent = currentRun.expectation;
    $("sc-bar").style.width = "0";
    $("sc-log").replaceChildren();
    $("sc-rows").replaceChildren();
    $("sc-summary").hidden = true;
    const mine = earlyEvents.filter((e) => e.runId === currentRun.runId);
    earlyEvents = [];
    mine.forEach(onScenarioEvent);
  } catch (e) {
    alert(`Không chạy được kịch bản: ${e.message}`);
    setScenarioButtons(false);
  }
}

function setScenarioButtons(disabled) {
  document.querySelectorAll("[data-scenario]").forEach((b) => (b.disabled = disabled));
}

function onScenarioEvent(event) {
  if (!currentRun || event.runId !== currentRun.runId) {
    earlyEvents.push(event);
    if (earlyEvents.length > 50) earlyEvents.shift();
    return;
  }
  const log = $("sc-log");
  log.append(el("li", {}, event.message));
  log.scrollTop = log.scrollHeight;

  const s = event.stepResult;
  if (s) {
    const r = s.result;
    $("sc-bar").style.width = `${(event.step / event.totalSteps) * 100}%`;
    $("sc-rows").append(el("tr", { class: `new ${r.decision}` },
      el("td", {}, String(s.step)),
      el("td", {}, time(s.transaction.timestamp)),
      el("td", {}, cityName(s.transaction)),
      el("td", { class: "num" }, vnd.format(s.transaction.amount)),
      el("td", {}, badge(r.decision)),
      el("td", { class: "num" }, score(r)),
      el("td", {}, r.triggeredRule || ""),
      el("td", { class: "num" }, r.features ? String(r.features.so_giao_dich_5_phut) : "")));
  }
  if (event.finished) {
    const summary = $("sc-summary");
    summary.hidden = false;
    summary.textContent = event.summary;
    summary.className = `summary${event.summary.startsWith("Phát hiện") ? " detected" : ""}`;
    $("sc-bar").style.width = "100%";
    setScenarioButtons(false);
  }
}

// ---------- live feed ----------

let feedPaused = false;

$("feed-pause").addEventListener("click", () => {
  feedPaused = !feedPaused;
  $("feed-pause").textContent = feedPaused ? "Tiếp tục" : "Tạm dừng";
});
$("feed-clear").addEventListener("click", () => {
  $("feed-rows").replaceChildren();
  $("feed-empty").hidden = false;
});

function onLiveFeedBatch(events) {
  if (feedPaused) return;
  const hideOk = $("feed-hide-ok").checked;
  const fragment = document.createDocumentFragment();
  // newest first
  for (let i = events.length - 1; i >= 0; i--) {
    const { transaction: tx, result: r } = events[i];
    if (hideOk && r.decision === "CHO_QUA") continue;
    fragment.append(el("tr", { class: `new ${r.decision}` },
      el("td", {}, time(tx.timestamp)),
      el("td", {}, el("code", {}, tx.transactionId)),
      el("td", {}, tx.cardId),
      el("td", { class: "num" }, vnd.format(tx.amount)),
      el("td", {}, tx.merchant),
      el("td", {}, badge(r.decision)),
      el("td", { class: "num" }, score(r)),
      el("td", {}, r.triggeredRule || ""),
      el("td", { class: "num" }, String(r.latencyMs))));
  }
  const body = $("feed-rows");
  body.prepend(fragment);
  while (body.rows.length > MAX_FEED_ROWS) body.deleteRow(-1);
  $("feed-empty").hidden = body.rows.length > 0;
}

// ---------- WebSocket (STOMP) ----------

function connect() {
  const status = $("ws-status");
  const setStatus = (state, label) => {
    status.dataset.state = state;
    status.querySelector(".label").textContent = label;
  };
  const client = new StompJs.Client({
    brokerURL: `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/live-feed`,
    reconnectDelay: 2000,
  });
  client.onConnect = () => {
    setStatus("connected", "Đã kết nối real-time");
    client.subscribe("/topic/live-feed", (m) => onLiveFeedBatch(JSON.parse(m.body)));
    client.subscribe("/topic/scenario", (m) => onScenarioEvent(JSON.parse(m.body)));
  };
  client.onWebSocketClose = () => setStatus("disconnected", "Mất kết nối, đang thử lại...");
  client.activate();
}

// ---------- start ----------

$("dashboard-link").href = `${location.protocol}//${location.hostname}:8081/`;

loadCatalog().catch((e) => alert(`Không tải được danh sách thẻ: ${e.message}`));
connect();
refreshStats();
setInterval(refreshStats, 1000);

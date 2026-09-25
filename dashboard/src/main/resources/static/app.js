// Fraud dashboard: first paint from GET /api/metrics, then one snapshot per second over STOMP.
"use strict";

const $ = (id) => document.getElementById(id);
const nf = new Intl.NumberFormat("vi-VN");
const pct = (x) => `${(x * 100).toFixed(x > 0 && x < 0.01 ? 2 : 1)}%`;
const hhmmss = (epochSecond) => new Date(epochSecond * 1000).toLocaleTimeString("vi-VN", { hour12: false });
const RATE_WINDOW = 10; // seconds, for the smoothed block / review rates

$("simulator-link").href = `${location.protocol}//${location.hostname}:8080/`;

// ---------- charts ----------

const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
let charts = {};

function baseOptions(yTitle, yFormat) {
  return {
    animation: false,
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: "index", intersect: false },
    elements: { point: { radius: 0, hoverRadius: 4, hitRadius: 8 }, line: { borderWidth: 2, tension: 0.25 } },
    scales: {
      x: {
        grid: { display: false },
        border: { color: css("--axis") },
        ticks: { color: css("--text-muted"), maxTicksLimit: 6, maxRotation: 0 },
      },
      y: {
        beginAtZero: true,
        grid: { color: css("--grid") },
        border: { display: false },
        title: { display: true, text: yTitle, color: css("--text-muted") },
        ticks: { color: css("--text-muted"), maxTicksLimit: 5, callback: yFormat },
      },
    },
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: css("--surface-1"),
        borderColor: css("--axis"),
        borderWidth: 1,
        titleColor: css("--text-primary"),
        bodyColor: css("--text-secondary"),
        padding: 10,
        boxPadding: 4,
        usePointStyle: true,
      },
    },
  };
}

function legendOn(options) {
  options.plugins.legend = {
    display: true,
    position: "top",
    align: "end",
    labels: { color: css("--text-secondary"), usePointStyle: true, pointStyle: "line", boxWidth: 18 },
  };
  return options;
}

function line(label, color, extra = {}) {
  return { label, data: [], borderColor: color, backgroundColor: color, spanGaps: false, ...extra };
}

function buildCharts() {
  Object.values(charts).forEach((c) => c.destroy());

  const tpsOptions = baseOptions("giao dịch/s", (v) => nf.format(v));
  tpsOptions.plugins.tooltip.callbacks = { label: (c) => ` ${nf.format(c.parsed.y)} giao dịch` };
  charts.tps = new Chart($("chart-tps"), {
    type: "line",
    data: { labels: [], datasets: [line("Giao dịch/giây", css("--series-1"))] },
    options: tpsOptions,
  });

  const rateOptions = legendOn(baseOptions("% giao dịch", (v) => `${v}%`));
  rateOptions.plugins.tooltip.callbacks = { label: (c) => ` ${c.dataset.label}: ${c.parsed.y.toFixed(2)}%` };
  charts.rate = new Chart($("chart-rate"), {
    type: "line",
    data: {
      labels: [],
      datasets: [
        line("CHAN", css("--status-critical")),
        line("XEM_XET", css("--status-warning"), { borderDash: [6, 4] }), // dash = second cue besides color
      ],
    },
    options: rateOptions,
  });

  const latOptions = legendOn(baseOptions("ms", (v) => `${v}`));
  latOptions.plugins.tooltip.callbacks = { label: (c) => ` ${c.dataset.label}: ${c.parsed.y} ms` };
  charts.latency = new Chart($("chart-latency"), {
    type: "line",
    data: {
      labels: [],
      datasets: [line("p50", css("--series-1")), line("p99", css("--series-2"), { borderDash: [6, 4] })],
    },
    options: latOptions,
  });
}

// ---------- rendering ----------

let lastSnapshot = null;

function render(s) {
  lastSnapshot = s;
  const h = s.lastHour;

  $("kpi-tps").textContent = nf.format(Math.round(s.currentTps));
  $("kpi-total").textContent = nf.format(h.total);
  $("kpi-total-sub").textContent = `${nf.format(h.choQua)} CHO_QUA · ${nf.format(h.xemXet)} XEM_XET · ${nf.format(h.chan)} CHAN`;
  $("kpi-block").textContent = h.total ? pct(h.chan / h.total) : "–";
  $("kpi-review").textContent = h.total ? `XEM_XET ${pct(h.xemXet / h.total)}` : " ";
  $("kpi-p99").textContent = s.lastMinute.p99 == null ? "–" : `${s.lastMinute.p99} ms`;
  $("kpi-latency-sub").textContent = s.lastMinute.p50 == null ? "không có giao dịch"
    : `p50 ${s.lastMinute.p50} ms · max ${s.lastMinute.max} ms`;

  const labels = s.series.map((p) => hhmmss(p.epochSecond));

  charts.tps.data.labels = labels;
  charts.tps.data.datasets[0].data = s.series.map((p) => p.total);

  // rolling window: a single second with 2 transactions would otherwise jump between 0% and 50%
  const block = [];
  const review = [];
  for (let i = 0; i < s.series.length; i++) {
    let total = 0, chan = 0, xem = 0;
    for (let j = Math.max(0, i - RATE_WINDOW + 1); j <= i; j++) {
      total += s.series[j].total;
      chan += s.series[j].chan;
      xem += s.series[j].xemXet;
    }
    block.push(total ? (chan / total) * 100 : null);
    review.push(total ? (xem / total) * 100 : null);
  }
  charts.rate.data.labels = labels;
  charts.rate.data.datasets[0].data = block;
  charts.rate.data.datasets[1].data = review;

  charts.latency.data.labels = labels;
  charts.latency.data.datasets[0].data = s.series.map((p) => p.p50);
  charts.latency.data.datasets[1].data = s.series.map((p) => p.p99);

  Object.values(charts).forEach((c) => c.update("none"));
  renderTop(s.topRisky);
}

function cell(text, cls) {
  const td = document.createElement("td");
  if (cls) td.className = cls;
  td.textContent = text;
  return td;
}

function renderTop(rows) {
  const body = $("top-rows");
  body.replaceChildren(...rows.map((r) => {
    const tr = document.createElement("tr");
    const badge = document.createElement("span");
    badge.className = `badge ${r.decision}`;
    badge.textContent = r.decision;
    const decision = document.createElement("td");
    decision.append(badge);
    tr.append(
      cell(new Date(r.decidedAt).toLocaleTimeString("vi-VN", { hour12: false })),
      cell(r.transactionId),
      cell(r.cardId),
      cell(nf.format(r.amount), "num"),
      cell(r.merchant),
      decision,
      cell(r.risk.toFixed(2), "num"),
      cell(r.triggeredRule || (r.riskScore != null ? "model ML" : "")),
    );
    return tr;
  }));
  $("top-empty").hidden = rows.length > 0;
}

// ---------- data sources ----------

function connect() {
  const status = $("ws-status");
  const setStatus = (state, label) => {
    status.dataset.state = state;
    status.querySelector(".label").textContent = label;
  };
  const client = new StompJs.Client({
    brokerURL: `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/metrics`,
    reconnectDelay: 2000,
  });
  client.onConnect = () => {
    setStatus("connected", "Đã kết nối real-time");
    client.subscribe("/topic/metrics", (m) => render(JSON.parse(m.body)));
  };
  client.onWebSocketClose = () => setStatus("disconnected", "Mất kết nối, đang thử lại...");
  client.activate();
}

// dark/light switch: rebuild charts with the other mode's colors
window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
  buildCharts();
  if (lastSnapshot) render(lastSnapshot);
});

buildCharts();
fetch("/api/metrics").then((r) => r.json()).then(render).catch(() => {});
connect();

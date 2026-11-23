"use strict";
const $ = (id) => document.getElementById(id);
const H = { "x-hubuddy-local": "1", "content-type": "application/json" };
const post = (path, body) => fetch(path, { method: "POST", headers: H, body: JSON.stringify(body || {}) }).then(r => r.json().then(j => { if (!r.ok) throw new Error(j.error || r.status); return j; }));
const get = (path) => fetch(path, { cache: "no-store" }).then(r => r.json());
const text = (el, s) => { el.textContent = s; return el; };
function el(tag, cls, s) { const e = document.createElement(tag); if (cls) e.className = cls; if (s !== undefined) e.textContent = s; return e; }
const ago = (t) => !t ? "never" : (s => s < 60 ? "just now" : s < 3600 ? Math.floor(s / 60) + " min ago" : s < 86400 ? Math.floor(s / 3600) + " h ago" : Math.floor(s / 86400) + " d ago")(Date.now() / 1000 - t / 1000);
const size = (b) => b > 1 << 30 ? (b / (1 << 30)).toFixed(1) + " GB" : b > 1 << 20 ? (b / (1 << 20)).toFixed(1) + " MB" : Math.ceil(b / 1024) + " KB";
let timer = null;

function drawQr(modules, quiet) {
  const c = $("qr"), n = modules.length + quiet * 2, scale = Math.max(1, Math.floor(c.width / n));
  c.width = c.height = n * scale; const g = c.getContext("2d");
  g.fillStyle = "#fff"; g.fillRect(0, 0, c.width, c.height); g.fillStyle = "#000";
  modules.forEach((row, y) => row.forEach((v, x) => { if (v) g.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale); }));
}
function countdown(expires, el, label) {
  clearInterval(timer);
  const tick = () => { const s = Math.max(0, Math.round((expires - Date.now()) / 1000)); text(el, `${label} ${s} s`); if (s <= 0) { clearInterval(timer); refresh(); } };
  tick(); timer = setInterval(tick, 1000);
}
async function refresh() {
  const s = await get("/local/state");
  text($("title"), "Hubuddy · " + s.name); text($("status"), `v${s.version} · port ${s.port} · ${s.discovery ? "discoverable" : "not discoverable (type the address on the phone)"}`);
  text($("root"), s.root);
  const showing = s.pairing.qr || s.pairing.pin; $("btn-cancel").hidden = !showing;
  if (!s.pairing.qr) $("qr-box").hidden = true; if (!s.pairing.pin) $("pin-box").hidden = true;
  const ph = $("phones"); ph.replaceChildren();
  if (!s.phones.length) ph.append(el("p", "dim", "No phones paired yet."));
  s.phones.forEach(p => {
    const r = el("div", "row"), l = el("div"); l.append(el("div", "", p.name));
    const st = p.status || {}; const bits = [`seen ${ago(p.last_seen)}`]; if (st.battery !== undefined) bits.push(`battery ${st.battery}%${st.charging ? " (charging)" : ""}`); if (st.network) bits.push(st.network);
    l.append(el("div", "dim", bits.join(" · "))); const b = el("button", "alt", "Forget"); b.onclick = async () => { await post("/local/forget", { id: p.id }); refresh(); };
    r.append(l, b); ph.append(r);
  });
  const tp = $("to-phone"); tp.replaceChildren(); s.toPhone.forEach(f => { const r = el("div", "row"); r.append(el("span", "", `${f.name} · ${size(f.size)}`)); const b = el("button", "alt", "Remove"); b.onclick = async () => { await post("/local/files/remove", { name: f.name }); refresh(); }; r.append(b); tp.append(r); });
  const rc = $("received"); rc.replaceChildren(); if (!s.received.length) rc.append(el("p", "dim", "Nothing yet.")); s.received.forEach(f => { const r = el("div", "row"); r.append(el("span", "", f.name), el("span", "dim", size(f.size))); rc.append(r); });
  const info = $("info"); info.replaceChildren();
  [["Computer id", s.hostId.slice(0, 8) + "…"], ["Addresses", s.addresses.join(", ") || "none found"], ["Ports", `TCP ${s.port}, UDP discovery 41236`]].forEach(([a, b]) => { const r = el("div", "row"); r.append(el("span", "dim", a), el("span", "", b)); info.append(r); });
  const a = await get("/local/activity"); const ac = $("activity"); ac.replaceChildren();
  a.events.slice(0, 40).forEach(e => { const r = el("div", "row"); const { t, event, ...rest } = e; r.append(el("span", "", `${new Date(t * 1000).toLocaleString()} · ${event.replace(/_/g, " ")}`), el("span", "dim", Object.entries(rest).map(([k, v]) => `${k}: ${v}`).join(" · "))); ac.append(r); });
}
$("btn-qr").onclick = async () => { const j = await post("/local/invite"); $("pin-box").hidden = true; $("qr-box").hidden = false; drawQr(j.modules, j.quiet); countdown(j.expires, $("qr-note"), "Scan with Hubuddy → Settings → Scan QR. Expires in"); refresh(); };
$("btn-pin").onclick = async () => { const j = await post("/local/pin"); $("qr-box").hidden = true; $("pin-box").hidden = false; text($("pin"), j.pin); text($("compare"), j.compare); countdown(j.expires, $("pin-note"), "On the phone: Find PCs → this PC → check the code → enter the PIN. Expires in"); refresh(); };
$("btn-cancel").onclick = async () => { await post("/local/pairing/cancel"); clearInterval(timer); refresh(); };
$("btn-forget-all").onclick = async () => { if (confirm("Forget every paired phone? Each one must pair again.")) { await post("/local/forget-all", { confirm: "forget-all" }); refresh(); } };
$("btn-send").onclick = async () => { try { await post("/local/send", { kind: $("send-kind").value, text: $("send-text").value }); $("send-text").value = ""; text($("send-note"), "Sent. It shows on the phone while the app is open."); } catch (e) { text($("send-note"), "Could not send: " + e.message); } };
$("file-in").onchange = async (ev) => { const f = ev.target.files[0]; if (!f) return; const r = await fetch("/local/files/upload?name=" + encodeURIComponent(f.name), { method: "PUT", headers: { "x-hubuddy-local": "1" }, body: f }); if (!r.ok) alert("Upload failed: " + (await r.json()).error); ev.target.value = ""; refresh(); };
refresh(); setInterval(refresh, 5000);

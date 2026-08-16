import { createServer } from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { isAllowedMessage } from './protocol.js';

const port = Number(process.env.PORT || 8080);
const clients = new Map();
const codes = new Map();
const authAttempts = new Map();
const maxMessageBytes = 8 * 1024 * 1024;

const server = createServer((req, res) => {
  if (req.url === '/health') { res.writeHead(200, {'content-type':'application/json'}); return res.end('{"ok":true}'); }
  res.writeHead(404); res.end();
});
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: maxMessageBytes });

function safeEqual(a, b) {
  const aa = Buffer.from(String(a)); const bb = Buffer.from(String(b));
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}
function send(ws, payload) { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload)); }
function reject(ws, message) { send(ws, {type:'error', message}); ws.close(1008, message); }

wss.on('connection', ws => {
  ws.id = randomUUID(); ws.isAlive = true; ws.auth = null; ws.remoteAddress = ws._socket.remoteAddress || 'unknown';
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', raw => {
    let msg; try { msg = JSON.parse(raw.toString()); } catch { return reject(ws, 'Invalid JSON'); }
    if (!ws.auth) {
      const now = Date.now();
      const recent = (authAttempts.get(ws.remoteAddress) || []).filter(time => now - time < 60_000);
      if (recent.length >= 12) return reject(ws, 'Too many pairing attempts');
      recent.push(now); authAttempts.set(ws.remoteAddress, recent);
      if (msg.type !== 'authenticate') return reject(ws, 'Authenticate first');
      const code = String(msg.pairingCode || '');
      if (!/^\d{6}$/.test(code)) return reject(ws, 'Invalid pairing code');
      if (msg.role === 'desktop') {
        const secret = String(msg.deviceSecret || '');
        if (secret.length < 32) return reject(ws, 'Weak device secret');
        const previous = codes.get(code);
        const sameOwner = previous && safeEqual(previous.secret, secret);
        const previousDesktop = previous ? clients.get(`${previous.room}:desktop`) : null;
        if (previous && !sameOwner && previousDesktop?.readyState === WebSocket.OPEN) return reject(ws, 'Pairing code already in use');
        if (previous && !sameOwner) {
          const staleMobile = clients.get(`${previous.room}:mobile`);
          if (staleMobile?.readyState === WebSocket.OPEN) staleMobile.close(4001, 'Pairing code refreshed');
        }
        const room = sameOwner ? previous.room : randomUUID(); codes.set(code, {room, secret, expires:Date.now()+24*60*60_000});
        const oldDesktop = clients.get(`${room}:desktop`);
        if (oldDesktop && oldDesktop !== ws && oldDesktop.readyState === WebSocket.OPEN) oldDesktop.close(4002, 'Desktop reconnected');
        ws.auth = {role:'desktop', room}; clients.set(`${room}:desktop`, ws); send(ws, {type:'authenticated', role:'desktop', room});
        send(clients.get(`${room}:mobile`), {type:'presence', desktopConnected:true});
        send(ws, {type:'presence', mobileConnected:clients.get(`${room}:mobile`)?.readyState === WebSocket.OPEN}); return;
      }
      if (msg.role === 'mobile') {
        const pair = codes.get(code); if (!pair || pair.expires < Date.now()) return reject(ws, 'Pairing code expired');
        const desktop = clients.get(`${pair.room}:desktop`);
        if (!desktop || desktop.readyState !== WebSocket.OPEN) return reject(ws, 'Fusion connector is offline');
        const oldMobile = clients.get(`${pair.room}:mobile`);
        if (oldMobile && oldMobile !== ws && oldMobile.readyState === WebSocket.OPEN) oldMobile.close(4003, 'Mobile reconnected');
        ws.auth = {role:'mobile', room:pair.room}; clients.set(`${pair.room}:mobile`, ws); send(ws, {type:'authenticated', role:'mobile'});
        send(ws, {type:'presence', desktopConnected:clients.get(`${pair.room}:desktop`)?.readyState === WebSocket.OPEN});
        send(clients.get(`${pair.room}:desktop`), {type:'presence', mobileConnected:true}); return;
      }
      return reject(ws, 'Invalid role');
    }
    const destination = ws.auth.role === 'mobile' ? 'desktop' : 'mobile';
    if (!isAllowedMessage(msg)) return send(ws, {type:'error', message:'Unsupported message type'});
    send(clients.get(`${ws.auth.room}:${destination}`), msg);
  });
  ws.on('close', () => {
    if (!ws.auth) return;
    const key = `${ws.auth.room}:${ws.auth.role}`;
    if (clients.get(key) === ws) clients.delete(key);
    if (ws.auth.role === 'mobile') send(clients.get(`${ws.auth.room}:desktop`), {type:'presence', mobileConnected:false});
    if (ws.auth.role === 'desktop') send(clients.get(`${ws.auth.room}:mobile`), {type:'presence', desktopConnected:false});
  });
});

setInterval(() => {
  for (const [code, item] of codes) {
    if (clients.get(`${item.room}:desktop`)?.readyState === WebSocket.OPEN) item.expires = Date.now()+24*60*60_000;
    else if (item.expires < Date.now()) codes.delete(code);
  }
  for (const [address, times] of authAttempts) {
    const recent = times.filter(time => Date.now() - time < 60_000);
    if (recent.length) authAttempts.set(address, recent); else authAttempts.delete(address);
  }
  for (const ws of wss.clients) { if (!ws.isAlive) ws.terminate(); else { ws.isAlive=false; ws.ping(); } }
}, 30_000).unref();

server.listen(port, '0.0.0.0', () => console.log(`Volumind relay listening on ${port}`));

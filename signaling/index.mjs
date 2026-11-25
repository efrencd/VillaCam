import { DurableObject } from 'cloudflare:workers';

const encoder = new TextEncoder();
const json = (data, status = 200) => Response.json(data, {status, headers:{'Cache-Control':'no-store'}});
const hex = bytes => Array.from(new Uint8Array(bytes), n => n.toString(16).padStart(2, '0')).join('');
const uuidPattern = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/;
const roomPattern = /^(?:[a-f0-9-]{36}|[a-f0-9]{64})$/;
const cameraPattern = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const tokenPattern = /^(publisher|viewer)\.(\d{10})\.(?:([a-f0-9]{64})\.)?([a-f0-9]{64})$/;

async function key(secret) {
    return crypto.subtle.importKey('raw', encoder.encode(secret), {name:'HMAC',hash:'SHA-256'}, false, ['sign','verify']);
}

async function issue(signingKey, room, role, expiry, owner = "") {
    const value = role + '.' + String(expiry).padStart(10, '0') + (owner ? '.' + owner : '');
    return value + '.' + hex(await crypto.subtle.sign('HMAC', signingKey, encoder.encode(room + ':' + value)));
}

async function roleFor(signingKey, room, token) {
    const match = tokenPattern.exec(token ?? '');
    if (!match) return null;
    const expiry = Number(match[2]);
    const stableViewer = expiry === 0 && room.length === 64 && match[1] === 'viewer';
    if (!stableViewer && expiry <= Date.now() / 1000) return null;
    const signature = Uint8Array.from(match[4].match(/../g), value => parseInt(value, 16));
    const valid = await crypto.subtle.verify('HMAC', signingKey, signature, encoder.encode(room + ':' + token.slice(0, token.lastIndexOf('.'))));
    return valid ? match[1] : null;
}

async function iceServers(env) {
    const stun = [{urls:['stun:stun.cloudflare.com:3478']}];
    if (!env.TURN_KEY_ID || !env.TURN_API_TOKEN) return stun;
    try {
        const response = await fetch('https://rtc.live.cloudflare.com/v1/turn/keys/' +
            encodeURIComponent(env.TURN_KEY_ID) + '/credentials/generate-ice-servers', {
            method:'POST', headers:{Authorization:'Bearer ' + env.TURN_API_TOKEN, 'Content-Type':'application/json'},
            body:JSON.stringify({ttl:3600}), signal:AbortSignal.timeout(3500)
        });
        if (!response.ok) return stun;
        const body = await response.json();
        return body.iceServers.map(server => ({
            ...server, urls: (Array.isArray(server.urls) ? server.urls : [server.urls]).filter(url => !/:53(?:\?|$)/.test(url))
        }));
    } catch { return stun; }
}

function publicRoom(signingKey, camera) {
    return crypto.subtle.sign('HMAC', signingKey, encoder.encode('public-camera:' + camera)).then(hex);
}

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        if (!env.SIGNING_KEY) return json({error:'Server setup required'}, 503);
        const signingKey = await key(env.SIGNING_KEY);
        if (url.pathname === '/api/view' && request.method === 'GET') {
            const camera = url.searchParams.get('name') ?? '';
            if (!cameraPattern.test(camera)) return json({error:'Invalid camera name'}, 400);
            const room = await publicRoom(signingKey, camera);
            return json({room, viewer:await issue(signingKey, room, 'viewer', 0)});
        }
        if (url.pathname === '/api/session' && request.method === 'POST') {
            const camera = request.headers.get('X-VillaCam-Camera');
            const device = request.headers.get('X-VillaCam-Device');
            const publicName = request.headers.get('X-VillaCam-Mode') === 'name';
            const named = camera !== null || device !== null;
            if ((named || publicName) && (!cameraPattern.test(camera ?? '') || !uuidPattern.test(device ?? '')))
                return json({error:'Invalid camera identity'}, 400);
            // Older versions can still create temporary rooms. Named rooms are scoped
            // to an installation, so typing another camera's name grants no access.
            const room = publicName ? await publicRoom(signingKey, camera) : named
                ? hex(await crypto.subtle.sign('HMAC', signingKey, encoder.encode('camera:' + camera + ':' + device)))
                : crypto.randomUUID();
            const expiry = Math.floor(Date.now() / 1000) + 86400;
            const owner = publicName
                ? hex(await crypto.subtle.sign('HMAC', signingKey, encoder.encode('owner:' + room + ':' + device)))
                : "";
            const [publisher, viewer] = await Promise.all([
                issue(signingKey, room, 'publisher', expiry, owner), issue(signingKey, room, 'viewer', named ? 0 : expiry)
            ]);
            return json({room, publisher, viewer});
        }
        const room = url.searchParams.get('room') ?? '';
        if (!roomPattern.test(room)) return json({error:'Invalid room'}, 400);
        if (url.pathname === '/api/ice' && request.method === 'GET') {
            const token = request.headers.get('Authorization')?.replace(/^Bearer /,'');
            if (!await roleFor(signingKey, room, token)) return json({error:'Unauthorized'}, 401);
            return json({iceServers:await iceServers(env)});
        }
        if (url.pathname === '/api/socket' && request.method === 'GET') {
            if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return json({error:'WebSocket required'}, 426);
            const [protocol, token] = (request.headers.get('Sec-WebSocket-Protocol') ?? '').split(',').map(p => p.trim());
            const role = protocol === 'villacam' && await roleFor(signingKey, room, token);
            if (!role) return json({error:'Unauthorized'}, 401);
            const headers = new Headers(request.headers);
            headers.set('X-VillaCam-Role', role);
            headers.set('X-VillaCam-Expiry', token.split('.')[1]);
            headers.set('X-VillaCam-Owner', token.split('.').length === 4 ? token.split('.')[2] : '');
            headers.delete('Sec-WebSocket-Protocol');
            return env.ROOMS.getByName(room).fetch(new Request(request, {headers}));
        }
        return json({error:'Not found'}, 404);
    }
};

export class Room extends DurableObject {
    constructor(ctx, env) {
        super(ctx, env);
        ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping','pong'));
    }

    fetch(request) {
        const role = request.headers.get('X-VillaCam-Role');
        const expiry = Number(request.headers.get('X-VillaCam-Expiry'));
        const owner = request.headers.get('X-VillaCam-Owner');
        const currentPublisher = role === 'publisher' && this.active('publisher');
        if (currentPublisher && currentPublisher.deserializeAttachment().owner !== owner)
            return json({error:'Camera name already in use'}, 409);
        const pair = new WebSocketPair();
        const [client, server] = Object.values(pair);
        for (const old of this.ctx.getWebSockets(role)) {
            old.serializeAttachment({replaced:true});
            old.close(4001, 'Replaced');
        }
        this.ctx.acceptWebSocket(server, [role]);
        server.serializeAttachment({role,expiry,owner});
        const other = this.active(role === 'publisher' ? 'viewer' : 'publisher');
        if (other) {
            server.send('{"type":"ready"}');
            other.send('{"type":"ready"}');
        }
        return new Response(null, {status:101, webSocket:client, headers:{'Sec-WebSocket-Protocol':'villacam'}});
    }

    active(role) {
        return this.ctx.getWebSockets(role).find(socket => {
            const session = socket.deserializeAttachment();
            return socket.readyState === 1 && !session?.replaced && (session?.expiry === 0 || session?.expiry > Date.now() / 1000);
        });
    }

    webSocketMessage(socket, raw) {
        const session = socket.deserializeAttachment();
        if (session?.replaced) return;
        if (!session || (session.expiry !== 0 && session.expiry <= Date.now() / 1000)) {
            socket.close(4003, 'Expired');
            return;
        }
        if (typeof raw !== 'string' || raw.length > 65536) return;
        try {
            const {type,id,sdp} = JSON.parse(raw);
            if (type !== (session.role === 'publisher' ? 'answer' : 'offer') ||
                !uuidPattern.test(id ?? '') || typeof sdp !== 'string' || sdp.length > 60000) return;
            this.active(session.role === 'publisher' ? 'viewer' : 'publisher')?.send(JSON.stringify({type,id,sdp}));
        } catch { /* Ignore invalid messages. */ }
    }

    webSocketClose(socket) {
        const session = socket.deserializeAttachment();
        if (session?.role && !session.replaced)
            this.active(session.role === 'publisher' ? 'viewer' : 'publisher')?.send('{"type":"offline"}');
    }
}

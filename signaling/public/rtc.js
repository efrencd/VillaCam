'use strict';
const [privateRoom, privateToken] = location.hash.slice(1).split('/');
const cameraName = new URLSearchParams(location.search).get('view');
const statusText = document.querySelector('#status');
const video = document.querySelector('video');
const sound = document.querySelector('#sound');
const spanish = navigator.language.startsWith('es');
const text = (en, es) => spanish ? es : en;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
sound.textContent = text('Enable sound', 'Activar sonido');
sound.onclick = async () => {
    video.muted = false;
    try { await video.play(); sound.hidden = true; }
    catch { video.muted = true; }
};

async function play(stream) {
    video.srcObject = stream;
    video.muted = false;
    try { await video.play(); }
    catch {
        if (video.srcObject !== stream) return;
        video.muted = true;
        sound.hidden = false;
        await video.play().catch(() => {});
    }
}

function gather(pc) {
    if (pc.iceGatheringState === 'complete') return Promise.resolve();
    return new Promise(resolve => {
        const finish = () => {
            clearTimeout(timer);
            pc.removeEventListener('icegatheringstatechange', changed);
            resolve();
        };
        const changed = () => { if (pc.iceGatheringState === 'complete') finish(); };
        const timer = setTimeout(finish, 5000);
        pc.addEventListener('icegatheringstatechange', changed);
        changed();
    });
}

async function watch() {
    let room = privateRoom, token = privateToken;
    if ((!room || !token) && cameraName) {
        if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(cameraName)) {
            statusText.textContent = text('Invalid camera name.', 'Nombre de cámara no válido.');
            return;
        }
        while (!room || !token) {
            try {
                const response = await fetch('/api/view?name=' + encodeURIComponent(cameraName), {
                    cache:'no-store', signal:AbortSignal.timeout(5000)
                });
                if (!response.ok) throw Error('Camera lookup failed');
                const config = await response.json();
                room = config.room;
                token = config.viewer;
            } catch {
                statusText.textContent = text('Connecting to signaling…', 'Conectando con el servidor…');
                await sleep(1500);
            }
        }
    }
    if (!room || !token) {
        statusText.textContent = text('Open the camera link from VillaCam.', 'Abre el enlace de la cámara desde VillaCam.');
        return;
    }
    let replaced = false;
    for (;;) {
        statusText.textContent = text('Waiting for cameraâ€¦', 'Esperando a la cÃ¡maraâ€¦');
        await new Promise(resolve => {
            const address = new URL('/api/socket?room=' + room, location.href);
            address.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
            const socket = new WebSocket(address, ['villacam', token]);
            let pc, offerId, disconnected, negotiateTimeout, watchdog, lastMessage = Date.now();
            const heartbeat = setInterval(() => {
                if (Date.now() - lastMessage > 14000) socket.close();
                else if (socket.readyState === 1) socket.send('ping');
            }, 4500);
            const clear = () => {
                if (pc) pc.close();
                pc = null;
                offerId = null;
                clearTimeout(disconnected);
                clearTimeout(negotiateTimeout);
                video.srcObject = null;
                sound.hidden = true;
            };
            async function offer() {
                clear();
                const id = crypto.randomUUID();
                offerId = id;
                negotiateTimeout = setTimeout(() => socket.close(), 20000);
                const response = await fetch('/api/ice?room=' + room, {
                    headers:{Authorization:'Bearer ' + token}, cache:'no-store', signal:AbortSignal.timeout(6500)
                });
                if (!response.ok) throw Error('ICE configuration unavailable');
                const config = await response.json();
                if (offerId !== id || socket.readyState !== 1) return;
                const current = pc = new RTCPeerConnection(config);
                current.addTransceiver('video',{direction:'recvonly'});
                current.addTransceiver('audio',{direction:'recvonly'});
                current.ontrack = event => {
                    if (pc === current && event.streams[0] && video.srcObject !== event.streams[0]) void play(event.streams[0]);
                };
                current.onconnectionstatechange = () => {
                    if (pc !== current) return;
                    clearTimeout(disconnected);
                    switch (current.connectionState) {
                        case 'connected':
                            clearTimeout(negotiateTimeout);
                            statusText.textContent = '';
                            break;
                        case 'failed': socket.close(); break;
                        case 'disconnected': disconnected = setTimeout(() => socket.close(), 3000); break;
                    }
                };
                await current.setLocalDescription(await current.createOffer());
                await gather(current);
                if (pc === current && socket.readyState === 1)
                    socket.send(JSON.stringify({type:'offer',id,sdp:current.localDescription.sdp}));
            }
            socket.onmessage = event => {
                lastMessage = Date.now();
                if (event.data === 'pong') return;
                const message = JSON.parse(event.data);
                if (message.type === 'ready') void offer().catch(() => { if (socket.readyState === 1) socket.close(); });
                if (message.type === 'offline') {
                    clear();
                    statusText.textContent = text('Waiting for cameraâ€¦', 'Esperando a la cÃ¡maraâ€¦');
                }
                if (message.type === 'answer' && offerId === message.id && pc)
                    void pc.setRemoteDescription({type:'answer',sdp:message.sdp}).catch(() => socket.close());
            };
            socket.onclose = event => {
                replaced = event.code === 4001 || event.code === 4003;
                clearInterval(heartbeat);
                clearTimeout(watchdog);
                clear();
                resolve();
            };
            watchdog = setTimeout(() => { if (socket.readyState === 0) socket.close(); }, 10000);
        });
        if (replaced) {
            statusText.textContent = text('Link expired or opened in another viewer.', 'El enlace caducó o se abrió en otro receptor.');
            return;
        }
        await sleep(1500);
    }
}
void watch();

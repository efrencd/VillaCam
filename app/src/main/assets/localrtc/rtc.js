'use strict';
const spanish = navigator.language.startsWith('es');
const statusText = document.querySelector('#status');
const video = document.querySelector('video');
const sound = document.querySelector('#sound');
sound.textContent = spanish ? 'Activar sonido' : 'Enable sound';
sound.onclick = async () => {
    const stream = video.srcObject;
    video.muted = false;
    try {
        await video.play();
        if (video.srcObject === stream) sound.hidden = true;
    } catch (error) {
        if (video.srcObject !== stream) return;
        video.muted = true;
        await video.play().catch(() => {});
    }
};
async function playStream(stream) {
    video.srcObject = stream;
    video.muted = false;
    try { await video.play(); }
    catch (error) {
        if (video.srcObject !== stream) return;
        video.muted = true;
        sound.hidden = false;
        await video.play().catch(() => {});
    }
}

const label = (en, es) => statusText.textContent = spanish ? es : en;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const peer = () => new RTCPeerConnection({iceServers: []});
async function api(path, data) {
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 1500);
    try {
        const response = await fetch(path, {
            method: data === undefined ? 'GET' : 'POST', signal: abort.signal, cache: 'no-store',
            headers: data === undefined ? undefined : {'Content-Type':'application/json'},
            body: data === undefined ? undefined : JSON.stringify(data)
        });
        if (!response.ok) throw Error('HTTP ' + response.status);
        return await response.json();
    } finally {
        clearTimeout(timer);
    }
}
async function gather(pc) {
    if (pc.iceGatheringState === 'complete') return;
    await new Promise(resolve => {
        const done = () => {
            clearTimeout(timer);
            pc.removeEventListener('icegatheringstatechange', changed);
            resolve();
        };
        const changed = () => { if (pc.iceGatheringState === 'complete') done(); };
        const timer = setTimeout(done, 2500);
        pc.addEventListener('icegatheringstatechange', changed);
        changed();
    });
}
async function receive() {
    let previousId;
    for (;;) {
        const pc = peer();
        let id;
        try {
            label('Connecting to camera…','Conectando con la cámara…');
            pc.addTransceiver('video',{direction:'recvonly'});
            pc.addTransceiver('audio',{direction:'recvonly'});
            pc.ontrack = event => {
                const stream = event.streams[0];
                if (stream && video.srcObject !== stream) void playStream(stream);
            };
            await pc.setLocalDescription(await pc.createOffer());
            await gather(pc);
            ({id} = await api('/offer', {sdp:pc.localDescription.sdp, previousId}));
            previousId = id;
            for (let attempt = 0; attempt < 24; attempt++) {
                const answer = await api('/answer?id=' + id);
                if (answer.sdp) { await pc.setRemoteDescription({type:'answer',sdp:answer.sdp}); break; }
                await sleep(250);
            }
            if (!pc.remoteDescription) throw Error('No answer');
            const deadline = Date.now() + 7000;
            let connectedOnce = false;
            let disconnectedAt = null;
            for (;;) {
                const state = pc.connectionState;
                const now = Date.now();
                if (state === 'connected') connectedOnce = true;
                if (state === 'disconnected') {
                    if (disconnectedAt === null) disconnectedAt = now;
                } else disconnectedAt = null;
                if (['failed','closed'].includes(state)
                    || (!connectedOnce && now > deadline)
                    || (disconnectedAt !== null && now - disconnectedAt >= 2000)) break;
                if (state === 'connected' && !video.paused) statusText.textContent = '';
                await api('/answer?id=' + id); // Keep the single viewer slot alive.
                await sleep(600);
            }
        } catch (error) { console.warn(error); }
        finally {
            pc.close();
            video.srcObject = null;
            sound.hidden = true;
        }
        label('Waiting for camera…','Esperando a la cámara…');
        await sleep(600);
    }
}
receive();

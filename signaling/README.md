# VillaCam signaling for Cloudflare Workers

The Worker serves the OBS receiver page. Each room uses a SQLite-backed Durable Object to exchange messages over hibernating WebSockets. Audio and video flow over WebRTC, either directly or through TURN.

1. Run the following commands inside this directory with Node.js and a Cloudflare account.

   ```sh
   npx wrangler login
   npx wrangler deploy
   npx wrangler secret put SIGNING_KEY
   ```

2. Generate a random SIGNING_KEY using `node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"` and keep it on the server. Anyone who can reach this deployment can create their own session. Each session has separate signed publisher and viewer credentials.
3. Optional for networks where direct WebRTC is blocked. Create a [Cloudflare TURN key](https://developers.cloudflare.com/realtime/turn/generate-credentials/) and set `TURN_KEY_ID` and `TURN_API_TOKEN` using `npx wrangler secret put`. The Worker issues temporary TURN credentials as each connection is negotiated. If TURN is unset or fails, the Worker supplies STUN only.
4. In VillaCam select **WebRTC → Over the Internet**. Enter the deployed HTTPS origin and a camera name. Start streaming and paste the generated link into an OBS Browser Source. The link uses only the camera name, such as `/?view=villacam2`. Anyone with that name can watch. A second phone with the same name cannot replace an active publisher.

For local server development, put a disposable SIGNING_KEY value into the ignored `.dev.vars` file and run `npx wrangler dev`. The Android app requires HTTPS for the remote server. Local HTTP is suitable for testing signaling in isolation.

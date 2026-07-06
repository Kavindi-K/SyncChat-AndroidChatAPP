import ws from 'k6/ws';
import { check } from 'k6';

export const options = {
    vus: 50,           // 50 concurrent WebSocket connections
    duration: '30s',   // Keep them open for 30 seconds
};

const BASE_URL = __ENV.BASE_URL || 'localhost:5228';
const AUTH_TOKEN = __ENV.AUTH_TOKEN;

export default function () {
    // SignalR negotiation and connection URL pattern
    const url = `ws://${BASE_URL}/hubs/chat?access_token=${AUTH_TOKEN}`;

    const res = ws.connect(url, null, function (socket) {
        socket.on('open', () => {
            console.log(`VU ${__VU}: connected`);
            
            // SignalR requires an initial handshake protocol message
            socket.send(JSON.stringify({ protocol: "json", version: 1 }) + '\x1e');
        });

        socket.on('message', (data) => {
            // Check if we received an empty object `{}` which indicates a successful handshake
            if (data === '{}') {
                console.log(`VU ${__VU}: Handshake successful`);
            }
        });

        socket.on('close', () => console.log(`VU ${__VU}: disconnected`));

        // Keep the connection open for 15 seconds
        socket.setTimeout(function () {
            console.log(`VU ${__VU}: keeping connection open...`);
        }, 15000); 
    });

    check(res, { 'status is 101 Switching Protocols': (r) => r && r.status === 101 });
}

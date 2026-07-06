import http from 'k6/http';
import { check, sleep } from 'k6';

// Test Configuration
export const options = {
    stages: [
        { duration: '15s', target: 20 }, // Ramp up to 20 users
        { duration: '30s', target: 20 }, // Stay at 20 users for 30 seconds
        { duration: '15s', target: 0 },  // Ramp down to 0 users
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'], // 95% of requests must complete below 500ms
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:5228';
const AUTH_TOKEN = __ENV.AUTH_TOKEN;
const TARGET_USER_ID = __ENV.TARGET_USER_ID;
const CONV_ID = __ENV.CONV_ID;

export default function () {
    const headers = {
        'Authorization': `Bearer ${AUTH_TOKEN}`,
        'Content-Type': 'application/json',
    };

    // 1. Fetch User Profile
    const profileRes = http.get(`${BASE_URL}/api/users/${TARGET_USER_ID}`, { headers });
    check(profileRes, {
        'Profile status is 200': (r) => r.status === 200,
    });
    
    sleep(1); // Think time

    // 2. Send a Message
    const payload = JSON.stringify({
        text: `Load test message from k6 VU-${__VU} ITER-${__ITER}`,
        mediaUrl: null
    });
    
    const msgRes = http.post(`${BASE_URL}/api/conversations/${CONV_ID}/messages`, payload, { headers });
    
    // We expect 201 Created or 429 Too Many Requests (Rate Limit)
    check(msgRes, {
        'Message sent (201)': (r) => r.status === 201,
        'Hit Rate Limit (429)': (r) => r.status === 429
    });

    sleep(1); // Think time
}

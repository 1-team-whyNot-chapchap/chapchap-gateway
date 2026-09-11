# Browser consultation connection

Status: development contract; existing Customer participant authorization remains authoritative. Gateway must be the only public ingress. Customer port must remain private.

1. Login via Auth, then POST an empty body to `/api/customer/consultations/ws-ticket` with `Authorization: Bearer <access token>`. Browser Origin must exactly match `CORS_ALLOW_ORIGIN`.
2. Response is `{"ticket":"<opaque>","expiresIn":30}` (actual lifetime may be shorter than 30 seconds). Never persist or log the ticket.
3. Open `/ws/customer/consultations` on the Gateway with protocols `v12.stomp` and `ticket.<opaque>`. Use WSS in deployment. No query credentials.
4. Send STOMP CONNECT; subscribe to `/topic/consultations/{consultationId}`, send to `/app/consultations/{consultationId}/messages`. Customer verifies participation for each SEND/SUBSCRIBE. Reconnect requires a fresh ticket; retrieve persisted history via REST.

```javascript
// Use in your STOMP client's async preparation hook before opening the socket.
const response = await fetch(gatewayOrigin + "/api/customer/consultations/ws-ticket", {
  method: "POST",
  headers: { Authorization: "Bearer " + accessToken },
});
if (!response.ok) throw new Error("Unable to authorize consultation connection");
const { ticket } = await response.json();
const socket = new WebSocket(
  gatewayOrigin.replace(/^http/, "ws") + "/ws/customer/consultations",
  ["v12.stomp", "ticket." + ticket],
);
// Supply socket through the STOMP library's webSocketFactory.
```

Gateway consumes the ticket once, revalidates access JWT, strips credentials/cookies/forged identity and sends only verified user ID/role plus the real STOMP protocol downstream.

Errors: 401 invalid/missing/expired/replayed credentials; 403 disallowed/missing Origin; 405 wrong ticket method; 429 store full; 400 non-WebSocket handshake/query parameters. Error bodies are empty, no secrets; ticket responses use Cache-Control: no-store.

Settings:

- Gateway `CORS_ALLOW_ORIGIN=http://localhost:5173` (comma-separated exact origins).
- Gateway `CUSTOMER_SERVICE_WS_URI=ws://localhost:8084`.
- Compose `COMPOSE_CUSTOMER_SERVICE_WS_URI=ws://customer-service:8084`.
- Customer `CUSTOMER_WEBSOCKET_ALLOWED_ORIGINS=http://localhost:5173`; keep aligned with Gateway, no wildcard.

The ticket store is bounded to 10,000 entries and one outstanding ticket per user. It is local to one Gateway process; restart loses tickets, multiple replicas require a shared atomic single-use store or explicit sticky routing. No production multi-replica claim. Never enable request-header logging for Authorization or Sec-WebSocket-Protocol. Session revocation/expiry after an established connection is separate from handshake authorization.

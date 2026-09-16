# ws

The single shared session WebSocket client (`/ws/session?token=...`) and its message
handling. One connection is opened after login and reused for the whole session,
including order-book subscribe/unsubscribe traffic.

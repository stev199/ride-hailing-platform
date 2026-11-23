// Sim bridge v0.1 skeleton (D6). Full topology engine (loss/latency/jitter/bandwidth,
// mobility, churn, clock skew) lands with mesh-core Stage 3 and is reused here.
// Contract: emulators connect over WS (ws://10.0.2.2:7300/node/<nodeId>); the bridge
// forwards envelopes between nodes according to the active topology, applying
// per-link loss/latency, and exposes /api/topology for scenario control + metrics.
const http = require("http");
const { WebSocketServer } = require("ws");

const TOPOLOGY = process.env.TOPOLOGY || "line-3";
const PORT = process.env.PORT || 7300;

// placeholder topology definition; replaced by mesh-core sim models at Stage 3
const TOPOLOGIES = {
  "line-3": { nodes: 4, links: [[0, 1, 0.0, 40], [1, 2, 0.05, 60], [2, 3, 0.05, 60]] },
  "line-2": { nodes: 3, links: [[0, 1, 0.0, 40], [1, 2, 0.05, 60]] },
  "line-1": { nodes: 2, links: [[0, 1, 0.0, 40]] },
};

const server = http.createServer((req, res) => {
  if (req.url === "/api/topology") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(JSON.stringify({ topology: TOPOLOGY, ...(TOPOLOGIES[TOPOLOGY] || {}) }));
  }
  res.writeHead(404); res.end();
});
const wss = new WebSocketServer({ server });
const nodes = new Map();

wss.on("connection", (ws, req) => {
  const nodeId = (req.url.split("/node/")[1] || "").trim();
  nodes.set(nodeId, ws);
  console.log(`node connected: ${nodeId} (${nodes.size} total)`);
  ws.on("message", (data) => {
    // v0.1: broadcast to all other nodes; Stage 3 applies topology (hops, loss, latency)
    for (const [id, peer] of nodes) {
      if (id !== nodeId && peer.readyState === 1) peer.send(data);
    }
  });
  ws.on("close", () => nodes.delete(nodeId));
});

server.listen(PORT, () => console.log(`sim bridge on :${PORT} topology=${TOPOLOGY}`));

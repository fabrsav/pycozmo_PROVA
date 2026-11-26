import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DigitalTwinRobot } from './lib/simulator.ts';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = Number(process.env.PORT || 3000);

const robot = new DigitalTwinRobot();

// 20 Hz simulation tick
setInterval(() => {
  robot.step(0.05, Date.now() / 1000);
}, 50);

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

app.get('/api/state', (_req, res) => {
  res.json(robot.snapshot());
});

app.get('/api/events', (_req, res) => {
  res.json(robot.getRecentEvents());
});

app.get('/events', (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.flushHeaders?.();

  for (const ev of robot.getRecentEvents().slice(-15)) {
    res.write(`data: ${JSON.stringify(ev)}\n\n`);
  }

  const unsubscribe = robot.subscribe((ev) => {
    res.write(`data: ${JSON.stringify(ev)}\n\n`);
  });

  const ping = setInterval(() => {
    res.write(': ping\n\n');
  }, 15000);

  req.on('close', () => {
    clearInterval(ping);
    unsubscribe();
  });
});

app.post('/api/ask', (req, res) => {
  const text = typeof req.body?.text === 'string' ? req.body.text : '';
  if (!text.trim()) {
    res.status(400).json({ ok: false, error: 'testo vuoto' });
    return;
  }
  const result = robot.ask(text);
  res.json(result);
});

app.post('/api/cmd', (req, res) => {
  const action = typeof req.body?.action === 'string' ? req.body.action : '';
  if (action === 'ask') {
    const text = typeof req.body?.text === 'string' ? req.body.text : '';
    if (!text.trim()) {
      res.status(400).json({ ok: false, error: 'testo vuoto' });
      return;
    }
    res.json(robot.ask(text));
    return;
  }
  if (action === 'pickup') {
    robot.pickup(true);
  } else if (action === 'release') {
    robot.pickup(false);
  } else if (action === 'cliff') {
    robot.dropOffTable();
  } else if (action === 'reset') {
    robot.resetCenter();
  } else if (action.startsWith('face_')) {
    const ok = robot.setFace(action.slice(5));
    if (!ok) {
      res.status(400).json({ ok: false, error: `espressione sconosciuta: ${action}` });
      return;
    }
  } else if (action.startsWith('speak_')) {
    robot.speak(action.slice(6));
  } else if (action === 'move') {
    robot.drive(Number(req.body?.vl || 0), Number(req.body?.vr || 0));
  } else {
    res.status(400).json({ ok: false, error: `azione sconosciuta: ${action}` });
    return;
  }
  res.json({ ok: true, action, state: robot.snapshot() });
});

app.get('/health', (_req, res) => {
  res.json({ ok: true, l1_ticks: robot.l1_ticks });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`[cozmo] Digital Twin attivo su http://0.0.0.0:${PORT}`);
});

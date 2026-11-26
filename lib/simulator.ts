export const WHEEL_TRACK_MM = 48.0;
export const WHEEL_SPEED_HARD_MAX = 200.0;
export const HEAD_ANGLE_MIN = (-25.0 * Math.PI) / 180.0;
export const HEAD_ANGLE_MAX = (44.5 * Math.PI) / 180.0;
export const LIFT_HEIGHT_MIN = 32.0;
export const LIFT_HEIGHT_MAX = 92.0;
export const TABLE_HALF_X_MM = 250.0;
export const TABLE_HALF_Y_MM = 200.0;

export type FaceExpression =
  | 'neutral'
  | 'happy'
  | 'question'
  | 'surprised'
  | 'sad'
  | 'sleepy'
  | 'angry';

export interface WorldObject {
  id: string;
  kind: 'face' | 'mug' | 'keys' | 'cube';
  label: string;
  x: number;
  y: number;
  size: number;
  confidence: number;
}

export interface RobotEvent {
  t: number;
  kind: string;
  [key: string]: unknown;
}

export interface GroundingResult {
  ok: boolean;
  referent: string | null;
  confidence: number;
  note: string;
}

export interface Plan {
  action: '' | 'goto' | 'look' | 'face' | 'speak';
  parameter: string;
  raw: string;
  understood: boolean;
}

export interface AskResult {
  ok: boolean;
  answer: string;
  plan: string;
  grounded: boolean;
  executed: boolean;
  referent: string | null;
}

export interface StateSnapshot {
  t: number;
  l1_ticks: number;
  pose: { x: number; y: number; theta: number };
  head: number;
  lift: number;
  battery_mv: number;
  cliff: number;
  picked_up: boolean;
  face: FaceExpression;
  mood: string;
  estop: boolean;
  estop_reason: string;
  nav_goal: { x: number; y: number } | null;
  dbg: {
    vl: number;
    vr: number;
    nav: boolean;
    intent: boolean;
    reason: string;
  };
  objects: WorldObject[];
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

function wrapAngle(a: number): number {
  let out = (a + Math.PI) % (2 * Math.PI);
  if (out < 0) out += 2 * Math.PI;
  return out - Math.PI;
}

export function groundUtterance(utterance: string, objects: WorldObject[]): GroundingResult {
  const u = utterance.toLowerCase();
  const vocab: Array<[string, WorldObject['kind']]> = [
    ['cubo', 'cube'],
    ['tazza', 'mug'],
    ['chiavi', 'keys'],
    ['persona', 'face'],
    ['faccia', 'face'],
    ['volto', 'face'],
  ];
  for (const [token, kind] of vocab) {
    if (u.includes(token)) {
      const found = objects.find((o) => o.kind === kind);
      if (found) {
        return { ok: true, referent: kind, confidence: 0.9, note: '' };
      }
      return { ok: false, referent: null, confidence: 0, note: `'${token}' citato ma non osservato` };
    }
  }
  return { ok: false, referent: null, confidence: 0, note: 'nessun referente riconosciuto' };
}

export function parsePlan(text: string): Plan {
  const raw = (text || '').trim();
  if (!raw) {
    return { action: '', parameter: '', raw, understood: false };
  }
  for (const line of raw.split('\n')) {
    const clean = line.trim().replace(/^[-*•\s]+/, '');
    if (clean.includes('|')) {
      const idx = clean.indexOf('|');
      const act = clean.slice(0, idx).trim().toLowerCase();
      const par = clean.slice(idx + 1).trim();
      if (act === 'goto' || act === 'look' || act === 'face' || act === 'speak') {
        return { action: act, parameter: par, raw, understood: true };
      }
    }
  }
  const low = raw.toLowerCase();
  const proseActions: Array<[string, Plan['action']]> = [
    ['vai', 'goto'],
    ['avvicin', 'goto'],
    ['raggiung', 'goto'],
    ['guarda', 'look'],
    ['gira', 'look'],
    ['orient', 'look'],
    ['sguardo', 'look'],
    ['saluta', 'speak'],
    ['ciao', 'speak'],
    ['parla', 'speak'],
    ['voce', 'speak'],
    ['sorridi', 'face'],
    ['felice', 'face'],
    ['curios', 'face'],
    ['triste', 'face'],
    ['dormi', 'face'],
    ['faccia', 'face'],
  ];
  for (const [kw, act] of proseActions) {
    if (low.includes(kw)) {
      let param = raw;
      if (act === 'face') {
        if (low.includes('felic') || low.includes('sorrid')) param = 'happy';
        else if (low.includes('curios') || low.includes('domand')) param = 'question';
        else if (low.includes('sorpres')) param = 'surprised';
        else if (low.includes('trist')) param = 'sad';
        else if (low.includes('sonn') || low.includes('dorm')) param = 'sleepy';
        else param = 'neutral';
      } else if (act === 'speak') {
        param = 'hello';
      }
      return { action: act, parameter: param, raw, understood: true };
    }
  }
  return { action: '', parameter: '', raw, understood: false };
}

export class DigitalTwinRobot {
  pose = { x: 0.0, y: 0.0, theta: 0.0 };
  head = 0.08;
  lift = LIFT_HEIGHT_MIN;
  vl = 0.0;
  vr = 0.0;
  battery_mv = 3980;
  picked_up = false;
  face: FaceExpression = 'neutral';
  estop = false;
  estop_reason = '';
  l1_ticks = 0;

  private moodValence = 0.25;
  private moodArousal = 0.3;
  private moodFear = 0.0;
  private moodCurious = 0.35;
  private prevCliff = false;
  private prevPicked = false;
  private prevMoodLabel = 'curious';
  private cliffEscapeUntil = -1;
  private pickupUntil = -1;
  private l3Intent: {
    vl: number;
    vr: number;
    head: number | null;
    lift: number | null;
    until: number;
  } | null = null;
  private navGoal: { x: number; y: number; until: number } | null = null;
  private dbgLast = { vl: 0, vr: 0, nav: false, intent: false, reason: 'idle' };

  readonly objects: WorldObject[] = [
    { id: 'face-1', kind: 'face', label: 'Persona', x: 40.0, y: 155.0, size: 72.0, confidence: 0.92 },
    { id: 'mug-1', kind: 'mug', label: 'Tazza', x: 120.0, y: -60.0, size: 42.0, confidence: 1.0 },
    { id: 'keys-1', kind: 'keys', label: 'Chiavi', x: -130.0, y: -70.0, size: 36.0, confidence: 1.0 },
    { id: 'cube-1', kind: 'cube', label: 'Cubo 1', x: -60.0, y: 40.0, size: 44.0, confidence: 1.0 },
    { id: 'cube-2', kind: 'cube', label: 'Cubo 2', x: 55.0, y: -35.0, size: 44.0, confidence: 1.0 },
    { id: 'cube-3', kind: 'cube', label: 'Cubo 3', x: 10.0, y: 70.0, size: 44.0, confidence: 1.0 },
  ];

  private recentEvents: RobotEvent[] = [];
  private listeners = new Set<(ev: RobotEvent) => void>();

  constructor() {
    this.emit('boot', { mode: 'digital_twin_node', objects: this.objects.length });
  }

  subscribe(listener: (ev: RobotEvent) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  getRecentEvents(): RobotEvent[] {
    return this.recentEvents.slice(-80);
  }

  emit(kind: string, data: Record<string, unknown>): RobotEvent {
    const ev: RobotEvent = {
      t: Number((Date.now() / 1000).toFixed(3)),
      kind,
      ...data,
    };
    this.recentEvents.push(ev);
    if (this.recentEvents.length > 250) {
      this.recentEvents = this.recentEvents.slice(-250);
    }
    for (const fn of this.listeners) {
      try {
        fn(ev);
      } catch {
        // ignore closed subscriber
      }
    }
    return ev;
  }

  overTable(x: number, y: number): boolean {
    return Math.abs(x) <= TABLE_HALF_X_MM && Math.abs(y) <= TABLE_HALF_Y_MM;
  }

  cliffMask(): number {
    const halfL = WHEEL_TRACK_MM / 2.0 + 4.0;
    const halfF = 24.0;
    const c = Math.cos(this.pose.theta);
    const s = Math.sin(this.pose.theta);
    const pts: Array<[number, number]> = [
      [this.pose.x + halfF * c - halfL * s, this.pose.y + halfF * s + halfL * c],
      [this.pose.x + halfF * c + halfL * s, this.pose.y + halfF * s - halfL * c],
      [this.pose.x - halfF * c - halfL * s, this.pose.y - halfF * s + halfL * c],
      [this.pose.x - halfF * c + halfL * s, this.pose.y - halfF * s - halfL * c],
    ];
    let mask = 0;
    for (let i = 0; i < pts.length; i++) {
      if (!this.overTable(pts[i][0], pts[i][1])) {
        mask |= 1 << i;
      }
    }
    return mask;
  }

  moodLabel(): string {
    if (this.moodFear > 0.45) return 'afraid';
    if (this.moodValence > 0.45 && this.moodArousal > 0.5) return 'excited';
    if (this.moodValence > 0.35) return 'happy';
    if (this.moodCurious > 0.35) return 'curious';
    return 'calm';
  }

  pickup(on: boolean, nowSec = Date.now() / 1000): void {
    if (on) {
      this.picked_up = true;
      this.pickupUntil = nowSec + 8.0;
      this.navGoal = null;
      this.l3Intent = null;
      this.face = 'surprised';
      this.emit('world', { event: 'pickup', on: true });
    } else {
      this.picked_up = false;
      this.pickupUntil = -1;
      if (this.estop && this.estop_reason === 'picked_up') {
        this.estop = false;
        this.estop_reason = '';
      }
      this.face = 'neutral';
      this.emit('world', { event: 'pickup', on: false });
    }
  }

  dropOffTable(nowSec = Date.now() / 1000): void {
    const aheadX = Math.cos(this.pose.theta) >= 0 ? TABLE_HALF_X_MM - 8 : -(TABLE_HALF_X_MM - 8);
    this.pose = {
      x: aheadX,
      y: clamp(this.pose.y, -(TABLE_HALF_Y_MM - 40), TABLE_HALF_Y_MM - 40),
      theta: aheadX > 0 ? 0 : Math.PI,
    };
    this.navGoal = null;
    this.cliffEscapeUntil = nowSec + 1.0;
    this.face = 'surprised';
    this.emit('world', { event: 'cliff_simulated', pose: this.pose });
  }

  resetCenter(): void {
    this.pose = { x: 0, y: 0, theta: 0 };
    this.vl = 0;
    this.vr = 0;
    this.navGoal = null;
    this.l3Intent = null;
    this.picked_up = false;
    this.pickupUntil = -1;
    this.cliffEscapeUntil = -1;
    this.estop = false;
    this.estop_reason = '';
    this.face = 'neutral';
    this.emit('world', { event: 'reset_center' });
  }

  setFace(name: string, via = 'ui'): boolean {
    const allowed: FaceExpression[] = [
      'neutral',
      'happy',
      'question',
      'surprised',
      'sad',
      'sleepy',
      'angry',
    ];
    const clean = name.trim().toLowerCase() as FaceExpression;
    if (!allowed.includes(clean)) return false;
    this.face = clean;
    if (clean === 'happy') {
      this.moodValence = clamp(this.moodValence + 0.35, -1, 1);
      this.moodFear = Math.max(0, this.moodFear - 0.3);
    } else if (clean === 'question') {
      this.moodCurious = clamp(this.moodCurious + 0.35, 0, 1);
    }
    this.emit('face', { expression: clean, via });
    return true;
  }

  speak(chirpName: string): void {
    const clean = (chirpName || 'hello').trim().toLowerCase();
    this.moodArousal = clamp(this.moodArousal + 0.2, 0, 1);
    this.emit('voice', { chirp: clean });
  }

  drive(vl: number, vr: number, holdSec = 0.8, nowSec = Date.now() / 1000): void {
    const cl = clamp(vl, -WHEEL_SPEED_HARD_MAX, WHEEL_SPEED_HARD_MAX);
    const cr = clamp(vr, -WHEEL_SPEED_HARD_MAX, WHEEL_SPEED_HARD_MAX);
    this.l3Intent = { vl: cl, vr: cr, head: null, lift: null, until: nowSec + holdSec };
    this.emit('drive', { vl: cl, vr: cr });
  }

  ask(text: string, nowSec = Date.now() / 1000): AskResult {
    const clean = (text || '').trim();
    if (!clean) {
      return {
        ok: false,
        answer: 'Inserisci un comando per Cozmo.',
        plan: '',
        grounded: false,
        executed: false,
        referent: null,
      };
    }

    const plan = parsePlan(clean);
    if (!plan.understood || !plan.action) {
      const answer = `Ho capito '${clean}' ma non so trasformarlo in un'azione concreta (prova: "gira verso la tazza", "vai al cubo", "faccia felice", "saluta").`;
      this.emit('l3', { ask: clean, plan: plan.raw, grounded: false, executed: false });
      return { ok: true, answer, plan: plan.raw, grounded: false, executed: false, referent: null };
    }

    const needsReferent = plan.action === 'look' || plan.action === 'goto';
    const grounded = groundUtterance(`${clean} ${plan.parameter}`, this.objects);
    if (needsReferent && (!grounded.ok || !grounded.referent)) {
      const answer = `Non ho capito a cosa ti riferisci (${grounded.note}). Oggetti visibili: tazza, cubo, chiavi, persona.`;
      this.emit('l3', {
        ask: clean,
        plan: `${plan.action}|${plan.parameter}`,
        grounded: false,
        executed: false,
        note: grounded.note,
      });
      return {
        ok: true,
        answer,
        plan: `${plan.action}|${plan.parameter}`,
        grounded: false,
        executed: false,
        referent: null,
      };
    }

    let detail = '';
    let executed = false;

    if (plan.action === 'look' && grounded.referent) {
      const target = this.nearestObject(grounded.referent);
      if (target) {
        const dx = target.x - this.pose.x;
        const dy = target.y - this.pose.y;
        const bearing = wrapAngle(Math.atan2(dy, dx) - this.pose.theta);
        const omega = clamp(bearing * 2.2, -1.8, 1.8);
        const half = (omega * WHEEL_TRACK_MM) / 2.0;
        this.l3Intent = {
          vl: clamp(-half, -90, 90),
          vr: clamp(half, -90, 90),
          head: clamp(0.24, HEAD_ANGLE_MIN, HEAD_ANGLE_MAX),
          lift: null,
          until: nowSec + Math.max(0.4, Math.min(2.0, Math.abs(bearing) / 1.1)),
        };
        this.face = 'question';
        this.emit('face', { expression: 'question', via: 'l3' });
        detail = `guardo verso ${target.label.toLowerCase()}`;
        executed = true;
      }
    } else if (plan.action === 'goto' && grounded.referent) {
      const target = this.nearestObject(grounded.referent);
      if (target) {
        const dist = Math.hypot(target.x - this.pose.x, target.y - this.pose.y);
        this.navGoal = { x: target.x, y: target.y, until: nowSec + 14.0 };
        this.face = 'happy';
        this.emit('face', { expression: 'happy', via: 'l3' });
        detail = `avanzo verso ${target.label.toLowerCase()} (dist ${Math.round(dist)} mm)`;
        executed = true;
      }
    } else if (plan.action === 'face') {
      const targetFace = plan.parameter.split(/\s+/)[0].toLowerCase() || 'neutral';
      executed = this.setFace(targetFace, 'l3');
      detail = executed ? `faccia ${targetFace}` : `espressione sconosciuta: ${targetFace}`;
    } else if (plan.action === 'speak') {
      const chirp = plan.parameter.split(/\s+/)[0].toLowerCase() || 'hello';
      this.speak(chirp);
      detail = `dico '${chirp}'`;
      executed = true;
    }

    const answer = executed
      ? `Fatto: ${detail}.`
      : `Ho capito '${clean}' ma il passo '${plan.action}' non è eseguibile (${detail}).`;

    this.emit('l3', {
      ask: clean,
      plan: `${plan.action}|${plan.parameter}`,
      grounded: grounded.ok,
      executed,
      referent: grounded.referent,
      detail,
    });

    return {
      ok: true,
      answer,
      plan: `${plan.action}|${plan.parameter}`,
      grounded: grounded.ok,
      executed,
      referent: grounded.referent,
    };
  }

  private nearestObject(kind: string): WorldObject | null {
    let best: WorldObject | null = null;
    let bestDist = Infinity;
    for (const o of this.objects) {
      if (o.kind !== kind) continue;
      const d = Math.hypot(o.x - this.pose.x, o.y - this.pose.y);
      if (d < bestDist) {
        best = o;
        bestDist = d;
      }
    }
    return best;
  }

  private computeNavCommand(nowSec: number): [number, number] | null {
    if (!this.navGoal) return null;
    if (nowSec >= this.navGoal.until) {
      this.navGoal = null;
      return null;
    }
    const dx = this.navGoal.x - this.pose.x;
    const dy = this.navGoal.y - this.pose.y;
    const dist = Math.hypot(dx, dy);
    if (dist <= 38.0) {
      this.navGoal = null;
      this.emit('nav', { status: 'arrived', pose: this.pose });
      return [0, 0];
    }
    const bearing = wrapAngle(Math.atan2(dy, dx) - this.pose.theta);
    let v = 0;
    let omega = 0;
    if (Math.abs(bearing) > 0.25) {
      omega = clamp(bearing * 2.0, -1.6, 1.6);
    } else {
      v = clamp(dist * 0.65, 25.0, 75.0);
      omega = clamp(bearing * 2.0, -1.2, 1.2);
    }
    const half = (omega * WHEEL_TRACK_MM) / 2.0;
    return [
      clamp(v - half, -WHEEL_SPEED_HARD_MAX, WHEEL_SPEED_HARD_MAX),
      clamp(v + half, -WHEEL_SPEED_HARD_MAX, WHEEL_SPEED_HARD_MAX),
    ];
  }

  step(dtSec: number, nowSec = Date.now() / 1000): StateSnapshot {
    if (dtSec <= 0) return this.snapshot(nowSec);

    if (this.pickupUntil > 0 && nowSec >= this.pickupUntil) {
      this.picked_up = false;
      this.pickupUntil = -1;
    }

    const cliff = this.cliffMask();
    const cliffNow = cliff !== 0;

    // Edge-triggered limbic updates
    if (cliffNow && !this.prevCliff) {
      this.moodFear = clamp(this.moodFear + 0.75, 0, 1);
      this.cliffEscapeUntil = nowSec + 0.9;
      this.face = 'surprised';
      this.emit('reflex', { event: 'cliff_detected', mask: cliff });
    }
    if (this.picked_up && !this.prevPicked) {
      this.moodFear = clamp(this.moodFear + 0.55, 0, 1);
      this.emit('reflex', { event: 'picked_up' });
    }
    this.prevCliff = cliffNow;
    this.prevPicked = this.picked_up;

    // Gentle decay back toward curious/calm baseline
    this.moodFear = Math.max(0, this.moodFear - dtSec * 0.18);
    this.moodArousal = clamp(this.moodArousal + (0.28 - this.moodArousal) * dtSec * 0.25, 0, 1);
    this.moodValence = clamp(this.moodValence + (0.22 - this.moodValence) * dtSec * 0.15, -1, 1);
    this.moodCurious = clamp(this.moodCurious + (0.38 - this.moodCurious) * dtSec * 0.12, 0, 1);

    const moodNow = this.moodLabel();
    if (moodNow !== this.prevMoodLabel) {
      this.prevMoodLabel = moodNow;
      this.emit('mood', { label: moodNow });
    }

    // L1 safety + command arbitration
    let vl = 0;
    let vr = 0;
    let navActive = false;
    let reason = 'idle';

    if (this.picked_up) {
      this.estop = true;
      this.estop_reason = 'picked_up';
      reason = 'picked_up_hold';
    } else if (cliffNow || nowSec < this.cliffEscapeUntil) {
      // Safety cliff escape: reverse back onto tabletop!
      this.estop = cliffNow;
      this.estop_reason = cliffNow ? 'cliff_escape' : '';
      vl = -65.0;
      vr = -65.0;
      this.navGoal = null;
      this.l3Intent = null;
      reason = 'cliff_escape';
    } else {
      this.estop = false;
      this.estop_reason = '';

      if (this.l3Intent) {
        if (nowSec >= this.l3Intent.until) {
          this.l3Intent = null;
        } else {
          vl = this.l3Intent.vl;
          vr = this.l3Intent.vr;
          if (this.l3Intent.head !== null) {
            this.head = clamp(this.l3Intent.head, HEAD_ANGLE_MIN, HEAD_ANGLE_MAX);
          }
          if (this.l3Intent.lift !== null) {
            this.lift = clamp(this.l3Intent.lift, LIFT_HEIGHT_MIN, LIFT_HEIGHT_MAX);
          }
          reason = 'l3_intent';
        }
      }

      const nav = this.computeNavCommand(nowSec);
      if (nav) {
        vl = nav[0];
        vr = nav[1];
        navActive = true;
        reason = 'nav_goal';
      }
    }

    this.vl = vl;
    this.vr = vr;

    if (!this.picked_up) {
      const v = (vl + vr) / 2.0;
      const omega = (vr - vl) / WHEEL_TRACK_MM;
      const nextTheta = wrapAngle(this.pose.theta + omega * dtSec);
      const nextX = clamp(this.pose.x + v * Math.cos(nextTheta) * dtSec, -(TABLE_HALF_X_MM + 40), TABLE_HALF_X_MM + 40);
      const nextY = clamp(this.pose.y + v * Math.sin(nextTheta) * dtSec, -(TABLE_HALF_Y_MM + 40), TABLE_HALF_Y_MM + 40);
      this.pose = {
        x: Number(nextX.toFixed(2)),
        y: Number(nextY.toFixed(2)),
        theta: Number(nextTheta.toFixed(4)),
      };
    }

    this.l1_ticks += 1;
    this.dbgLast = {
      vl: Number(vl.toFixed(1)),
      vr: Number(vr.toFixed(1)),
      nav: navActive,
      intent: this.l3Intent !== null,
      reason,
    };

    return this.snapshot(nowSec);
  }

  snapshot(nowSec = Date.now() / 1000): StateSnapshot {
    return {
      t: Number(nowSec.toFixed(2)),
      l1_ticks: this.l1_ticks,
      pose: { ...this.pose },
      head: Number(this.head.toFixed(3)),
      lift: Number(this.lift.toFixed(1)),
      battery_mv: this.battery_mv,
      cliff: this.cliffMask(),
      picked_up: this.picked_up,
      face: this.face,
      mood: this.moodLabel(),
      estop: this.estop,
      estop_reason: this.estop_reason,
      nav_goal: this.navGoal ? { x: this.navGoal.x, y: this.navGoal.y } : null,
      dbg: { ...this.dbgLast },
      objects: this.objects,
    };
  }
}

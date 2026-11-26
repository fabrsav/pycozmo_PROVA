import test from 'node:test';
import assert from 'node:assert/strict';
import { DigitalTwinRobot, groundUtterance, parsePlan } from './simulator.ts';

test('parsePlan recognizes Italian prose and structured commands', () => {
  const p1 = parsePlan('gira verso la tazza');
  assert.equal(p1.understood, true);
  assert.equal(p1.action, 'look');

  const p2 = parsePlan('goto|cubo');
  assert.equal(p2.understood, true);
  assert.equal(p2.action, 'goto');
  assert.equal(p2.parameter, 'cubo');

  const p3 = parsePlan('xyz sconosciuto');
  assert.equal(p3.understood, false);
});

test('groundUtterance grounds visible tabletop objects and rejects missing ones', () => {
  const robot = new DigitalTwinRobot();
  const ok = groundUtterance('gira verso la tazza', robot.objects);
  assert.equal(ok.ok, true);
  assert.equal(ok.referent, 'mug');

  const miss = groundUtterance('vai verso il gatto', robot.objects);
  assert.equal(miss.ok, false);
  assert.equal(miss.referent, null);
});

test('pickup engages safety hold and release clears estop', () => {
  const robot = new DigitalTwinRobot();
  robot.pickup(true, 100);
  const stHeld = robot.step(0.05, 100.05);
  assert.equal(stHeld.picked_up, true);
  assert.equal(stHeld.estop, true);
  assert.equal(stHeld.dbg.vl, 0);

  robot.pickup(false, 101);
  const stFree = robot.step(0.05, 101.05);
  assert.equal(stFree.picked_up, false);
  assert.equal(stFree.estop, false);
});

test('cliff reflex reverses Cozmo back onto the table', () => {
  const robot = new DigitalTwinRobot();
  robot.dropOffTable(200);
  const startX = robot.pose.x;
  assert.notEqual(robot.cliffMask(), 0);

  for (let i = 1; i <= 15; i++) {
    robot.step(0.05, 200 + i * 0.05);
  }
  assert.ok(robot.pose.x < startX, 'robot should reverse away from cliff edge');
});

test('ask executes grounded navigation toward mug', () => {
  const robot = new DigitalTwinRobot();
  const res = robot.ask('vai verso la tazza', 300);
  assert.equal(res.executed, true);
  assert.equal(res.referent, 'mug');
  const st = robot.step(0.05, 300.05);
  assert.equal(st.dbg.nav, true);
});

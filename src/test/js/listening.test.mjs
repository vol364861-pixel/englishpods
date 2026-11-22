import { test } from 'node:test';
import assert from 'node:assert/strict';
import { beijingDay, createPlaybackTracker, durationOnDay, formatListeningTime } from '../../main/resources/static/listening.mjs';

function setup(rate = 1) {
  const audio = { currentTime: 0, playbackRate: rate, paused: true, seeking: false, ended: false };
  const periods = [];
  let clock = 0;
  const origin = Date.parse('2026-11-20T16:00:00Z');
  const tracker = createPlaybackTracker(audio, (period) => periods.push(period), () => clock, () => origin + clock);
  const advance = (milliseconds, position) => { clock += milliseconds; audio.currentTime = position; tracker.sample(); };
  return { tracker, audio, advance, periods, origin };
}

test('tracks real playback time at double speed and ignores idle or paused time', () => {
  const { tracker, audio, advance, periods, origin } = setup(2);
  tracker.start();
  advance(2000, 0);
  audio.paused = false;
  tracker.start();
  advance(1000, 2);
  audio.paused = true;
  tracker.stop();
  advance(5000, 2);
  assert.deepEqual(periods, [{ start: origin + 2000, end: origin + 3000 }]);
});

test('buffering, seeking, and looping do not add elapsed or skipped time', () => {
  const { tracker, audio, advance, periods, origin } = setup();
  audio.paused = false;
  tracker.start();
  advance(1000, 1);
  tracker.stop();
  advance(5000, 1);
  audio.seeking = true;
  advance(300, 35);
  tracker.start();
  audio.seeking = false;
  tracker.start();
  advance(1000, 36);
  tracker.stop(); // The loop moves back to its start.
  advance(100, 35);
  tracker.start();
  advance(1000, 36);
  assert.deepEqual(periods, [
    { start: origin, end: origin + 1000 },
    { start: origin + 6300, end: origin + 7300 },
    { start: origin + 7400, end: origin + 8400 }
  ]);
});

test('allocates a playback period to both Beijing dates at midnight', () => {
  const boundary = Date.parse('2026-11-22T16:00:00Z');
  const periods = [{ start: boundary - 1250, end: boundary + 2200 }];
  assert.equal(beijingDay(boundary - 1), '2026-11-22');
  assert.equal(beijingDay(boundary), '2026-11-23');
  assert.equal(durationOnDay(periods, '2026-11-22'), 1250);
  assert.equal(durationOnDay(periods, '2026-11-23'), 2200);
  assert.equal(formatListeningTime(3661500), '01:01:01');
});

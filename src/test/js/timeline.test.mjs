import test from 'node:test';
import assert from 'node:assert/strict';
import { findActiveIndex, loopSeekTime, clampTime, formatTime, sourceParts } from '../../main/resources/static/timeline.mjs';

test('word lookup respects half-open intervals and removes highlight during silence', () => {
  const words = [{ start: .1, end: .4 }, { start: .4, end: .8 }, { start: 1, end: 1.4 }];
  assert.equal(findActiveIndex(words, 0), -1);
  assert.equal(findActiveIndex(words, .1), 0);
  assert.equal(findActiveIndex(words, .3999), 0);
  assert.equal(findActiveIndex(words, .4), 1);
  assert.equal(findActiveIndex(words, .8), -1);
  assert.equal(findActiveIndex(words, .95), -1);
  assert.equal(findActiveIndex(words, 1), 2);
  assert.equal(findActiveIndex(words, 1.4), -1);
});

test('lookup handles seeking backward, empty timeline, invalid time and a long story', () => {
  assert.equal(findActiveIndex([], 0), -1);
  assert.equal(findActiveIndex([{ start: 0, end: 1 }], NaN), -1);
  const words = Array.from({ length: 10000 }, (_, index) => ({ start: index * 2, end: index * 2 + 1 }));
  assert.equal(findActiveIndex(words, 19998.5), 9999);
  assert.equal(findActiveIndex(words, 4.1), 2);
  assert.equal(findActiveIndex(words, -1), -1);
});

test('audio media seconds require no playback-rate multiplication', () => {
  const words = [{ start: 0, end: 1 }, { start: 1, end: 2 }];
  // At all playback rates the browser reports media time, not wall-clock time.
  for (const rate of [.5, .75, 1, 1.25, 1.5, 2]) {
    const wallClockElapsed = 1.2 / rate;
    const browserCurrentTime = wallClockElapsed * rate;
    assert.equal(findActiveIndex(words, browserCurrentTime), 1);
  }
});

test('sentence looping honors exact end, seeks outside range, and does not move paused audio', () => {
  const sentence = { start: 3.2, end: 6.7 };
  assert.equal(loopSeekTime(sentence, 3.2, true), null);
  assert.equal(loopSeekTime(sentence, 6.699, true), null);
  assert.equal(loopSeekTime(sentence, 6.7, true), 3.2);
  assert.equal(loopSeekTime(sentence, 8, true), 3.2);
  assert.equal(loopSeekTime(sentence, 1, true), 3.2);
  assert.equal(loopSeekTime(sentence, 8, false), null);
  assert.equal(loopSeekTime(null, 8, true), null);
  assert.equal(loopSeekTime({ start: 2, end: 2 }, 3, true), null);
});

test('source slices preserve punctuation, leading whitespace, newlines and UTF-16 emoji', () => {
  const text = '  “Sam’s 📚!”\n\nThat\'s good.  ';
  const spans = [
    { id: 'w1', sourceStart: 3, sourceEnd: 8, text: 'ignored model text' },
    { id: 'w2', sourceStart: 9, sourceEnd: 11 },
    { id: 'w3', sourceStart: 15, sourceEnd: 21 },
  ];
  const parts = sourceParts(text, spans);
  assert.equal(parts.map((part) => part.text).join(''), text);
  assert.equal(parts.find((part) => part.span?.id === 'w1').text, 'Sam’s');
  assert.equal(parts.find((part) => part.span?.id === 'w2').text, '📚');
  assert.equal(parts.find((part) => part.span?.id === 'w3').text, "That's");
});

test('nested sentence slices retain original text and reject overlap or invalid offsets', () => {
  const text = 'Hi.\n\nHello!';
  const sentence = { sourceStart: 5, sourceEnd: 11 };
  const parts = sourceParts(text, [{ sourceStart: 5, sourceEnd: 10 }], sentence.sourceStart, sentence.sourceEnd);
  assert.equal(parts.map((part) => part.text).join(''), 'Hello!');
  assert.throws(() => sourceParts(text, [{ sourceStart: 0, sourceEnd: 4 }, { sourceStart: 3, sourceEnd: 5 }]));
  assert.throws(() => sourceParts(text, [{ sourceStart: -1, sourceEnd: 2 }]));
  assert.throws(() => sourceParts(text, [{ sourceStart: 5, sourceEnd: 12 }]));
  assert.throws(() => sourceParts(text, [{ sourceStart: 5.5, sourceEnd: 7 }]));
});

test('seek clamping and duration formatting handle audio ends and unavailable metadata', () => {
  assert.equal(clampTime(-5, 10), 0);
  assert.equal(clampTime(20, 10), 10);
  assert.equal(clampTime(4.5, 10), 4.5);
  assert.equal(clampTime(5, NaN), 0);
  assert.equal(formatTime(65.9), '1:05');
  assert.equal(formatTime(3600), '60:00');
  assert.equal(formatTime(NaN), '0:00');
});

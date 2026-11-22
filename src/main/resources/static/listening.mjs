const dayMs = 86_400_000;
const beijingOffset = 8 * 3_600_000;

export function beijingDay(epochMs) {
  return new Date(epochMs + beijingOffset).toISOString().slice(0, 10);
}

export function durationOnDay(periods, date) {
  const start = Date.parse(date + 'T00:00:00+08:00');
  return periods.reduce((total, period) =>
    total + Math.max(0, Math.min(start + dayMs, period.end) - Math.max(start, period.start)), 0);
}

export function formatListeningTime(milliseconds) {
  const seconds = Math.floor(milliseconds / 1000);
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((part) => String(part).padStart(2, '0')).join(':');
}

export function createPlaybackTracker(audio, onPeriod, monotonic = () => performance.now(), epoch = () => Date.now()) {
  let anchor = null;

  function start() {
    if (!anchor && !audio.paused && !audio.seeking && !audio.ended) {
      anchor = { clock: monotonic(), wall: epoch(), position: audio.currentTime, rate: audio.playbackRate };
    }
  }

  function sample() {
    if (!anchor || audio.seeking) return;
    const played = (audio.currentTime - anchor.position) * 1000 / anchor.rate;
    if (!(played > 0)) return;
    const clock = monotonic();
    const elapsed = clock - anchor.clock;
    // Media clocks drift a few milliseconds relative to performance.now().
    const duration = elapsed - played <= 20 && played >= elapsed * 0.9 ? elapsed : Math.min(elapsed, played);
    const start = Math.round(anchor.wall);
    const end = Math.round(anchor.wall + duration);
    anchor = { clock, wall: anchor.wall + elapsed, position: audio.currentTime, rate: audio.playbackRate };
    if (end > start) onPeriod({ start, end });
  }

  function stop() {
    sample();
    anchor = null;
  }

  return { start, sample, stop };
}

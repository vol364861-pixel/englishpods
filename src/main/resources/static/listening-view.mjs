import { beijingDay, createPlaybackTracker, durationOnDay, formatListeningTime } from './listening.mjs';

const outboxPrefix = 'englishpods.listening.';
const keyFor = ({ start, end }) => `${outboxPrefix}${start}.${end}`;
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const shortDate = (date) => date.slice(5).replace('-', '/');

function restoreOutbox() {
  const periods = [];
  try {
    for (let index = 0; index < localStorage.length; index++) {
      const key = localStorage.key(index);
      if (!key?.startsWith(outboxPrefix)) continue;
      const [start, end] = key.slice(outboxPrefix.length).split('.').map(Number);
      if (Number.isSafeInteger(start) && Number.isSafeInteger(end) && end > start) periods.push({ start, end });
    }
  } catch { /* The server can still receive updates when browser storage is disabled. */ }
  return periods;
}

function axisLabel(ms) {
  if (ms >= 3_600_000) return `${(ms / 3_600_000).toFixed(1).replace(/\.0$/, '')} 小时`;
  if (ms >= 60_000) return `${Math.round(ms / 60_000)} 分`;
  return `${ms / 1000} 秒`;
}

function drawChart(canvas, days, today) {
  const { width, height } = canvas.getBoundingClientRect();
  if (!width || !height) return;
  const scale = window.devicePixelRatio || 1;
  canvas.width = Math.round(width * scale);
  canvas.height = Math.round(height * scale);
  const context = canvas.getContext('2d');
  context.scale(scale, scale);
  const left = 51, right = width - 12, top = 14, bottom = height - 14;
  const largest = Math.max(...days.map((day) => day.milliseconds));
  const step = [5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 28800]
    .find((seconds) => seconds * 3000 >= largest) * 1000;
  const max = 3 * step;
  context.font = '11px system-ui';
  context.textAlign = 'right';
  context.textBaseline = 'middle';
  for (let row = 0; row <= 3; row++) {
    const y = bottom - (bottom - top) * row / 3;
    context.strokeStyle = '#dfe8dd';
    context.beginPath(); context.moveTo(left, y); context.lineTo(right, y); context.stroke();
    context.fillStyle = '#52665a';
    context.fillText(axisLabel(row * step), left - 8, y);
  }
  const points = days.flatMap((day, index) => day.date > today ? [] : [{
    x: left + (right - left) * (index + 0.5) / 7,
    y: bottom - (bottom - top) * day.milliseconds / max
  }]);
  if (!points.length) return;
  const trace = () => {
    context.beginPath();
    context.moveTo(points[0].x, points[0].y);
    for (const { x, y } of points.slice(1)) context.lineTo(x, y);
  };
  trace();
  context.lineTo(points.at(-1).x, bottom); context.lineTo(points[0].x, bottom); context.closePath();
  const gradient = context.createLinearGradient(0, top, 0, bottom);
  gradient.addColorStop(0, '#b4d4bb');
  gradient.addColorStop(1, '#e9f0e6');
  context.fillStyle = gradient; context.fill();
  trace();
  context.lineWidth = 2.5; context.lineJoin = 'round'; context.strokeStyle = '#30684d'; context.stroke();
  for (const { x, y } of points) {
    context.beginPath(); context.arc(x, y, 4, 0, 2 * Math.PI);
    context.fillStyle = '#30684d'; context.fill();
  }
}

export function bindListening({ audio, timer, value, panel, canvas, range, total, days, previous, next, message }) {
  let pending = [];
  let outbox = restoreOutbox();
  let summary = null;
  let requestedWeek = null;
  let syncing = false;
  let refreshAgain = false;

  function renderTimer() {
    const date = beijingDay(Date.now());
    const saved = summary?.today.date === date ? summary.today.milliseconds : 0;
    value.textContent = formatListeningTime(saved + durationOnDay([...outbox, ...pending], date));
  }

  const tracker = createPlaybackTracker(audio, (period) => {
    const last = pending.at(-1);
    if (last?.end === period.start && period.end - last.start <= 86_400_000) last.end = period.end;
    else pending.push(period);
    renderTimer();
  });

  function savePending() {
    for (const period of pending) {
      try { localStorage.setItem(keyFor(period), '1'); } catch { /* Retry in memory. */ }
      outbox.push(period);
    }
    pending = [];
  }

  function renderBoard() {
    if (!panel.open || !summary) return;
    const weekDays = summary.days;
    range.textContent = `第 ${summary.week} 周 · ${weekDays[0].date.slice(0, 4)} / ${shortDate(weekDays[0].date)} – ${shortDate(weekDays[6].date)}`;
    total.textContent = formatListeningTime(weekDays.reduce((sum, day) => sum + day.milliseconds, 0));
    previous.disabled = summary.week <= 1;
    next.disabled = summary.week >= summary.currentWeek;
    days.replaceChildren(...weekDays.map((day, index) => {
      const item = document.createElement('li');
      const label = document.createElement('span');
      label.textContent = weekdays[index];
      const date = document.createElement('time');
      date.dateTime = day.date;
      date.textContent = shortDate(day.date);
      const duration = document.createElement('strong');
      const formatted = formatListeningTime(day.milliseconds);
      duration.textContent = day.date > summary.today.date ? '—' : formatted.replace(/^00:/, '');
      item.classList.toggle('is-today', day.date === summary.today.date);
      item.append(label, date, duration);
      return item;
    }));
    drawChart(canvas, weekDays, summary.today.date);
  }

  async function sync() {
    if (syncing) return;
    syncing = true;
    savePending();
    const sent = outbox.slice(0, 100);
    const week = panel.open ? requestedWeek : null;
    try {
      if (sent.length) {
        const response = await fetch('/api/listening', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(sent), keepalive: true
        });
        if (!response.ok) throw new Error();
      }
      const response = await fetch(week === null ? '/api/listening' : `/api/listening?week=${week}`, { cache: 'no-store' });
      if (!response.ok) throw new Error();
      summary = await response.json();
      outbox = outbox.slice(sent.length);
      for (const period of sent) {
        try { localStorage.removeItem(keyFor(period)); } catch { /* Uploads can safely repeat. */ }
      }
      message.hidden = true;
      if ((panel.open ? requestedWeek : null) === week) renderBoard();
    } catch {
      if (panel.open) {
        message.textContent = '暂时无法同步收听时长，稍后会自动重试。';
        message.hidden = false;
      }
    } finally {
      renderTimer();
      syncing = false;
      if (refreshAgain) {
        refreshAgain = false;
        sync();
      }
    }
  }

  function requestRefresh() {
    if (syncing) refreshAgain = true;
    else sync();
  }

  function start() {
    tracker.start();
    timer.classList.toggle('is-playing', !audio.paused && !audio.seeking && !audio.ended);
  }

  function stop() {
    tracker.stop();
    timer.classList.remove('is-playing');
  }

  audio.addEventListener('playing', start);
  audio.addEventListener('timeupdate', tracker.sample);
  for (const event of ['waiting', 'seeking', 'emptied', 'error']) audio.addEventListener(event, stop);
  for (const event of ['pause', 'ended']) audio.addEventListener(event, () => { stop(); requestRefresh(); });
  audio.addEventListener('seeked', () => { if (audio.readyState >= 3) start(); });
  audio.addEventListener('ratechange', () => {
    tracker.sample();
    // Only restart a running clock, since changing speed cannot resume buffered audio.
    if (timer.classList.contains('is-playing')) { stop(); start(); }
  });

  previous.addEventListener('click', () => {
    if (!summary || previous.disabled) return;
    requestedWeek = summary.week - 1;
    requestRefresh();
  });
  next.addEventListener('click', () => {
    if (!summary || next.disabled) return;
    requestedWeek = summary.week + 1 === summary.currentWeek ? null : summary.week + 1;
    requestRefresh();
  });
  new ResizeObserver(() => {
    if (panel.open && summary && summary.week === (requestedWeek ?? summary.currentWeek)) {
      drawChart(canvas, summary.days, summary.today.date);
    }
  }).observe(canvas);

  window.addEventListener('pagehide', () => {
    tracker.sample();
    savePending();
    if (outbox.length) {
      navigator.sendBeacon('/api/listening',
        new Blob([JSON.stringify(outbox.slice(0, 100))], { type: 'application/json' }));
    }
  });
  window.addEventListener('pageshow', requestRefresh);
  window.addEventListener('online', requestRefresh);
  setInterval(() => {
    tracker.sample();
    if (pending.length || outbox.length || panel.open || summary?.today.date !== beijingDay(Date.now())) sync();
    else renderTimer();
  }, 5000);
  // Separate idle refreshes keep the timer current if another tab is playing.
  setInterval(sync, 30_000);
  sync();

  return {
    beforeSeek: stop,
    stop,
    open() {
      if (panel.open) return;
      requestedWeek = null;
      panel.showModal();
      if (summary && summary.week === summary.currentWeek) renderBoard();
      else {
        range.textContent = '正在加载…';
        total.textContent = '—';
        days.replaceChildren();
        previous.disabled = next.disabled = true;
        canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
      }
      requestRefresh();
    }
  };
}

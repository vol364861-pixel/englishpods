/** Timeline intervals use audio seconds and UTF-16 half-open source ranges. */
export function findActiveIndex(items, time) {
  if (!Number.isFinite(time) || !items.length) return -1;
  let low = 0;
  let high = items.length - 1;
  let candidate = -1;
  while (low <= high) {
    const middle = (low + high) >>> 1;
    if (items[middle].start <= time) {
      candidate = middle;
      low = middle + 1;
    } else {
      high = middle - 1;
    }
  }
  return candidate >= 0 && time < items[candidate].end ? candidate : -1;
}

export function loopSeekTime(sentence, time, playing) {
  if (!playing || !sentence || !Number.isFinite(time) || sentence.end <= sentence.start) return null;
  return time < sentence.start || time >= sentence.end ? sentence.start : null;
}

export function clampTime(time, duration) {
  return Math.max(0, Math.min(Number.isFinite(duration) && duration > 0 ? duration : 0, Number.isFinite(time) ? time : 0));
}

export function formatTime(time) {
  const seconds = Math.max(0, Math.floor(Number.isFinite(time) ? time : 0));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/** Build display fragments from source slices, never reconstructed model text. */
export function sourceParts(text, spans, start = 0, end = text.length) {
  const parts = [];
  let cursor = start;
  for (const span of spans) {
    if (!Number.isInteger(span.sourceStart) || !Number.isInteger(span.sourceEnd)
      || span.sourceStart < cursor || span.sourceEnd <= span.sourceStart || span.sourceEnd > end) {
      throw new Error('阅读时间轴包含无效的原文范围');
    }
    if (span.sourceStart > cursor) parts.push({ text: text.slice(cursor, span.sourceStart), span: null });
    parts.push({ text: text.slice(span.sourceStart, span.sourceEnd), span });
    cursor = span.sourceEnd;
  }
  if (cursor < end) parts.push({ text: text.slice(cursor, end), span: null });
  return parts;
}

export function paragraphStartWords(text, words) {
  const starts = [];
  let previousEnd = 0;
  words.forEach((word, index) => {
    if (index === 0 || /[\r\n\u2028\u2029]/.test(text.slice(previousEnd, word.sourceStart))) starts.push(index);
    previousEnd = word.sourceEnd;
  });
  return starts;
}

function nextParagraphTop(paragraphs, minimumTop) {
  // The first paragraph can start below the page header, but cannot replace itself.
  let low = 1;
  let high = paragraphs.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (paragraphs[middle].getBoundingClientRect().top < minimumTop) low = middle + 1;
    else high = middle;
  }
  return paragraphs[low]?.getBoundingClientRect().top ?? Infinity;
}

export function bindReaderFollow({ button, state, player, viewport, currentWord, paragraphs, read, write }) {
  const preferenceKey = 'englishpods.readerFollow';
  let enabled = read(preferenceKey) !== 'off';

  function render() {
    button.setAttribute('aria-pressed', String(enabled));
    state.textContent = enabled ? '开启' : '关闭';
  }

  function reveal() {
    if (!enabled || player.hidden) return;
    const word = currentWord();
    if (!word) return;

    const top = 12;
    const bottom = player.getBoundingClientRect().top - 12;
    if (bottom <= top) return;
    const bounds = word.getBoundingClientRect();
    if (bounds.top >= top && bounds.bottom <= bottom) return;

    let scroll = bounds.top - top;
    if (bounds.top >= top) {
      const needed = bounds.bottom - bottom;
      const paragraphTop = nextParagraphTop(paragraphs(), top + needed);
      // Keep as much context as possible by moving the first usable paragraph to the top.
      // Inside a long paragraph, advance part of a screen so some lines remain visible.
      scroll = paragraphTop <= bounds.top ? paragraphTop - top
        : Math.min(scroll, Math.max(needed, (bottom - top) * .75));
    }
    viewport.scrollBy({ top: Math.ceil(scroll), behavior: 'instant' });
  }

  button.addEventListener('click', () => {
    enabled = !enabled;
    write(preferenceKey, enabled ? 'on' : 'off');
    render();
    reveal();
  });
  viewport.addEventListener('resize', reveal);
  render();
  return reveal;
}

import test from 'node:test';
import assert from 'node:assert/strict';
import { bindReaderFollow, paragraphStartWords } from '../../main/resources/static/reader-follow.mjs';

function reader(saved = null) {
  const button = new EventTarget();
  const attributes = new Map();
  button.setAttribute = (name, value) => attributes.set(name, value);
  const state = {};
  const writes = [];
  const scrolls = [];
  const viewport = new EventTarget();
  viewport.scrollBy = (options) => scrolls.push(options);
  let playerTop = 622;
  const player = { hidden: false, getBoundingClientRect: () => ({ top: playerTop }) };
  let word;
  let paragraphs;
  const reveal = bindReaderFollow({
    button, state, player, viewport, currentWord: () => word, paragraphs: () => paragraphs,
    read: () => saved, write: (...args) => writes.push(args)
  });
  return {
    attributes, state, writes, scrolls, player, reveal,
    click: () => button.dispatchEvent(new Event('click')),
    setWord(top, bottom, paragraphTops = []) {
      word = { getBoundingClientRect: () => ({ top, bottom }) };
      paragraphs = paragraphTops.map((top) => ({ getBoundingClientRect: () => ({ top }) }));
    },
    resize(top) {
      playerTop = top;
      viewport.dispatchEvent(new Event('resize'));
    }
  };
}

test('first timed word after each original line break is a paragraph anchor', () => {
  const text = '“One 📚 two.”\r\n\r\n   Three.\nFour.\rFive.\u2028Six.\u2029Seven.';
  const words = [...text.matchAll(/[A-Za-z]+/g)].map((match) => ({
    sourceStart: match.index,
    sourceEnd: match.index + match[0].length
  }));
  assert.deepEqual(paragraphStartWords(text, words), [0, 2, 3, 4, 5, 6]);
  assert.deepEqual(paragraphStartWords('', []), []);
});

test('does not scroll while the highlight is visible, or without a visible reader', () => {
  const ui = reader();
  assert.equal(ui.attributes.get('aria-pressed'), 'true');
  assert.equal(ui.state.textContent, '开启');
  ui.reveal();
  ui.setWord(580, 610);
  ui.reveal();
  ui.setWord(605, 630);
  ui.player.hidden = true;
  ui.reveal();
  assert.deepEqual(ui.scrolls, []);
});

test('a word overlapping the player shifts the next visible paragraph to the top', () => {
  const ui = reader();
  ui.setWord(600, 628, [-20, 150, 300, 500]);
  ui.reveal();
  assert.deepEqual(ui.scrolls, [{ top: 138, behavior: 'instant' }]);
});

test('the first scroll moves past the opening paragraph even with the page header visible', () => {
  const ui = reader();
  ui.setWord(600, 628, [300, 500, 650]);
  ui.reveal();
  assert.equal(ui.scrolls[0].top, 488);
});

test('a large forward seek skips paragraph boundaries that would leave the word hidden', () => {
  const ui = reader();
  ui.setWord(1300, 1330, [-20, 300, 700, 780, 1100, 1450]);
  ui.reveal();
  assert.equal(ui.scrolls[0].top, 768);
});

test('one long paragraph advances part of a screen and backward seeks restore the highlight', () => {
  const ui = reader();
  ui.setWord(600, 628, [-100, 800]);
  ui.reveal();
  assert.equal(ui.scrolls[0].top, 449);
  ui.setWord(-80, -52, [-100, 800]);
  ui.reveal();
  assert.equal(ui.scrolls[1].top, -92);
});

test('the saved off state disables scrolling, and re-enabling reveals the current word', () => {
  const ui = reader('off');
  ui.setWord(600, 628, [-20, 150, 300]);
  assert.equal(ui.attributes.get('aria-pressed'), 'false');
  assert.equal(ui.state.textContent, '关闭');
  ui.reveal();
  assert.deepEqual(ui.scrolls, []);
  ui.click();
  assert.equal(ui.attributes.get('aria-pressed'), 'true');
  assert.equal(ui.state.textContent, '开启');
  assert.equal(ui.scrolls[0].top, 138);
  ui.click();
  ui.reveal();
  assert.equal(ui.scrolls.length, 1);
  assert.deepEqual(ui.writes, [['englishpods.readerFollow', 'on'], ['englishpods.readerFollow', 'off']]);
});

test('a smaller viewport follows when the fixed player covers the highlight', () => {
  const ui = reader();
  ui.setWord(510, 540, [-20, 145, 300]);
  ui.reveal();
  assert.deepEqual(ui.scrolls, []);
  ui.resize(500);
  assert.equal(ui.scrolls[0].top, 133);
});

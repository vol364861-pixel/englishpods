import test from 'node:test';
import assert from 'node:assert/strict';
import { bindSidebar } from '../../main/resources/static/sidebar.mjs';

function sidebar(saved, narrow = false) {
  const classes = new Set();
  const attributes = {};
  const writes = [];
  const root = new EventTarget();
  root.classList = { toggle: (key, on) => on ? classes.add(key) : classes.delete(key) };
  const content = { hidden: false };
  const toggle = new EventTarget();
  toggle.setAttribute = (key, value) => attributes[key] = value;
  let toggleFocused = false;
  toggle.focus = () => { toggleFocused = true; };
  const backdrop = new EventTarget();
  const background = [{ inert: false }, { inert: false }];
  const narrowScreen = new EventTarget();
  narrowScreen.matches = narrow;
  const controls = bindSidebar({
    root, content, toggle, backdrop, background, narrowScreen,
    read: () => saved, write: (...args) => writes.push(args)
  });
  return {
    classes, content, attributes, toggle, writes, controls, backdrop, background,
    click: () => toggle.dispatchEvent(new Event('click')),
    clickBackdrop: () => backdrop.dispatchEvent(new Event('click')),
    toggleFocused: () => toggleFocused,
    pressEscape() {
      const event = new Event('keydown', { cancelable: true });
      event.key = 'Escape';
      root.dispatchEvent(event);
      return event.defaultPrevented;
    },
    resize(matches) {
      narrowScreen.matches = matches;
      narrowScreen.dispatchEvent(new Event('change'));
    }
  };
}

test('sidebar collapses and expands with accessible labels and persistent preference', () => {
  const ui = sidebar(null);
  assert.equal(ui.content.hidden, false);
  assert.equal(ui.attributes['aria-expanded'], 'true');
  ui.click();
  assert.equal(ui.content.hidden, true);
  assert.equal(ui.attributes['aria-expanded'], 'false');
  assert.equal(ui.attributes['aria-label'], '展开侧边栏');
  assert.ok(ui.classes.has('sidebar-collapsed'));
  assert.deepEqual(ui.writes[0], ['englishpods.sidebar', 'collapsed']);
  ui.click();
  assert.equal(ui.content.hidden, false);
  assert.equal(ui.toggle.title, '收起侧边栏');
  assert.equal(ui.classes.has('sidebar-collapsed'), false);
  assert.deepEqual(ui.writes[1], ['englishpods.sidebar', 'expanded']);
});

test('saved collapsed preference hides shelf contents on reload', () => {
  const ui = sidebar('collapsed');
  assert.equal(ui.content.hidden, true);
  assert.equal(ui.attributes['aria-expanded'], 'false');
  assert.equal(ui.writes.length, 0);
});

test('small screens start collapsed and keep their drawer state separate from desktop preferences', () => {
  assert.equal(sidebar(null, true).content.hidden, true);
  const ui = sidebar('expanded', true);
  assert.equal(ui.content.hidden, true);
  ui.click();
  assert.equal(ui.content.hidden, false);
  assert.equal(ui.backdrop.hidden, false);
  assert.ok(ui.classes.has('sidebar-drawer-open'));
  assert.ok(ui.background.every((region) => region.inert));
  assert.deepEqual(ui.writes, []);
  ui.resize(false);
  assert.equal(ui.content.hidden, false);
  assert.equal(ui.backdrop.hidden, true);
  assert.ok(ui.background.every((region) => !region.inert));
  ui.resize(true);
  assert.equal(ui.content.hidden, true);
});

test('the mobile drawer closes from Escape, backdrop clicks, and story navigation', () => {
  const ui = sidebar('expanded', true);
  assert.equal(ui.pressEscape(), false);
  ui.click();
  assert.equal(ui.pressEscape(), true);
  assert.equal(ui.content.hidden, true);
  assert.equal(ui.backdrop.hidden, true);
  assert.equal(ui.toggleFocused(), true);
  ui.click();
  ui.clickBackdrop();
  assert.equal(ui.content.hidden, true);
  ui.controls.expand();
  assert.equal(ui.controls.dismissDrawer(), true);
  assert.equal(ui.content.hidden, true);
  assert.equal(ui.controls.dismissDrawer(), false);
  assert.deepEqual(ui.writes, []);
});

test('service configuration can reveal the sidebar and persist its state', () => {
  const ui = sidebar('collapsed');
  ui.controls.expand();
  assert.equal(ui.content.hidden, false);
  assert.equal(ui.attributes['aria-expanded'], 'true');
  assert.deepEqual(ui.writes.at(-1), ['englishpods.sidebar', 'expanded']);
});

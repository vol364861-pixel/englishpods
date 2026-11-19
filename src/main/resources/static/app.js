import { findActiveIndex, loopSeekTime, clampTime, formatTime, sourceParts } from './timeline.mjs';

import { checkpointDescription } from './task-progress.mjs';

const $ = (id) => document.getElementById(id);
const audio = $('audio');
const stages = ['QUEUED', 'SEGMENTING', 'REVIEWING', 'SYNTHESIZING', 'ASSEMBLING'];
const labels = { CREATED: '已保存', QUEUED: '等待生成', SEGMENTING: '识别角色', REVIEWING: '复核分角', SYNTHESIZING: '合成配音', ASSEMBLING: '整理音轨', COMPLETED: '可以收听', FAILED: '生成失败' };
const descriptions = { QUEUED: '故事已排队，即将开始', SEGMENTING: '正在辨认故事里的每一个声音', REVIEWING: '再次检查角色与对白', SYNTHESIZING: '正在为角色录制声音与单词时间轴', ASSEMBLING: '正在拼接音频，整理阅读时间轴' };
const example = `On Tuesday afternoon, Maya, Ben, and I went back to Sam's bookstore. Our first story had been online since yesterday. Maya checked her phone as we walked.

“Two more people liked it,” she said.

“That's good,” I said. “But I want to see the map.”

“Then walk a little faster,” Ben said.`;

let readiness = null;
let selected = null;
let result = null;
let history = [];
let selectionVersion = 0;
let selectionRequest = null;
let pollTimer = null;
let mutationPending = false;
let resultLoadedFor = null;
let frame = null;
let wordNodes = [];
let sentenceNodes = [];
let activeWord = -1;
let activeSentence = -1;
let selectedSentence = -1;
let loopEnabled = false;
let playbackVersion = 0;

function stored(key) { try { return localStorage.getItem(key); } catch { return null; } }
function remember(key, value) { try { value == null ? localStorage.removeItem(key) : localStorage.setItem(key, value); } catch { /* Browser storage may be disabled. */ } }

async function api(path, options = {}) {
  let response;
  try { response = await fetch(path, { ...options, headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers } }); }
  catch (error) { if (error.name === 'AbortError') throw error; throw new Error('无法连接服务器，请确认应用正在运行，然后重试。'); }
  let body;
  try { body = await response.json(); } catch { throw new Error(`服务器返回了无法读取的响应（HTTP ${response.status}）。`); }
  if (!response.ok) {
    const details = body.fieldErrors && Object.values(body.fieldErrors).flat().join('；');
    const error = new Error([body.message || `请求失败（HTTP ${response.status}）`, details].filter(Boolean).join('：'));
    error.status = response.status;
    throw error;
  }
  return body;
}

function notify(message, error = false) {
  $('notice').textContent = message;
  $('notice').classList.toggle('error', error);
  $('notice').hidden = !message;
}

function setPending(pending) {
  mutationPending = pending;
  $('submit-story').disabled = pending;
  $('generate-story').disabled = pending;
  $('new-story').disabled = pending;
  $('load-example').disabled = pending;
  $('submit-story').textContent = pending ? '正在保存…' : '保存并生成 →';
}

async function refreshConfig(reportFailure = false) {
  $('refresh-config').disabled = true;
  try {
    readiness = await api('/api/config');
    $('config-summary').textContent = readiness.ready ? '生成服务已就绪' : '生成服务待配置 · 可以先保存故事';
    $('config-dot').className = `status-dot ${readiness.ready ? 'ready' : 'unavailable'}`;
    $('config-issues').replaceChildren(...(readiness.issues || []).map((issue) => {
      const node = document.createElement('li'); node.textContent = issue; return node;
    }));
    const voices = Object.entries(readiness.voices || {}).map(([speaker, voice]) => `${speaker}: ${voice}`).join(' · ');
    $('config-voices').textContent = voices ? `角色音色：${voices}。第一人称：${readiness.firstPersonSpeaker || 'alex'}。` : '';
    return readiness;
  } catch (error) {
    readiness = null;
    $('config-summary').textContent = '暂时无法检查服务 · 故事仍可保存';
    $('config-dot').className = 'status-dot unavailable';
    $('config-issues').replaceChildren();
    const issue = document.createElement('li'); issue.textContent = error.message; $('config-issues').append(issue);
    if (reportFailure) notify(error.message, true);
    return null;
  } finally { $('refresh-config').disabled = false; }
}

function renderHistory() {
  $('history-message').hidden = history.length > 0;
  $('history-message').textContent = '书架还是空的，写下你的第一个故事吧。';
  $('history').replaceChildren(...history.map((task) => {
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'history-item';
    button.classList.toggle('active', task.id === selected?.id);
    if (task.id === selected?.id) button.setAttribute('aria-current', 'true');
    const title = document.createElement('strong'); title.textContent = task.title;
    const meta = document.createElement('small');
    const date = document.createElement('span');
    const parsed = new Date(task.createdAt);
    date.textContent = Number.isNaN(parsed.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(parsed);
    const status = document.createElement('span'); status.textContent = labels[task.status] || task.status;
    meta.append(date, status); button.append(title, meta);
    button.addEventListener('click', () => { if (!mutationPending) selectTask(task.id); });
    return button;
  }));
}

async function refreshHistory(reportFailure = false) {
  $('refresh-history').disabled = true;
  try {
    const loaded = await api('/api/tasks');
    if (!Array.isArray(loaded)) throw new Error('故事列表格式错误，请刷新重试。');
    history = loaded;
    // Keep the newest selected snapshot if an earlier list request finishes late.
    if (selected) {
      const index = history.findIndex((task) => task.id === selected.id);
      if (index >= 0) history[index] = selected;
      else history.unshift(selected);
      history = history.slice(0, 30);
    }
    renderHistory();
  } catch (error) {
    if (!history.length) { $('history-message').hidden = false; $('history-message').textContent = '暂时无法打开书架，请点击刷新。'; }
    if (reportFailure) notify(error.message, true);
  } finally { $('refresh-history').disabled = false; }
}

function updateHistory(task) {
  const index = history.findIndex((item) => item.id === task.id);
  if (index >= 0) history[index] = task;
  else history.unshift(task);
  history = history.slice(0, 30);
  renderHistory();
}

function stopSelection() {
  selectionVersion++;
  selectionRequest?.abort();
  selectionRequest = new AbortController();
  clearTimeout(pollTimer);
  pollTimer = null;
  return selectionVersion;
}

function resetPlayer() {
  playbackVersion++;
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  cancelAnimationFrame(frame); frame = null;
  result = null; resultLoadedFor = null;
  wordNodes = []; sentenceNodes = [];
  activeWord = -1; activeSentence = -1; selectedSentence = -1;
  loopEnabled = false;
  $('loop-sentence').setAttribute('aria-pressed', 'false');
  $('player').hidden = true;
  document.body.classList.remove('has-player');
  $('story-reader').replaceChildren();
  $('reading-view').hidden = true;
  $('result-loading').hidden = true;
}

function showComposer() {
  if (mutationPending) return;
  stopSelection(); resetPlayer(); selected = null;
  remember('englishpods.selected', null);
  $('composer').hidden = false;
  $('task-panel').hidden = true;
  $('welcome-note').hidden = false;
  $('page-title').textContent = '在故事里，听懂英语。';
  $('page-subtitle').textContent = '把一段文字，变成可以慢慢听、反复读的故事。';
  notify(''); renderHistory(); $('story-title').focus();
}

function renderTask(task) {
  selected = task;
  updateHistory(task);
  $('composer').hidden = true;
  $('task-panel').hidden = false;
  $('welcome-note').hidden = true;
  $('page-title').textContent = '给故事一点时间。';
  $('page-subtitle').textContent = '从原文到声音，再到每一个清晰的单词。';
  $('task-title').textContent = task.title;
  $('task-status').textContent = labels[task.status] || task.status;
  $('task-status').className = `badge${task.status === 'FAILED' ? ' failed' : ''}`;
  const running = stages.includes(task.status);
  $('generation-progress').hidden = !running;
  const stage = stages.includes(task.stage) ? task.stage : task.status;
  const stageIndex = stages.indexOf(stage);
  $('stage-description').textContent = descriptions[stage] || '正在准备故事';
  for (const node of $('stage-list').children) {
    const index = stages.indexOf(node.dataset.stage);
    node.classList.toggle('done', index < stageIndex);
    node.classList.toggle('current', index === stageIndex);
    if (index === stageIndex) node.setAttribute('aria-current', 'step'); else node.removeAttribute('aria-current');
  }
  const checkpoint = checkpointDescription(task);
  $('checkpoint-detail').hidden = !checkpoint;
  $('checkpoint-detail').textContent = checkpoint;
  $('task-error').hidden = task.status !== 'FAILED';
  $('task-error-detail').textContent = (task.stage ? `出错阶段：${labels[task.stage] || task.stage}。` : '') + (task.error || '未能完成生成，请检查服务配置后重试。');
  const actionable = task.status === 'CREATED' || task.status === 'FAILED';
  $('task-actions').hidden = !actionable;
  $('generate-story').textContent = task.status === 'FAILED' ? '重试生成 →' : '开始生成 →';
  $('task-action-hint').textContent = task.status === 'FAILED' ? '原文已保留，可检查配置后重试。' : '原文已安全保存，服务就绪后即可生成。';
  $('saved-story').hidden = task.status === 'COMPLETED';
  $('saved-text').textContent = task.text;
}

async function selectTask(id, task = null) {
  const version = stopSelection();
  resetPlayer();
  notify('');
  // Immediately move selection so an old completed task cannot remain visible while loading.
  selected = task || history.find((item) => item.id === id) || null;
  if (selected) renderTask(selected);
  remember('englishpods.selected', id);
  try {
    const loaded = task || await api(`/api/tasks/${encodeURIComponent(id)}`, { signal: selectionRequest.signal });
    if (version !== selectionVersion) return;
    renderTask(loaded);
    await continueTask(version);
  } catch (error) {
    if (version !== selectionVersion || error.name === 'AbortError') return;
    notify(error.message, true);
    if (error.status === 404) { remember('englishpods.selected', null); selected = null; $('task-panel').hidden = true; $('composer').hidden = false; renderHistory(); }
  }
}

async function continueTask(version) {
  if (version !== selectionVersion || !selected) return;
  if (selected.status === 'COMPLETED') {
    if (resultLoadedFor === selected.id) return;
    $('result-loading').hidden = false;
    try {
      const loaded = await api(`/api/tasks/${encodeURIComponent(selected.id)}/result`, { signal: selectionRequest.signal });
      if (version !== selectionVersion) return;
      loadResult(loaded);
      resultLoadedFor = selected.id;
      $('page-title').textContent = '把故事，听进心里。';
      $('page-subtitle').textContent = '放慢一点，跟着声音，找到你自己的节奏。';
      $('result-loading').hidden = true;
    } catch (error) {
      if (version !== selectionVersion || error.name === 'AbortError') return;
      $('result-loading').hidden = true;
      notify(`音频时间轴加载失败：${error.message} 请重新选择这个故事以重试。`, true);
    }
    return;
  }
  if (!stages.includes(selected.status)) return;
  clearTimeout(pollTimer);
  pollTimer = setTimeout(async () => {
    if (version !== selectionVersion || !selected) return;
    try {
      const task = await api(`/api/tasks/${encodeURIComponent(selected.id)}`, { signal: selectionRequest.signal });
      if (version !== selectionVersion) return;
      renderTask(task);
      notify('');
    } catch (error) {
      if (version !== selectionVersion || error.name === 'AbortError') return;
      notify(`进度暂时无法更新：${error.message} 页面会自动重试。`, true);
    }
    await continueTask(version);
  }, document.hidden ? 5000 : 1500);
}

async function requestGeneration(id) {
  const config = await refreshConfig();
  if (!config?.ready) {
    $('configuration').open = true;
    notify(config ? '故事已保存。生成服务尚未配置完成，请根据服务详情设置服务器环境变量，再点击“开始生成”。' : '故事已保存。暂时无法确认生成服务状态，请重新检查服务后再生成。');
    return;
  }
  const task = await api(`/api/tasks/${encodeURIComponent(id)}/generate`, { method: 'POST' });
  if (selected?.id !== id) return;
  renderTask(task);
  notify('故事已经加入生成队列。你可以留在这里查看进度，也可以稍后从书架继续。');
  await continueTask(selectionVersion);
}

$('story-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (mutationPending) return;
  const title = $('story-title').value;
  const text = $('story-text').value;
  if (!title.trim() || !text.trim()) { notify('请填写故事标题与英文原文。', true); return; }
  setPending(true); notify('');
  try {
    const task = await api('/api/tasks', { method: 'POST', body: JSON.stringify({ title, text }) });
    $('story-title').value = '';
    $('story-text').value = '';
    updateCount();
    await selectTask(task.id, task);
    await requestGeneration(task.id);
  } catch (error) { notify(error.message, true); }
  finally { setPending(false); }
});

$('generate-story').addEventListener('click', async () => {
  if (mutationPending || !selected || !['CREATED', 'FAILED'].includes(selected.status)) return;
  const id = selected.id;
  setPending(true); notify('');
  try { await requestGeneration(id); }
  catch (error) {
    notify(error.message, true);
    if (error.status === 409) { await selectTask(id); notify('任务状态已经更新，已为你刷新。'); }
  } finally { setPending(false); }
});

function appendSource(parent, text, spans, createNode, start = 0, end = text.length) {
  for (const part of sourceParts(text, spans, start, end)) parent.append(part.span ? createNode(part.span, part.text) : document.createTextNode(part.text));
}

function loadResult(loaded) {
  if (loaded.text !== selected.text || !Array.isArray(loaded.words) || !Array.isArray(loaded.sentences) || !Number.isFinite(loaded.duration) || loaded.duration <= 0) throw new Error('故事时间轴与原文不一致。');
  const audioUrl = new URL(loaded.audioUrl, location.origin);
  if (audioUrl.origin !== location.origin || !audioUrl.pathname.startsWith('/api/tasks/')) throw new Error('音频地址无效。');
  const wordsBySentence = new Map(loaded.sentences.map((sentence) => [sentence.id, []]));
  loaded.words.forEach((word) => {
    if (!wordsBySentence.has(word.sentenceId)) throw new Error('单词所属句子不存在。');
    wordsBySentence.get(word.sentenceId).push(word);
  });
  const fragment = document.createDocumentFragment();
  const wordIndex = new Map(loaded.words.map((word, index) => [word.id, index]));
  const sentenceIndex = new Map(loaded.sentences.map((sentence, index) => [sentence.id, index]));
  appendSource(fragment, loaded.text, loaded.sentences, (sentence) => {
    const node = document.createElement('span'); node.className = 'sentence'; node.tabIndex = 0; node.setAttribute('role', 'group');
    const index = sentenceIndex.get(sentence.id);
    node.setAttribute('aria-label', `第 ${index + 1} 句，点击或按回车点读`);
    const activate = () => seekSentence(index, true);
    node.addEventListener('click', activate);
    node.addEventListener('keydown', (event) => { if (event.target === node && ['Enter', ' '].includes(event.key)) { event.preventDefault(); event.stopPropagation(); activate(); } });
    appendSource(node, loaded.text, wordsBySentence.get(sentence.id), (word, text) => {
      const span = document.createElement('span'); span.className = 'word'; span.tabIndex = 0; span.setAttribute('role', 'button');
      span.textContent = text; span.setAttribute('aria-label', `从 ${text} 开始播放`);
      const activateWord = (event) => { event.stopPropagation(); selectSentence(index); seek(word.start); play(); };
      span.addEventListener('click', activateWord);
      span.addEventListener('keydown', (event) => { if (['Enter', ' '].includes(event.key)) { event.preventDefault(); activateWord(event); } });
      wordNodes[wordIndex.get(word.id)] = span;
      return span;
    }, sentence.sourceStart, sentence.sourceEnd);
    sentenceNodes[index] = node;
    return node;
  });
  if (fragment.textContent !== loaded.text) throw new Error('阅读器无法完整保留原文。');
  result = loaded;
  $('story-reader').replaceChildren(fragment);
  $('reading-view').hidden = false;
  $('player').hidden = false;
  document.body.classList.add('has-player');
  $('player-title').textContent = selected.title;
  $('story-duration').textContent = `${loaded.words.length} 词 · ${formatTime(loaded.duration)}`;
  $('total-time').textContent = formatTime(loaded.duration);
  $('seek').max = loaded.duration;
  $('seek').value = 0;
  $('current-time').textContent = '0:00';
  $('playback-message').textContent = '准备好，听听这个故事';
  audio.src = audioUrl.href;
  audio.playbackRate = Number($('playback-rate').value);
  selectSentence(loaded.sentences.length ? 0 : -1);
  synchronize();
}

function selectSentence(index) {
  if (!result || index < 0 || index >= result.sentences.length) return;
  if (selectedSentence >= 0) sentenceNodes[selectedSentence]?.classList.remove('looping');
  selectedSentence = index;
  sentenceNodes[index]?.classList.toggle('looping', loopEnabled);
  $('sentence-label').textContent = `第 ${index + 1} / ${result.sentences.length} 句`;
  $('previous-sentence').disabled = index === 0;
  $('next-sentence').disabled = index === result.sentences.length - 1;
}

function synchronize() {
  if (!result) return;
  const time = audio.currentTime;
  const word = findActiveIndex(result.words, time);
  if (word !== activeWord) {
    wordNodes[activeWord]?.classList.remove('active');
    wordNodes[word]?.classList.add('active');
    activeWord = word;
  }
  const sentence = findActiveIndex(result.sentences, time);
  if (sentence !== activeSentence) {
    sentenceNodes[activeSentence]?.classList.remove('current');
    sentenceNodes[sentence]?.classList.add('current');
    activeSentence = sentence;
    if (!loopEnabled && sentence >= 0) selectSentence(sentence);
  }
  $('current-time').textContent = formatTime(time);
  $('seek').value = time;
  $('seek').setAttribute('aria-valuetext', `${formatTime(time)} / ${formatTime(result.duration)}`);
}

function enforceLoop(playing = !audio.paused && !audio.ended) {
  if (!result || !loopEnabled) return;
  const target = loopSeekTime(result.sentences[selectedSentence], audio.currentTime, playing);
  if (target !== null) audio.currentTime = target;
}

function tick() {
  frame = null;
  enforceLoop(); synchronize();
  if (!audio.paused && !audio.ended) frame = requestAnimationFrame(tick);
}

function startFrames() { if (frame === null) frame = requestAnimationFrame(tick); }

function seek(time) {
  if (!result) return;
  audio.currentTime = clampTime(time, result.duration);
  synchronize();
}

async function play() {
  if (!result) return;
  const version = playbackVersion;
  enforceLoop(true);
  try { await audio.play(); }
  catch (error) { if (version !== playbackVersion || error.name === 'AbortError') return; $('playback-message').textContent = '暂时无法播放，请再点击播放重试'; notify('音频播放失败，请确认音频文件可用并重试。', true); }
}

function seekSentence(index, shouldPlay) {
  if (!result || index < 0 || index >= result.sentences.length) return;
  selectSentence(index); seek(result.sentences[index].start);
  if (shouldPlay) play();
}

$('play-pause').addEventListener('click', () => audio.paused ? play() : audio.pause());
$('backward').addEventListener('click', () => seek(audio.currentTime - 5));
$('forward').addEventListener('click', () => seek(audio.currentTime + 5));
$('seek').addEventListener('input', () => seek(Number($('seek').value)));
$('playback-rate').addEventListener('change', () => { audio.playbackRate = Number($('playback-rate').value); remember('englishpods.rate', $('playback-rate').value); });
$('loop-sentence').addEventListener('click', () => {
  if (!result || !result.sentences.length) return;
  loopEnabled = !loopEnabled;
  $('loop-sentence').setAttribute('aria-pressed', String(loopEnabled));
  if (selectedSentence < 0) selectSentence(0);
  sentenceNodes[selectedSentence]?.classList.toggle('looping', loopEnabled);
  $('playback-message').textContent = loopEnabled ? '单句循环已开启 · 点击其他句子切换' : audio.paused ? '已暂停，随时继续' : '跟着声音，慢慢读';
  enforceLoop(); synchronize();
});
$('previous-sentence').addEventListener('click', () => seekSentence(selectedSentence - 1, true));
$('next-sentence').addEventListener('click', () => seekSentence(selectedSentence + 1, true));
$('replay-sentence').addEventListener('click', () => seekSentence(selectedSentence, true));

audio.addEventListener('play', () => { $('play-icon').textContent = 'Ⅱ'; $('play-pause').setAttribute('aria-label', '暂停'); $('playback-message').textContent = loopEnabled ? '单句循环中' : '跟着声音，慢慢读'; startFrames(); });
audio.addEventListener('playing', () => { $('playback-message').textContent = loopEnabled ? '单句循环中' : '跟着声音，慢慢读'; startFrames(); });
audio.addEventListener('pause', () => { $('play-icon').textContent = '▶'; $('play-pause').setAttribute('aria-label', '播放'); $('playback-message').textContent = '已暂停，随时继续'; cancelAnimationFrame(frame); frame = null; synchronize(); });
audio.addEventListener('waiting', () => { $('playback-message').textContent = '正在缓冲音频…'; synchronize(); });
audio.addEventListener('error', () => { if (!result || !audio.getAttribute('src')) return; $('playback-message').textContent = '音频加载失败，请重新选择故事'; notify('音频无法加载，请检查服务器或重新选择故事。', true); });
audio.addEventListener('ended', () => {
  if (loopEnabled && result) { enforceLoop(true); synchronize(); play(); }
  else { synchronize(); $('playback-message').textContent = '听完了，再听一次会有新发现'; }
});
for (const event of ['timeupdate', 'seeked', 'seeking', 'ratechange', 'loadedmetadata']) audio.addEventListener(event, () => { enforceLoop(); synchronize(); });

document.addEventListener('keydown', (event) => {
  if (!result || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
  const target = event.target;
  if (target.closest('input, textarea, select, button, a, [contenteditable="true"], [role="button"], .sentence')) return;
  if (event.code === 'Space') { event.preventDefault(); audio.paused ? play() : audio.pause(); }
  else if (event.key === 'ArrowLeft') { event.preventDefault(); seek(audio.currentTime - 5); }
  else if (event.key === 'ArrowRight') { event.preventDefault(); seek(audio.currentTime + 5); }
});

$('new-story').addEventListener('click', showComposer);
$('refresh-history').addEventListener('click', () => refreshHistory(true));
$('refresh-config').addEventListener('click', () => refreshConfig(true));
$('load-example').addEventListener('click', () => {
  $('story-title').value = 'Back to Sam’s Bookstore'; $('story-text').value = example;
  updateCount(); notify('已载入书店示例。你可以编辑原文，再保存生成。');
});
function updateCount() { $('character-count').textContent = `${$('story-text').value.length.toLocaleString('zh-CN')} 字符`; }
$('story-text').addEventListener('input', updateCount);

const rate = stored('englishpods.rate');
if (['0.5', '0.75', '1', '1.25', '1.5', '2'].includes(rate)) $('playback-rate').value = rate;
await Promise.allSettled([refreshConfig(), refreshHistory()]);
const restored = stored('englishpods.selected');
if (restored && selectionVersion === 0) await selectTask(restored);

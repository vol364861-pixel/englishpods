import test from 'node:test';
import assert from 'node:assert/strict';
import { checkpointDescription } from '../../main/resources/static/task-progress.mjs';

test('failed synthesis exposes saved review and current retry reuse without claiming task success', () => {
  const detail = checkpointDescription({ status: 'FAILED', completedStage: 'REVIEWING', reusedStage: 'REVIEWING' });
  assert.match(detail, /分角复核已保存/);
  assert.match(detail, /本次已复用两轮分角/);
  assert.doesNotMatch(detail, /可以收听|生成完成/);
});
test('initial-only checkpoint and old imports describe retry boundaries', () => {
  const detail = checkpointDescription({ completedStage: 'SEGMENTING', checkpointOrigin: 'LEGACY_ATTEMPT_2_CONFIG_UNKNOWN' });
  assert.match(detail, /初次分角已保存/);
  assert.doesNotMatch(detail, /分角复核已保存/);
  assert.match(detail, /历史模型配置不可追溯/);
  assert.match(detail, /校验原文及分角配置/);
});
test('unmigrated and corrupt checkpoint summaries do not claim saved LLM results', () => {
  assert.equal(checkpointDescription({}), '');
  assert.equal(checkpointDescription({ completedStage: 'SYNTHESIZING' }), '');
});

export function checkpointDescription(task) {
  const completed = task.completedStage === 'REVIEWING' ? '初次分角和分角复核已保存' : task.completedStage === 'SEGMENTING' ? '初次分角已保存' : '';
  if (!completed) return '';
  const reused = task.reusedStage === 'REVIEWING' ? '本次已复用两轮分角' : task.reusedStage === 'SEGMENTING' ? '本次已复用初次分角' : '';
  const legacy = task.checkpointOrigin?.startsWith('LEGACY_') ? '旧记录已按当前原文校验导入；历史模型配置不可追溯' : '';
  return [completed, reused, legacy, '重试时将校验原文及分角配置，匹配后跳过已完成的分角调用。'].filter(Boolean).join('。');
}

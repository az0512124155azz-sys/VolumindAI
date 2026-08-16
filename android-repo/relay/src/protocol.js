export const allowedTypes = new Set([
  'chat.command', 'chat.message', 'build.plan', 'build.step', 'build.stop',
  'fusion.screenshot', 'questionnaire', 'questionnaire.answer', 'presence', 'error'
]);

export function isAllowedMessage(message) {
  return Boolean(message && typeof message === 'object' && allowedTypes.has(message.type));
}

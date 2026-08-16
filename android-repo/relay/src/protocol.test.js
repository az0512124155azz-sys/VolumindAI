import test from 'node:test';
import assert from 'node:assert/strict';
import { isAllowedMessage } from './protocol.js';

test('allows protocol messages used by the mobile app', () => {
  assert.equal(isAllowedMessage({type:'chat.command', text:'build a stand'}), true);
  assert.equal(isAllowedMessage({type:'fusion.screenshot', url:'https://example.test/shot'}), true);
  assert.equal(isAllowedMessage({type:'presence', mobileConnected:true}), true);
});

test('rejects unknown and malformed messages', () => {
  assert.equal(isAllowedMessage({type:'run.shell'}), false);
  assert.equal(isAllowedMessage(null), false);
});

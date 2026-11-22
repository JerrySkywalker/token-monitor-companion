import { openStore } from './store.mjs';
import { httpOrigin } from './server.mjs';
import QRCode from 'qrcode';

const [command, kindOrDevice, output, file] = process.argv.slice(2);
const store = openStore(process.env.COMPANION_DB ?? './data/companion.db');
try {
  if (command === 'issue' && ['phone', 'wear'].includes(kindOrDevice) && (!output || ['json', 'code'].includes(output) || (output === 'png' && kindOrDevice === 'phone' && file))) {
    const url = httpOrigin(process.env.PUBLIC_URL, process.env.COMPANION_ALLOW_INSECURE === '1');
    const { code, expires } = store.issue(kindOrDevice);
    const uri = 'companion://pair?v=1&' + new URLSearchParams({ url, code, kind: kindOrDevice });
    if (output === 'json') console.log(JSON.stringify({ url, code, expires, uri }));
    else if (output === 'code') console.log(code);
    else if (output === 'png') await QRCode.toFile(file, uri, { width: 560, margin: 5, errorCorrectionLevel: 'M' });
    else {
      if (kindOrDevice === 'phone') console.log(await QRCode.toString(uri, { type: 'terminal', small: true }));
      console.log('Server: ' + url + '\nOne-time code: ' + code + '\nExpires: ' + new Date(expires).toISOString());
    }
  } else if (command === 'devices' && !kindOrDevice) {
    console.table(store.devices());
  } else if (command === 'revoke' && kindOrDevice && !output) {
    if (!store.revoke(kindOrDevice)) throw new Error('Device not found');
  } else {
    throw new Error('Use issue phone|wear [json|code], issue phone png <file>, devices, or revoke <device-id>');
  }
} finally {
  store.close();
}

"""One-shot native Stop safeguard; never starts acquisition or opens a port."""
from datetime import datetime, timezone
from pathlib import Path
import json
import sys
import time
from ui import BASE, application, action, serial_descriptors

record_path = BASE / sys.argv[1]
record = json.loads(record_path.read_text())
time.sleep(max(0, record['deadline_unix'] - time.time()))
pid = record['process_id']
result = {'checked_at_utc': datetime.now(timezone.utc).isoformat(),
          'process_id': pid, 'action': 'none'}
try:
    ticks = (Path('/proc') / str(pid) / 'stat').read_text().split()[21]
    if ticks != record['process_start_ticks']:
        result['reason'] = 'Process identity changed; no action.'
    elif not serial_descriptors(pid):
        result['reason'] = 'No serial descriptor; no action.'
    else:
        result['action'] = 'native Stop gathering'
        result['action_result'] = action(application(pid), 'Stop gathering')
except Exception as error:
    result['error'] = repr(error)
(BASE / record['deadline_evidence']).write_text(json.dumps(result, indent=2) + '\n')

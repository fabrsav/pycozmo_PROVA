"""Wait for fresh evidence of a real robot session, not merely a successful UI click."""
import json
import subprocess
import time

serial='100.94.42.57:5555'
deadline=time.monotonic()+40
minimum=int(time.time()*1000)-12000
last_status='Nessun tentativo recente'
while time.monotonic()<deadline:
    r=subprocess.run(['adb','-s',serial,'shell','run-as','dev.s200.cozmo','tail','-60','files/events.ndjson'],capture_output=True,text=True,timeout=5)
    try:
        entries=[json.loads(line) for line in r.stdout.splitlines() if line.strip()]
    except ValueError:
        entries=[]
    fresh=[e for e in entries if e.get('time',0)>=minimum]
    statuses=[e for e in fresh if e['kind']=='status']
    if statuses:
        last_status=statuses[-1]['message']
    telemetry=[e for e in fresh if e['kind']=='telemetry']
    if last_status.startswith('Connesso') and telemetry:
        print('Robot connected with fresh telemetry:',telemetry[-1]['message'])
        raise SystemExit(0)
    time.sleep(.5)
raise SystemExit('Real connection not confirmed: '+last_status)

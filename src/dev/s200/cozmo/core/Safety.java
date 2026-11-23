package dev.s200.cozmo.core;

/** Local motion leases. All times supplied by a monotonic clock, in milliseconds. */
public final class Safety {
    public static final float LOW_BATTERY_WARN_V = 3.50f;
    public static final float LOW_BATTERY_SHUTDOWN_V = 3.25f;
    public static final long LOW_BATTERY_HOLD_MS = 1500;
    private long lastState=-1, deadline=0, armUntil=0, lowBatterySince=-1; private Wire.Telemetry state;
    public void update(Wire.Telemetry value,long now) {
        state=value;lastState=now;
        if(value.unsafe()) disarm();
        if(value.onCharger()||value.battery<=0f||value.battery>=LOW_BATTERY_SHUTDOWN_V) lowBatterySince=-1;
        else if(lowBatterySince<0) lowBatterySince=now;
    }
    public boolean lowBatteryWarning() { return state!=null&&!state.onCharger()&&state.battery>0f&&state.battery<LOW_BATTERY_WARN_V; }
    public boolean mustShutdownLowBattery(long now) { return lowBatterySince>=0&&now>=lowBatterySince&&now-lowBatterySince>=LOW_BATTERY_HOLD_MS; }
    public boolean fresh(long now) { return lastState>=0&&now>=lastState&&now-lastState<350; }
    public boolean ready(long now) {return fresh(now)&&state!=null&&!state.unsafe();}
    public boolean startupHeadAllowed(long now) {
        if (!ready(now)) return false;
        boolean headBitOk = state.compactTelemetry || (state.status & 0x200) != 0;
        return state.onCharger() && headBitOk;
    }
    public boolean arm(long now) { if(!ready(now))return false; armUntil=now+60000;return true; }
    public boolean permit(long now,boolean wheels) {return now<armUntil&&ready(now)&&(!wheels||!state.onCharger());}
    public void lease(long now) {deadline=now+250;}
    public void jointLease(long now) {deadline=now+2200;}
    public void startupHeadLease(long now) {deadline=now+1700;}
    public boolean mustStop(long now) {return deadline>0&&(now>=deadline||!ready(now));}
    public void stopped() {deadline=0;}
    public void disarm() {armUntil=0;}
    public boolean armed(long now) {return now<armUntil&&ready(now);}
    public long armRemainingMs(long now) {
        long rem = armUntil - now;
        return rem > 0 ? rem : 0;
    }

    /** Restituisce stringa vuota "" se i movimenti giunti (testa/carrello) sono consentiti, altrimenti il motivo in italiano. */
    public String jointBlockReason(long now, boolean visualOnly, boolean factoryFirmware) {
        if (visualOnly) return "Sessione solo visiva: motori disabilitati";
        if (factoryFirmware) return "Firmware di fabbrica attivo: ripristina firmware 2381";
        if (state == null || !fresh(now)) return "Telemetria robot non ancora pronta";
        if (state.unsafe()) return "Blocco sicurezza sensori (sollevato / caduta / bordo)";
        if (now >= armUntil) return "Motori bloccati: premi Abilita motori (60 s)";
        return "";
    }

    /** Restituisce stringa vuota "" se i cingoli sono consentiti, altrimenti il motivo in italiano. */
    public String wheelBlockReason(long now, boolean visualOnly, boolean factoryFirmware) {
        String base = jointBlockReason(now, visualOnly, factoryFirmware);
        if (!base.isEmpty()) return base;
        if (state != null && state.onCharger()) return "Cingoli bloccati: Cozmo è sulla basetta di ricarica";
        return "";
    }

    public static float limit(float value,float min,float max) {
        if(!Float.isFinite(value))throw new IllegalArgumentException("Invalid motion value");return Math.max(min,Math.min(max,value));
    }
}

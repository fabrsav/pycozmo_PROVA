package dev.s200.cozmo.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Phone-local UDP engine. No Android dependency; runs against a real socket in host tests. */
public final class Link implements Runnable {
    public enum InitState { DISCONNECTED, HANDSHAKE_SENT, CONNECTED_AWAITING_INIT, ENABLED, INITIALIZED }
    public interface Binder { void bind(DatagramSocket socket) throws Exception; }
    public interface Observer {
        void event(String kind,String text);
        void state(Wire.Telemetry state,long count);
        void packet(Wire.Packet packet);
    }
    private final Binder binder; private final Observer observer; private final String host; private final int port;
    private final Reliable reliable=new Reliable(); private final Safety safety=new Safety();
    private final Deque<Runnable> requests=new ArrayDeque<>();
    private final Deque<Wire.Packet> queue=new ArrayDeque<>();
    private final Deque<byte[]> audioQueue=new ArrayDeque<>();
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final boolean visualOnly;
    private volatile boolean running=false,handshake=false;
    private volatile DatagramSocket socket;
    private volatile String phase="In attesa";
    private volatile boolean motionArmed=false;
    private volatile InitState initState=InitState.DISCONNECTED;
    private volatile String initPath="none";
    private volatile long connectReadyMs=-1,lastAnimStateMs=0;
    private volatile int queuedAudioFramesCount=0;
    private boolean initialized=false,firmwareEnabled=false,enableSent=false,robotInitialized=false,syncRetrySent=false,signatureSeen=false,animationStateEnabled=false;
    private boolean startupHeadEnabled=false,startupHeadDone=false;
    private boolean binaryVersionSeen;
    private volatile boolean autoShutdownLowBattery=true;
    private final AtomicBoolean shutdownRequested=new AtomicBoolean();
    private volatile String shutdownReason="Spegnimento richiesto";
    private boolean shutdownSent=false;
    private long shutdownSentAt=0;
    private volatile int initialVolume=32767;
    private volatile int audioPlayedCount=0,animBytesPlayedCount=0,animDropsCount=0,audioSentFrames=0;
    private byte[] pendingDisplay=null;
    private byte[] visualProbeImage;
    private boolean visualProbeTagged;
    private int visualProbeFrame;
    private long visualProbeNextAt;
    private int animationDebugCount;
    private volatile int animationTracks;
    private volatile boolean factoryFirmware;
    private volatile FirmwareRecovery firmwareRecovery;
    private long firstState=0,lastAudioTick=0;
    private Wire.Telemetry latestState;
    private volatile long lastState=0;
    private long lastRx=0,lastPing=0,lastReset=0,lastResend=0,start=0,states=0,rx=0,tx=0,retries=0,bad=0,lastSummary=0,handshakeAt=0,warmTriggerAt=0,enableSentAt=0,initializedAt=0;
    private long animBase=-1,audioBase=-1,animBytesPlayed=0,audioFramesPlayed=0,lastPlayedMs=0;
    private long displayBytesSent=0,lastDisplayRleMs=0; private int displayFramesSent=0; private byte[] lastDisplayRle=null;
    private int pingCounter=0,resends=0,initTraceCount=0,rxTraceCount=0,telemetryErrorLogged=0,animTraceCount=0,displayTraceCount=0;

    public Link(String host,int port,Binder binder,Observer observer) {
        this(host, port, binder, observer, false);
    }
    public Link(String host,int port,Binder binder,Observer observer,boolean visualOnly) {
        this.host=host;this.port=port;this.binder=binder;this.observer=observer;this.visualOnly=visualOnly;
    }
    public boolean isVisualOnly() {return visualOnly;}
    public boolean isFactoryFirmware() {return factoryFirmware;}
    public boolean isRecoveringFirmware() {
        FirmwareRecovery recovery = firmwareRecovery;
        return recovery != null && recovery.active();
    }
    public void recoverFirmware2381(byte[] image) {
        FirmwareRecovery recovery = new FirmwareRecovery(image);
        request(() -> {
            Wire.Telemetry current = latestState;
            if (!visualOnly || !factoryFirmware || signatureSeen || !initialized
                    || stateAge() >= 350 || firstState == 0 || now() - firstState < 8000
                    || current == null || !current.onCharger() || current.unsafe()
                    || reliable.pendingCount() != 0 || !queue.isEmpty() || firmwareRecovery != null) {
                observer.event("firmware_recovery", "Avvio sospeso · servono modalità di fabbrica, basetta e collegamento stabile");
                return;
            }
            visualProbeImage = null;
            firmwareRecovery = recovery;
            observer.event("firmware_recovery", "Avvio ripristino 2381 sul robot in modalità di fabbrica");
        });
    }
    public static long now() {return System.nanoTime()/1000000;}
    public void setStartupHeadEnabled(boolean enabled){startupHeadEnabled=enabled;}
    public void setAutoShutdownLowBattery(boolean enabled){autoShutdownLowBattery=enabled;}
    public boolean isAutoShutdownLowBattery(){return autoShutdownLowBattery;}
    public void shutdownRobot(String reason){if(reason!=null&&!reason.isEmpty())shutdownReason=reason;shutdownRequested.set(true);}
    public void setInitialVolume(int vol){initialVolume=Math.max(0,Math.min(65535,vol));}
    public String phase() {return phase;}
    public InitState initState() {return initState;}
    public String initPath() {return initPath;}
    public long connectReadyMs() {return connectReadyMs;}
    public int getQueuedAudioFrames() {return queuedAudioFramesCount;}
    public long getLastAnimStateMs() {return lastAnimStateMs;}
    public boolean armed() {return motionArmed;}
    public long stateAge() {return lastState==0?Long.MAX_VALUE:now()-lastState;}
    public int audioFramesPlayed() {return audioPlayedCount;}
    public int animBytesPlayed() {return animBytesPlayedCount;}
    public int animDrops() {return animDropsCount;}
    public int animTracks() {return animationTracks;}
    public int audioFramesSent() {return audioSentFrames;}
    private void status(String text) {phase=text;observer.event("status",text);}
    private void request(Runnable r) {
        synchronized(requests) {
            if(!running||firmwareRecovery!=null||requests.size()>=48) {observer.event("rejected","Collegamento assente o coda piena");return;}
            requests.add(r);
        }
    }
    private void enqueue(Wire.Packet p) {
        if(queue.size()>=48) {observer.event("rejected","Coda protocollo piena");return;}
        queue.add(p);
    }
    public void arm() {
        if (visualOnly) {
            observer.event("rejected", "Movimenti bloccati nella sessione visiva");
            return;
        }
        request(()->{
            long n=now();
            motionArmed=safety.arm(n);
            if (motionArmed) {
                observer.event("motion","Movimenti abilitati per 60 secondi");
            } else {
                observer.event("rejected", safety.jointBlockReason(n, visualOnly, factoryFirmware));
            }
        });
    }
    public void drive(float left,float right) {
        request(()->{
            long n=now();
            if(!robotInitialized||states==0) {observer.event("rejected","Robot non ancora inizializzato");return;}
            String block = safety.wheelBlockReason(n, visualOnly, factoryFirmware);
            if(!block.isEmpty()) {observer.event("rejected", block); return;}
            enqueue(Wire.command(0x32,Wire.floats(Safety.limit(left,-40,40),Safety.limit(right,-40,40),100,100)));
            safety.lease(n);observer.event("motion","Impulso cingoli, massimo 250 ms");
        });
    }
    public void head(float angle) {joint(0x37,Safety.limit(angle,-0.4363323f,0.65f));}
    public void lift(float height) {joint(0x36,Safety.limit(height,32,75));}
    private void joint(int id,float target) {
        request(()->{
            startupHeadDone=true;
            long n=now();
            if(!robotInitialized||states==0) {observer.event("rejected","Robot non ancora inizializzato");return;}
            String block = safety.jointBlockReason(n, visualOnly, factoryFirmware);
            if(!block.isEmpty()) {observer.event("rejected", block); return;}
            ByteBuffer b=Wire.le(17);b.put(Wire.floats(target,1,4,0.6f)).put((byte)0);
            enqueue(Wire.command(id,b.array()));safety.jointLease(n);
            observer.event("motion","Comando "+(id==0x37?"testa":"carrello")+" target="+target);
        });
    }
    public void camera(boolean enable) {request(()->{
        if(!initialized) {observer.event("rejected","Attendi l'inizializzazione del robot");return;}
        enqueue(Wire.command(0x66,new byte[]{0}));enqueue(Wire.command(0x4c,new byte[]{(byte)(enable?1:0),4}));
    });}
    public void display(boolean[] pixels) {
        displayEncoded(Wire.display(pixels));
    }
    public void displayEncoded(byte[] data) {
        if(data==null||data.length>FaceRenderer.MAX_RLE_BYTES)return;
        final byte[] copy=data.clone();
        request(()->{
            if(!initialized||visualProbeImage!=null||reliable.pendingCount()>10)return;
            long n=now();
            if(lastDisplayRle!=null&&Arrays.equals(lastDisplayRle,copy)&&n-lastDisplayRleMs<3000)return;
            Wire.Packet image = Wire.command(0x97, copy);
            boolean includeSilence = !visualOnly || signatureSeen;
            int counted = copy.length + (includeSilence ? 2 : 1);
            if(!displayFlowAllowed(counted,n))return;
            lastDisplayRle=copy;lastDisplayRleMs=n;
            if(!audioQueue.isEmpty()) {
                pendingDisplay=copy;
            } else {
                // This firmware rejected OutputSilence during the visual-only probe.
                if (includeSilence) enqueue(Wire.command(0x8f));
                enqueue(image);
                displayBytesSent+=counted;displayFramesSent++;
            }
            if(displayTraceCount<10){
                displayTraceCount++;
                observer.event("display_trace","payload="+data.length+" counted="+counted+" bytesSent="+displayBytesSent+" played="+animBytesPlayed+" frames="+displayFramesSent+"/"+audioFramesPlayed);
            }
        });
    }
    private boolean displayFlowAllowed(int counted,long n){
        if(!animationStateEnabled)return true;
        if(lastPlayedMs>0&&n-lastPlayedMs>5000){
            displayBytesSent=0;displayFramesSent=0;animBytesPlayed=0;audioFramesPlayed=0;lastPlayedMs=0;
        }
        long unplayed=displayBytesSent-animBytesPlayed;if(unplayed<0)unplayed=0;
        int ahead=displayFramesSent-(int)audioFramesPlayed;if(ahead<0)ahead=0;
        return unplayed+counted<=800&&(visualOnly||ahead<3);
    }
    public void playAudio(List<byte[]> frames) {
        if (visualOnly) {
            observer.event("rejected", "Audio bloccato nella sessione visiva");
            return;
        }
        if(frames==null||frames.isEmpty())return;
        request(()->{
            if(!initialized) {observer.event("rejected","Attendi l'inizializzazione del robot");return;}
            audioQueue.clear();
            for(byte[] f:frames) {
                if(f!=null&&f.length==Wire.AUDIO_FRAME_SAMPLES&&audioQueue.size()<180) audioQueue.add(f.clone());
            }
            queuedAudioFramesCount=audioQueue.size();
            observer.event("audio","Avvio vocalizzo Cozmo: "+audioQueue.size()+" frame u-law (22050 Hz)");
        });
    }
    public void setVolume(int level) {
        final int clamped=visualOnly?0:Math.max(0,Math.min(65535,level));
        initialVolume=clamped;
        request(()->{
            if(!initialized||visualOnly)return;
            enqueue(Wire.setVolume(clamped));
            observer.event("audio","Volume altoparlante Cozmo impostato: "+(clamped*100/65535)+"% ("+clamped+")");
        });
    }
    public void setHeadLight(boolean enable) {
        request(()->{
            if(!initialized) {observer.event("rejected","Attendi l'inizializzazione del robot");return;}
            enqueue(Wire.setHeadLight(enable));
            observer.event("light","Luce frontale Cozmo: "+(enable?"attiva":"spenta"));
        });
    }
    public void setBackpackColor(String preset) {
        final String p=preset==null?"cyan":preset.toLowerCase(Locale.ROOT);
        request(()->{
            if(!initialized)return;
            byte[] c;
            switch(p) {
                case "green": c=Wire.solidLight(0,255,60); break;
                case "amber": c=Wire.solidLight(255,170,0); break;
                case "purple": c=Wire.solidLight(190,40,255); break;
                case "red": c=Wire.solidLight(255,30,30); break;
                case "off": c=Wire.solidLight(0,0,0); break;
                case "cyan":
                default: c=Wire.solidLight(0,220,255); break;
            }
            byte[] off=Wire.solidLight(0,0,0);
            if (visualOnly) enqueue(Wire.selectUserBackpackLayer());
            enqueue(Wire.backpackCenter(c,c,c));
            enqueue(Wire.backpackSide(off,off));
            observer.event("light","Luci zaino Cozmo: "+p);
        });
    }
    public void diagBackpack98(){
        request(()->{
            if(!initialized)return;
            enqueue(animationBackpack(255,30,30));
            observer.event("diag","0x98 AnimBackpackLights inviato");
        });
    }
    private static Wire.Packet animationBackpack(int red, int green, int blue) {
        short color = (short) Wire.rgbToLed16(red, green, blue);
        ByteBuffer lights = Wire.le(10);
        for (int i = 0; i < 5; i++) lights.putShort(color);
        return Wire.command(0x98, lights.array());
    }
    public void probeVisualPlayer(boolean tagged) {
        request(() -> {
            if (!visualOnly || !initialized || stateAge() >= 350 || visualProbeImage != null) {
                observer.event("rejected", "Prova visiva disponibile con telemetria, movimento e audio disattivati");
                return;
            }
            // Two rectangles at columns 27..49 and 78..100, rows 6..25.
            // Verified with the reference PyCozmo RLE decoder.
            visualProbeImage = new byte[]{9, 0, 0x1a, (byte) 0x94, (byte) 0xcd, 0x55,
                0x1b, (byte) 0x94, (byte) 0xcd, 0x55, 0x1a};
            visualProbeTagged = tagged;
            visualProbeFrame = 0;
            visualProbeNextAt = now();
            animTraceCount = 0;
            animationDebugCount = 0;
            observer.event("visual_probe", "Inizio · tracce=" + animationTracks + " · tag=" + tagged
                + " · byte OLED=" + visualProbeImage.length);
        });
    }
    private void tickVisualProbe(long now) throws Exception {
        if (visualProbeImage == null || now < visualProbeNextAt) return;
        if (stateAge() >= 350 || emergency.get()) {
            visualProbeImage = null;
            observer.event("visual_probe", "Interrotta");
            return;
        }
        if (!queue.isEmpty() || reliable.pendingCount() > 8) return;
        if (visualProbeFrame == 154) {
            visualProbeImage = null;
            observer.event("visual_probe", "Fine invio · byte=" + animBytesPlayedCount
                + " · frame=" + audioPlayedCount + " · tracce=" + animationTracks + " · drop=" + animDropsCount);
            return;
        }
        List<Wire.Packet> frame = new ArrayList<>(3);
        if (visualProbeFrame == 0) {
            frame.add(Wire.command(0x9f));
        } else if (visualProbeFrame == 1) {
            frame.add(Wire.enableVisualAnimationTracks());
        } else {
            // PyCozmo starts each frame with a zero-payload silence marker.
            frame.add(Wire.command(0x8f));
            if (visualProbeFrame == 2 && visualProbeTagged) {
                frame.add(Wire.command(0x9b, new byte[]{1}));
            } else if (visualProbeFrame >= 3 && visualProbeFrame <= 123 && (visualProbeFrame - 3) % 30 == 0) {
                frame.add(Wire.command(0x97, visualProbeImage));
                if (visualProbeFrame == 3) frame.add(animationBackpack(0, 255, 60));
            } else if (visualProbeFrame == 152 && visualProbeTagged) {
                frame.add(Wire.command(0x9a));
            }
        }
        if (reliable.pendingCount() == 0) lastResend = now;
        for (Wire.Packet packet : frame) reliable.enqueue(packet);
        sendRaw(Wire.frame(7, frame.get(0).seq, frame.get(frame.size() - 1).seq, reliable.lastReceived, frame));
        visualProbeFrame++;
        visualProbeNextAt = now + (visualProbeFrame == 154 ? 170 : 33);
    }
    public void sendPackets(List<Wire.Packet> pkts, String tag, String description) {
        if(pkts==null||pkts.isEmpty())return;
        request(()->{
            if(!initialized)return;
            for(Wire.Packet p:pkts) enqueue(p);
            if(tag!=null&&description!=null) observer.event(tag,description);
        });
    }
    private final AtomicBoolean emergency=new AtomicBoolean();
    public void stopMotion() {emergency.set(true);}
    private void doStop(boolean disarm) throws Exception {
        queue.removeIf(p->(p.id>=0x32&&p.id<=0x39)||p.id==0x8e);
        audioQueue.clear();
        queuedAudioFramesCount=0;
        pendingDisplay=null;
        visualProbeImage=null;
        reliable.cancelMotion();
        if(disarm) {safety.disarm();motionArmed=false;startupHeadDone=true;}
        safety.stopped();
        // Replace unacknowledged motor packets before resending: an expired command must not be retried.
        for(Wire.Packet p:reliable.pending()) sendPacket(p);
        if(!reliable.full()) sendPacket(reliable.enqueue(Wire.command(0x3b)));
        observer.event("stop","Arresto inviato; conferma fisica da verificare");
    }
    public void close() {stopping.set(true);}
    public void run() {
        start=now();running=true;
        initState=InitState.HANDSHAKE_SENT;initPath="none";connectReadyMs=-1;
        enableSent=false;robotInitialized=false;syncRetrySent=false;firmwareEnabled=false;initialized=false;signatureSeen=false;binaryVersionSeen=false;animationStateEnabled=false;
        handshakeAt=0;warmTriggerAt=0;enableSentAt=0;initializedAt=0;rxTraceCount=0;telemetryErrorLogged=0;animTraceCount=0;displayTraceCount=0;
        animBase=-1;audioBase=-1;animBytesPlayed=0;audioFramesPlayed=0;lastPlayedMs=0;displayBytesSent=0;displayFramesSent=0;lastDisplayRle=null;lastDisplayRleMs=0;
        try(DatagramSocket s=new DatagramSocket(null)) {
            socket=s;
            binder.bind(s);
            s.bind(new InetSocketAddress(0));s.connect(InetAddress.getByName(host),port);s.setSoTimeout(10);
            status("Handshake in corso");
            sendRaw(Wire.frame(1,0,0,Wire.OOB,Collections.emptyList()));
            lastReset=now();
            byte[] buffer=new byte[65507];
            while(!stopping.get()) {
                long n=now();
                if(!handshake&&n-lastReset>=500&&n-start<=8000){
                    sendRaw(Wire.frame(1,0,0,Wire.OOB,Collections.emptyList()));lastReset=n;
                }
                if(handshake&&!enableSent&&handshakeAt>0&&n-handshakeAt>=700){
                    sendEnable(warmTriggerAt>0?"warm_trigger":"fallback_timer","FirmwareSignature assente dopo 700 ms: invio Enable (0x25)");
                }
                if(handshake&&enableSent&&!robotInitialized&&enableSentAt>0&&n-enableSentAt>=1500){
                    initializeRobot("fallback_timer","BodyInfo assente dopo 1500 ms: avvio inizializzazione diretta");
                }
                if(handshake&&robotInitialized&&states==0&&!syncRetrySent&&initializedAt>0&&n-initializedAt>=2500){
                    syncRetrySent=true;
                    observer.event("init_retry_sync","Nessun 0xf0 dopo 2500 ms da initializeRobot: singolo retry Enable + SyncTime");
                    enqueue(Wire.command(0x25));
                    enqueue(Wire.command(0x45,Wire.le(20).putInt(0).putInt(0).putInt(1).putFloat(0).putFloat(0).array()));
                    enqueue(Wire.command(0x4b,Wire.le(12).putInt(0).putInt(0).putInt(0).array()));
                }
                if(emergency.getAndSet(false)&&handshake&&firmwareRecovery==null)doStop(true);
                if(firmwareRecovery==null&&safety.mustStop(n))doStop(false);
                if(handshake&&!shutdownSent&&firmwareRecovery==null&&(shutdownRequested.getAndSet(false)||(autoShutdownLowBattery&&safety.mustShutdownLowBattery(n)))){
                    shutdownSent=true;shutdownSentAt=n;
                    String msg=safety.mustShutdownLowBattery(n)&&latestState!=null
                        ?String.format(Locale.ITALY,"Batteria critica (%.2f V): spegnimento automatico di protezione",latestState.battery)
                        :shutdownReason;
                    doStop(true);
                    if(!reliable.full())sendPacket(reliable.enqueue(Wire.shutdownRobot()));
                    observer.event("shutdown",msg);
                    status(msg);
                }
                if(shutdownSent&&n-shutdownSentAt>=450)break;
                if(!visualOnly&&startupHeadEnabled&&!startupHeadDone&&robotInitialized&&firstState>0&&n-firstState>=600){
                    if(n-firstState>5000)startupHeadDone=true;
                    else if(safety.startupHeadAllowed(n)){
                        startupHeadDone=true;
                        if(latestState.head<0.14f){
                            byte[] data=Wire.le(17).put(Wire.floats(0.18f,0.8f,2f,1.2f)).put((byte)0).array();
                            enqueue(Wire.command(0x37,data));safety.startupHeadLease(n);
                            observer.event("startup_head","Sollevo dolcemente la testa sulla basetta: target=0.18 rad");
                        }
                    }
                }
                motionArmed=safety.armed(n);
                synchronized(requests) { while(!requests.isEmpty())requests.removeFirst().run(); }
                FirmwareRecovery recovery = firmwareRecovery;
                if (recovery != null) {
                    if (!recovery.active()) {
                        // ACK the final reliable report while the robot reboots.
                        byte[] ping = Wire.le(17).putDouble(n / 1000.0).putInt(pingCounter++).putInt(0).put((byte) 0).array();
                        sendRaw(Wire.frame(11, Wire.OOB, Wire.OOB, reliable.lastReceived,
                            Collections.singletonList(new Wire.Packet(11, -1, ping))));
                        break;
                    }
                    boolean wasCommitting = recovery.committing();
                    recovery.advance(n, reliable.pendingCount() == 0 && queue.isEmpty(), this::enqueue);
                    if (!wasCommitting && recovery.committing()) {
                        observer.event("firmware_recovery", "Ultimo blocco consegnato · richiesta di verifica e riavvio");
                    }
                    if (!recovery.active()) {
                        observer.event("firmware_recovery", recovery.outcome());
                        break;
                    }
                } else if (!shutdownSent) {
                    tickVisualProbe(n);
                }
                if(initialized&&!audioQueue.isEmpty()&&n-lastAudioTick>=32&&reliable.pendingCount()<=10&&queue.size()<=30) {
                    byte[] audioFrame=audioQueue.removeFirst();
                    queuedAudioFramesCount=audioQueue.size();
                    enqueue(Wire.outputAudio(audioFrame));
                    audioSentFrames++;
                    if(pendingDisplay!=null) {
                        enqueue(Wire.command(0x97,pendingDisplay));
                        pendingDisplay=null;
                    }
                    lastAudioTick=n;
                }
                while(handshake&&!queue.isEmpty()&&!reliable.full()) {
                    Wire.Packet p=reliable.enqueue(queue.removeFirst());
                    if(reliable.pendingCount()==1)lastResend=n;
                    sendPacket(p);
                }
                if(handshake&&n-lastPing>=500) {
                    byte[] ping=Wire.le(17).putDouble(n/1000.0).putInt(pingCounter++).putInt(0).put((byte)0).array();
                    sendRaw(Wire.frame(11,Wire.OOB,Wire.OOB,reliable.lastReceived,Collections.singletonList(new Wire.Packet(11,-1,ping))));
                    lastPing=n;
                }
                if(lastRx>0&&reliable.pendingCount()>0&&n-lastResend>=Math.min(800,100L<<Math.min(3,resends))) {
                    for(Wire.Packet p:reliable.pending()) {sendPacket(p);retries++;}
                    lastResend=n;resends++;
                }
                if(n-start>8000&&!handshake) {status("Nessuna risposta all'handshake: verifica Wi-Fi, VPN e robot");break;}
                if(handshake&&n-lastRx>(isRecoveringFirmware()?30_000:5000)) {status("Robot non più raggiungibile");break;}
                if(handshake&&n-lastSummary>5000) {
                    observer.event("stats","rx="+rx+" tx="+tx+" stati="+states+" retry="+retries+" invalidi="+bad+" pendenti="+reliable.pendingCount()+" audio_tx="+audioSentFrames+" audio_hw="+audioPlayedCount);lastSummary=n;
                }
                DatagramPacket datagram=new DatagramPacket(buffer,buffer.length);
                try {
                    s.receive(datagram);
                    Wire.Frame frame;
                    try {frame=Wire.parse(Arrays.copyOf(buffer,datagram.getLength()));}catch(IllegalArgumentException e){bad++;continue;}
                    if(frame.type!=9&&frame.type!=11) {bad++;continue;}
                    rx++;lastRx=now();
                    if(!initialized&&initTraceCount<10) {
                        initTraceCount++;
                        StringBuilder sb=new StringBuilder("ft=").append(frame.type)
                            .append(" seq=").append(frame.first).append("..").append(frame.last)
                            .append(" ack=").append(frame.ack).append(" exp=").append(reliable.expected).append(" pkts=[");
                        for(int i=0;i<frame.packets.size();i++) {
                            Wire.Packet pk=frame.packets.get(i);
                            if(i>0)sb.append(',');
                            sb.append('t').append(pk.type).append(":0x").append(Integer.toHexString(pk.id&0xff))
                              .append('@').append(pk.seq).append('(').append(pk.data.length).append(')');
                        }
                        sb.append(']');
                        observer.event("init_rx",sb.toString());
                    }
                    if(reliable.acknowledge(frame.ack)) {lastResend=lastRx;resends=0;}
                    int prevLastReceived=reliable.lastReceived;
                    for(Wire.Packet p:frame.packets)for(Wire.Packet deliver:reliable.receive(p)) {
                        if(states==0&&rxTraceCount<80) {
                            rxTraceCount++;
                            StringBuilder hx=new StringBuilder();
                            int limit=Math.min(64,deliver.data.length);
                            for(int i=0;i<limit;i++)hx.append(String.format("%02x",deliver.data[i]&0xff));
                            observer.event("rx_trace","t"+deliver.type+":0x"+Integer.toHexString(deliver.id&0xff)
                                +"@"+deliver.seq+"("+deliver.data.length+") "+hx);
                        }
                        handle(deliver);
                    }
                    if(handshake&&!initialized&&reliable.lastReceived!=prevLastReceived&&lastRx-lastPing>=40) {
                        byte[] ping=Wire.le(17).putDouble(lastRx/1000.0).putInt(pingCounter++).putInt(0).put((byte)0).array();
                        sendRaw(Wire.frame(11,Wire.OOB,Wire.OOB,reliable.lastReceived,Collections.singletonList(new Wire.Packet(11,-1,ping))));
                        lastPing=lastRx;
                    }
                }catch(SocketTimeoutException expected) { /* timed receive keeps local safety running */ }
            }
            if(handshake&&!shutdownSent&&firmwareRecovery==null&&now()-lastRx<1000) {
                doStop(true);
                // Nothing follows DISCONNECT: the robot may reboot on subsequent packets.
                if(!reliable.full())sendPacket(reliable.enqueue(new Wire.Packet(3,-1,new byte[0])));
            }
        }catch(Exception e) {status("Errore: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        finally {running=false;handshake=false;motionArmed=false;initState=InitState.DISCONNECTED;queuedAudioFramesCount=0;socket=null;synchronized(requests){requests.clear();}queue.clear();audioQueue.clear();observer.event("closed",phase);}
    }
    private void sendEnable(String path,String reason) {
        if(enableSent)return;
        enableSent=true;firmwareEnabled=true;enableSentAt=now();
        if("none".equals(initPath))initPath=path;
        if(initState==InitState.CONNECTED_AWAITING_INIT||initState==InitState.HANDSHAKE_SENT)initState=InitState.ENABLED;
        if(reason!=null){
            observer.event("init_fallback_enable",reason);
            observer.event("init_fallback",reason);
        }
        enqueue(Wire.command(0x25));enqueue(Wire.command(0x25));
    }
    private void initializeRobot(String path,String reason) {
        if(robotInitialized)return;
        if(!enableSent)sendEnable(path,"Enable implicito prima di initializeRobot ("+path+")");
        robotInitialized=true;initialized=true;initializedAt=now();
        if("none".equals(initPath)||("normal".equals(initPath)&&!"normal".equals(path)))initPath=path;
        initState=InitState.INITIALIZED;
        if(reason!=null){
            observer.event("init_fallback_ready",reason);
            observer.event("init_fallback",reason);
        }
        // Keep the hardware-verified 0.1.4 boot order. NVStorage reads are not
        // part of initialization: on this firmware they can precede RobotState
        // and delay the first state indefinitely.
        // The 2381 application firmware uses the PyCozmo layout. The factory
        // firmware reported different lengths for these three boot messages.
        if (signatureSeen) {
            enqueue(Wire.command(0x45,Wire.le(24).putInt(0).putInt(0).putInt(1).putFloat(0).putFloat(0).putFloat(0).array()));
            sendInitPacket60();
            enqueue(Wire.command(0x4b,new byte[8]));
        } else {
            enqueue(Wire.command(0x45,Wire.le(20).putInt(0).putInt(0).putInt(1).putFloat(0).putFloat(0).array()));
            sendInitPacket60();
            enqueue(Wire.command(0x4b,Wire.le(12).putInt(0).putInt(0).putInt(0).array()));
        }
        if (!visualOnly) enqueue(Wire.setVolume(initialVolume));
        status("Inizializzato, attendo telemetria");
    }

    /** Invia il pacchetto 0x60 con lo stesso formato usato da initializeRobot(): 1 byte se signatureSeen, 12 byte altrimenti. */
    private void sendInitPacket60() {
        if (signatureSeen) {
            enqueue(Wire.command(0x60, new byte[]{1}));
        } else {
            enqueue(Wire.command(0x60, Wire.le(12).putInt(1).putInt(0).putInt(0).array()));
        }
    }

    public boolean isSignatureSeen() { return signatureSeen; }

    public long armRemainingMs() {
        return safety.armRemainingMs(now());
    }

    public String jointBlockReason() {
        return safety.jointBlockReason(now(), visualOnly, factoryFirmware);
    }

    public String wheelBlockReason() {
        return safety.wheelBlockReason(now(), visualOnly, factoryFirmware);
    }

    public String statusHex() {
        return String.format(Locale.US, "0x%04X", latestState != null ? latestState.status : 0L);
    }

    public long getTxPackets() { return tx; }
    public long getRxPackets() { return rx; }
    public long getInvalidPackets() { return bad; }

    private void handle(Wire.Packet p) {
        if(p.type==2) {
            handshake=true;
            if(handshakeAt==0){handshakeAt=now();initState=InitState.CONNECTED_AWAITING_INIT;status("Handshake riuscito, attendo firmware");}
        }
        else if(p.type==3) {status("Disconnessione richiesta dal robot");stopping.set(true);return;}
        else if(p.id==0xee) {
            if (!signatureSeen && p.data.length >= 4) {
                int length = Wire.read(p.data).getShort(2) & 65535;
                boolean hasString = length > 0 && length <= p.data.length - 4;
                boolean json = hasString && p.data[4] == '{';
                if (json || !binaryVersionSeen) {
                    if (json) {
                        signatureSeen = true;
                        observer.event("firmware", new String(p.data, 4, length, StandardCharsets.UTF_8));
                    } else {
                        binaryVersionSeen = true;
                        observer.event("firmware_format", "0xee non JSON · " + p.data.length + " byte");
                    }
                    if (!enableSent) {
                        sendEnable(hasString ? "normal" : "warm_trigger", null);
                    } else if (json || !initialized) {
                        enqueue(Wire.command(0x25));
                        enqueue(Wire.command(0x25));
                        observer.event("init_signature_enable", "0xee dopo Enable iniziale");
                    }
                }
            }
        } else if(p.id==0xef && firmwareRecovery != null) {
            FirmwareRecovery recovery = firmwareRecovery;
            if (recovery.receive(p.data)) observer.event("firmware_recovery", recovery.outcome() != null
                ? recovery.outcome() : "Blocchi confermati: " + recovery.confirmed());
        } else if(p.id==0xd2) {
            if (p.data.length == 44 && !factoryFirmware) {
                factoryFirmware = true;
                observer.event("firmware_mode", "Cozmo ha segnalato il firmware di fabbrica");
            }
            if(p.data.length>=4) {
                int length=Wire.read(p.data).getShort(2)&65535;
                if(length>2&&length<=p.data.length-4) {
                    String sig=new String(p.data,4,length,StandardCharsets.UTF_8);
                    if(sig.contains("{"))observer.event("firmware",sig);
                }
            }
            if(handshake&&!enableSent&&warmTriggerAt==0)warmTriggerAt=now();
        } else if(p.id==0xc9&&handshake&&!enableSent) {
            if(warmTriggerAt==0)warmTriggerAt=now();
        } else if(p.id==0xed&&!robotInitialized) {
            observer.event("body_info","BodyInfo 0xed ricevuto");
            initializeRobot("none".equals(initPath)?"normal":initPath,null);
        } else if(p.id==0xf0) {
            if(!animationStateEnabled) {
                // PyCozmo enables the animation controller only after Enable and
                // live RobotState. Firmware 2381 rejects 0x9f during boot
                // ("Received message not expected here tag=9f"), so send it here.
                animationStateEnabled=true;
                if (!visualOnly || signatureSeen) {
                    enqueue(Wire.command(0x9f));
                    observer.event("animation_state_enable","EnableAnimationState (0x9f) con telemetria attiva");
                }
            }
            if(!robotInitialized) {
                // RobotState proves that this firmware is already initialized.
                // Replaying SetOrigin/SyncTime/NV reads here interrupts a warm robot.
                robotInitialized=true;initialized=true;initializedAt=lastRx>0?lastRx:now();
                if("none".equals(initPath))initPath="warm_trigger";
                initState=InitState.INITIALIZED;
                sendInitPacket60();
                observer.event("init_existing_state","RobotState ricevuto prima di BodyInfo: riuso inizializzazione firmware attiva");
            }
            try {
                Wire.Telemetry t=Wire.Telemetry.parse(p.data);lastState=now();latestState=t;safety.update(t,lastState);states++;
                if(states==1){
                    firstState=lastState;
                    connectReadyMs=Math.max(0L,lastState-start);
                    observer.event("connect_ready_ms",String.valueOf(connectReadyMs));
                    status("Connesso · telemetria attiva");
                }
                if(t.unsafe()) {motionArmed=false;if(states==1)emergency.set(true);}
                observer.state(t,states);
            } catch(IllegalArgumentException e){
                bad++;
                if(telemetryErrorLogged++<3){
                    StringBuilder hx=new StringBuilder();
                    for(byte x:p.data)hx.append(String.format("%02x",x&255));
                    observer.event("telemetry_error",e.getMessage()+" len="+p.data.length+" hex="+hx);
                }
            }
        } else if (p.id == 0xb0 && visualOnly && p.data.length >= 8 && animationDebugCount < 50) {
            ByteBuffer debug = Wire.read(p.data);
            int format = debug.getShort(0) & 65535;
            if (format == 612 || format == 477 || format == 381 || format == 259 || format == 258
                    || format == 455 || format == 454 || format == 451) {
                StringBuilder args = new StringBuilder();
                for (int i = 0; i < (p.data[7] & 255) && 12 + i * 4 <= p.data.length; i++) {
                    args.append(' ').append(Integer.toUnsignedString(debug.getInt(8 + i * 4)));
                }
                animationDebugCount++;
                observer.event("visual_debug", "format=" + format + " args=" + args);
            }
        } else if ((p.id == 0xca || p.id == 0xcb) && p.data.length > 0 && visualProbeImage != null) {
            observer.event("visual_probe", (p.id == 0xca ? "AnimationStarted" : "AnimationEnded")
                + " · tag=" + (p.data[0] & 255));
        } else if(p.id==0xf1) {
            lastAnimStateMs=now();
            if(handshake&&!enableSent&&warmTriggerAt==0)warmTriggerAt=lastAnimStateMs;
            Wire.AnimState a=null;
            try {
                a=Wire.AnimState.parse(p.data);
                animationTracks=a.enabledTracks;
                animBytesPlayedCount=a.animBytesPlayed;
                audioPlayedCount=a.audioFramesPlayed;
                animDropsCount=a.clientDropCount;
                long curAnim=Integer.toUnsignedLong(a.animBytesPlayed);
                long curAudio=Integer.toUnsignedLong(a.audioFramesPlayed);
                if(animBase<0){animBase=curAnim;audioBase=curAudio;}
                long seenAnim=(curAnim-animBase)&0xffffffffL,seenAudio=(curAudio-audioBase)&0xffffffffL;
                if(seenAnim!=animBytesPlayed||seenAudio!=audioFramesPlayed){
                    animBytesPlayed=seenAnim;audioFramesPlayed=seenAudio;lastPlayedMs=now();
                }
            } catch(IllegalArgumentException ignored) {}
            if(a!=null&&animationStateEnabled&&animTraceCount<20){
                animTraceCount++;
                StringBuilder hx=new StringBuilder();
                int limit=Math.min(20,p.data.length);
                for(int i=0;i<limit;i++)hx.append(String.format("%02x",p.data[i]&255));
                observer.event("anim_state_trace","anim="+a.animBytesPlayed+" audio="+a.audioFramesPlayed+" tracks="+a.enabledTracks+" tag="+a.tag+" drops="+a.clientDropCount+" len="+p.data.length+" hex="+hx);
            }
        }
        observer.packet(p);
    }
    private void sendPacket(Wire.Packet p) throws Exception {
        sendRaw(Wire.frame(7,p.seq,p.seq,reliable.lastReceived,Collections.singletonList(p)));
    }
    private void sendRaw(byte[] data) throws Exception {
        socket.send(new DatagramPacket(data,data.length));tx++;
    }
}

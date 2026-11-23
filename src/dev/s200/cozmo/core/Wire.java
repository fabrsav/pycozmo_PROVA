package dev.s200.cozmo.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cozmo wire format, ported from PyCozmo (MIT). See THIRD_PARTY.md. */
public final class Wire {
    public static final int MOD = 65534, OOB = 65535;
    public static final int AUDIO_SAMPLE_RATE = 22050, AUDIO_FRAME_SAMPLES = 744;
    public static final byte AUDIO_SILENCE = 0x00;
    private static final byte[] MAGIC = {'C','O','Z',3,'R','E',1};

    public static final class Packet {
        public final int type, id; public final byte[] data;
        public int seq = OOB;
        public Packet(int type, int id, byte[] data) { this.type=type; this.id=id; this.data=data.clone(); }
        public boolean oob() { return type>=5; }
        public byte[] bytes() {
            ByteBuffer b=le(3+data.length+(type==4||type==5?1:0));
            b.put((byte)type).putShort((short)(b.capacity()-3));
            if(type==4||type==5) b.put((byte)id);
            return b.put(data).array();
        }
    }

    public static final class Frame {
        public int type,first,last,ack; public final List<Packet> packets=new ArrayList<>();
    }

    public static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
    public static ByteBuffer read(byte[] b) { return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }
    public static Packet command(int id, byte[] bytes) { return new Packet(4,id,bytes); }
    public static Packet command(int id) { return command(id,new byte[0]); }
    public static byte[] floats(float... values) {
        ByteBuffer b=le(values.length*4); for(float x:values) b.putFloat(x); return b.array();
    }

    public static byte[] frame(int type,int first,int last,int ack,List<Packet> packets) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] h=le(14).put(MAGIC).put((byte)type).putShort((short)(first+1)).putShort((short)(last+1)).putShort((short)(ack+1)).array();
        out.write(h,0,h.length);
        for(Packet p:packets) { byte[] raw=type==11?p.data:p.bytes(); out.write(raw,0,raw.length); }
        if(out.size()>(type==9?65507:1051)) throw new IllegalArgumentException("Cozmo frame exceeds transport limit");
        return out.toByteArray();
    }

    public static Frame parse(byte[] raw) {
        // 1051 limits what the robot accepts from the engine, not its camera datagrams.
        if(raw.length<14||raw.length>65507||!Arrays.equals(MAGIC,Arrays.copyOf(raw,7)))
            throw new IllegalArgumentException("Invalid Cozmo header");
        ByteBuffer b=read(raw); b.position(7); Frame f=new Frame();
        f.type=b.get()&255; f.first=((b.getShort()&65535)-1)&65535;
        f.last=((b.getShort()&65535)-1)&65535; f.ack=((b.getShort()&65535)-1)&65535;
        if(f.type==1||f.type==2||f.type==3) { if(b.hasRemaining()) throw new IllegalArgumentException("Unexpected reset/FIN data"); return f; }
        if(f.type==11) {
            if(b.remaining()!=17) throw new IllegalArgumentException("Invalid ping length");
            byte[] data=new byte[17]; b.get(data); f.packets.add(new Packet(11,-1,data)); return f;
        }
        if(f.type!=7&&f.type!=9) throw new IllegalArgumentException("Unsupported frame type "+f.type);
        int seq=f.first, reliable=0;
        while(b.hasRemaining()) {
            if(b.remaining()<3) throw new IllegalArgumentException("Truncated packet header");
            int type=b.get()&255, size=b.getShort()&65535, id=-1;
            if(type!=2&&type!=3&&type!=4&&type!=5&&type!=10&&type!=11) throw new IllegalArgumentException("Unknown packet type");
            if(size>b.remaining()) throw new IllegalArgumentException("Truncated packet");
            if(type==4||type==5) { if(size<1) throw new IllegalArgumentException("Missing packet ID"); id=b.get()&255; size--; }
            byte[] data=new byte[size]; b.get(data); Packet p=new Packet(type,id,data); p.seq=seq;
            if(!p.oob()) { seq=(seq+1)%MOD; reliable++; }
            f.packets.add(p);
        }
        if(reliable>0&&(f.first>=MOD||f.last!=(seq+MOD-1)%MOD)) throw new IllegalArgumentException("Inconsistent sequence interval");
        return f;
    }

    public static byte[] display(boolean[] pixels) {
        if(pixels.length!=128*32) throw new IllegalArgumentException("Expected 128x32 logical display");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int x=0;x<128;x++) {
            int y=0;
            while(y<32) {
                boolean on=pixels[y*128+x]; int n=1;
                while(y+n<32&&pixels[(y+n)*128+x]==on) n++;
                int count=n-1;
                out.write((count<16?0x80:0xc0)|((count%16)<<2)|(on?1:0)); y+=n;
            }
        }
        byte[] encoded=out.toByteArray();
        if(encoded.length>1024) throw new IllegalArgumentException("Display pattern too complex");
        return le(encoded.length+2).putShort((short)encoded.length).put(encoded).array();
    }

    /**
     * Cozmo non-complemented 8-bit u-law encoding for a 16-bit signed PCM sample.
     * Matches pycozmo.audio.u_law_encoding exactly.
     */
    public static byte encodeUlaw(int sample) {
        int mask = 0x4000;
        int position = 14;
        int sign = 0;
        if (sample < 0) {
            sample = -sample;
            sign = 0x80;
        }
        sample += 132;
        if (sample > 0x7FFF) {
            sample = 0x7FFF;
        }
        while ((sample & mask) != mask && position >= 7) {
            mask >>= 1;
            position -= 1;
        }
        int lsb = (sample >> (position - 4)) & 0x0f;
        return (byte) (sign | ((position - 7) << 4) | lsb);
    }

    /** Converts 16-bit mono 22050 Hz PCM samples into 744-byte Cozmo u-law frames padded with 0x00 silence. */
    public static List<byte[]> pcmToUlawFrames(short[] samples) {
        List<byte[]> frames = new ArrayList<>();
        if (samples == null || samples.length == 0) return frames;
        for (int offset = 0; offset < samples.length; offset += AUDIO_FRAME_SAMPLES) {
            byte[] frame = new byte[AUDIO_FRAME_SAMPLES];
            int end = Math.min(samples.length, offset + AUDIO_FRAME_SAMPLES);
            for (int i = offset; i < end; i++) {
                frame[i - offset] = encodeUlaw(samples[i]);
            }
            frames.add(frame);
        }
        return frames;
    }

    public static Packet outputAudio(byte[] samples744) {
        if (samples744 == null || samples744.length != AUDIO_FRAME_SAMPLES)
            throw new IllegalArgumentException("OutputAudio requires 744 u-law samples");
        return command(0x8e, samples744);
    }

    public static Packet setVolume(int level) {
        int clamped = Math.max(0, Math.min(65535, level));
        return command(0x64, le(2).putShort((short) clamped).array());
    }

    public static Packet setHeadLight(boolean enable) {
        return command(0x0b, new byte[]{(byte) (enable ? 1 : 0)});
    }

    public static Packet accessoryDiscovery(boolean enable) {
        return command(0x0a, new byte[]{(byte) (enable ? 1 : 0)});
    }

    public static Packet shutdownRobot() {
        return command(0xa9);
    }

    public static final int NV_TAG_SAVED_CUBE_IDS = 0x00197000;
    public static final int NV_TAG_CAMERA_CALIB = 0x80000001;
    public static final int NV_OP_READ = 0;
    public static final int NV_RESULT_OKAY = 0;

    public static Packet nvStorageRead(int tag, int length) {
        return command(0x81, le(12).putInt(tag).putInt(length).put((byte) NV_OP_READ).put((byte) 0).putShort((short) 0).array());
    }

    public static Packet objectConnect(int factoryId, int slot) {
        if (slot < 0 || slot > 4) throw new IllegalArgumentException("Invalid cube slot");
        return command(0x05, le(5).putInt(factoryId).put((byte) slot).array());
    }

    public static int rgbToLed16(int r, int g, int b) {
        int r5 = (Math.max(0, Math.min(255, r)) * 31) / 255;
        int g5 = (Math.max(0, Math.min(255, g)) * 31) / 255;
        int b5 = (Math.max(0, Math.min(255, b)) * 31) / 255;
        return (r5 << 10) | (g5 << 5) | b5;
    }

    public static byte[] lightState(int onColor16, int offColor16, int onFrames, int offFrames, int transOn, int transOff, int offset) {
        return le(10)
            .putShort((short) onColor16)
            .putShort((short) offColor16)
            .put((byte) onFrames)
            .put((byte) offFrames)
            .put((byte) transOn)
            .put((byte) transOff)
            .putShort((short) offset)
            .array();
    }

    public static byte[] solidLight(int r, int g, int b) {
        int c = rgbToLed16(r, g, b);
        return lightState(c, c, 0, 0, 0, 0, 0);
    }

    public static Packet selectUserBackpackLayer() {
        return command(0x02, new byte[]{0});
    }

    public static Packet enableVisualAnimationTracks() {
        int faceImageTrack = 0x08;
        int backpackLightsTrack = 0x20;
        return command(0x9e, new byte[]{(byte) (faceImageTrack | backpackLightsTrack)});
    }

    public static Packet backpackCenter(byte[] top, byte[] middle, byte[] bottom) {
        if (top.length != 10 || middle.length != 10 || bottom.length != 10)
            throw new IllegalArgumentException("Expected 10-byte LightState");
        return command(0x03, le(31).put(top).put(middle).put(bottom).put((byte) 0).array());
    }

    public static Packet backpackSide(byte[] left, byte[] right) {
        if (left.length != 10 || right.length != 10)
            throw new IllegalArgumentException("Expected 10-byte LightState");
        return command(0x11, le(21).put(left).put(right).put((byte) 0).array());
    }

    public static Packet cubeId(int objectId, int rotationPeriodFrames) {
        return command(0x10, le(5).putInt(objectId).put((byte) rotationPeriodFrames).array());
    }

    public static Packet cubeLights(byte[] l0, byte[] l1, byte[] l2, byte[] l3) {
        if (l0.length != 10 || l1.length != 10 || l2.length != 10 || l3.length != 10)
            throw new IllegalArgumentException("Expected four 10-byte LightStates");
        return command(0x04, le(40).put(l0).put(l1).put(l2).put(l3).array());
    }

    public static final class Telemetry {
        public long timestamp, status;
        public boolean compactTelemetry;
        public float x, y, yaw, pitch, head, lift, battery, left, right, ax, ay, az, gx, gy, gz;
        public int[] cliff = new int[4];
        public int touch;
        public static Telemetry parse(byte[] data) {
            if(data.length<65) throw new IllegalArgumentException("Short RobotState");
            ByteBuffer b=read(data); Telemetry t=new Telemetry();
            t.timestamp=Integer.toUnsignedLong(b.getInt());
            if(data.length<91) {
                // Firmware 2381 sends a compact 65-byte RobotState. The fields used by
                // the app were located from live captures: head angle at 36, lift angle
                // at 40, lift height at 44, battery percentage at 52, status at 56,
                // two cliff sensors at 60/62 and touch at 64. Keep the same semantic
                // values as the full 91-byte state so safety and personality work.
                b.position(12);
                t.x=b.getFloat();t.y=b.getFloat();
                b.position(24);
                t.yaw=b.getFloat();t.pitch=0f;t.left=0f;t.right=0f;
                b.position(36);
                t.head=b.getFloat();
                float liftAngle=b.getFloat();
                t.lift=(float)(45+66*Math.sin(liftAngle));
                b.position(52);
                float charge=b.getFloat();
                t.battery=(charge>0f&&charge<=120f)?3.3f+0.9f*(charge/100f):4f;
                b.position(56);
                // Caso B: nel pacchetto compatto 65-byte solo 0x1000 (onCharger) e 0x2000 (charging)
                // sono bit affidabili; gli altri bit del campo status possono contenere rumore
                // sul firmware di fabbrica. Vedi Safety.startupHeadAllowed per compactTelemetry.
                t.status=Integer.toUnsignedLong(b.getInt())&(0x1000L|0x2000L);
                t.compactTelemetry=true;
                b.position(60);
                t.cliff[0]=b.getShort()&65535;t.cliff[1]=b.getShort()&65535;
                t.cliff[2]=0;t.cliff[3]=0;
                t.touch=data.length>64?data[64]&255:0;
                t.ax=0f;t.ay=0f;t.az=0f;t.gx=0f;t.gy=0f;t.gz=0f;
                if(!Float.isFinite(t.battery)||!Float.isFinite(t.head)||!Float.isFinite(t.lift))
                    throw new IllegalArgumentException("Nonfinite telemetry");
                return t;
            }
            b.position(12);
            t.x=b.getFloat();t.y=b.getFloat();b.getFloat();t.yaw=b.getFloat();t.pitch=b.getFloat();
            t.left=b.getFloat();t.right=b.getFloat();t.head=b.getFloat();
            t.lift=(float)(45+66*Math.sin(b.getFloat()));
            t.ax=b.getFloat();t.ay=b.getFloat();t.az=b.getFloat();
            t.gx=b.getFloat();t.gy=b.getFloat();t.gz=b.getFloat();
            t.battery=b.getFloat(); t.status=Integer.toUnsignedLong(b.getInt());
            for(int i=0;i<4;i++)t.cliff[i]=b.getShort()&65535;
            t.touch=b.getShort()&65535;
            t.compactTelemetry=false;
            if(!Float.isFinite(t.battery)||!Float.isFinite(t.head)||!Float.isFinite(t.lift)) throw new IllegalArgumentException("Nonfinite telemetry");
            return t;
        }
        public boolean unsafe() { return (status&(0x8|0x20|0x4000))!=0; }
        public boolean pickedUp() { return (status&0x8)!=0; }
        public boolean falling() { return (status&0x20)!=0; }
        public boolean cliffDetected() { return (status&0x4000)!=0; }
        public boolean buttonPressed() { return (status&0x10)!=0; }
        public boolean onCharger() { return (status&0x1000)!=0; }
        public boolean charging() { return (status&0x2000)!=0; }
    }

    public static final class AnimState {
        public long timestamp;
        public int animBytesPlayed, audioFramesPlayed, enabledTracks, tag, clientDropCount;
        public static AnimState parse(byte[] data) {
            if (data.length < 14) throw new IllegalArgumentException("Short AnimationState");
            ByteBuffer b = read(data);
            AnimState a = new AnimState();
            a.timestamp = Integer.toUnsignedLong(b.getInt());
            a.animBytesPlayed = b.getInt();
            a.audioFramesPlayed = b.getInt();
            a.enabledTracks = b.get() & 255;
            a.tag = b.get() & 255;
            a.clientDropCount = data.length >= 15 ? (b.get() & 255) : 0;
            return a;
        }
    }

    public static final class CubeAvailable {
        public int factoryId, objectType, rssi;
        public static CubeAvailable parse(byte[] data) {
            if (data.length < 9) throw new IllegalArgumentException("Short ObjectAvailable");
            ByteBuffer b = read(data);
            CubeAvailable c = new CubeAvailable();
            c.factoryId = b.getInt();
            c.objectType = b.getInt();
            c.rssi = b.get(); // signed int8
            return c;
        }
    }

    public static final class CubeConnection {
        public int objectId, factoryId, objectType;
        public boolean connected;
        public static CubeConnection parse(byte[] data) {
            if (data.length < 13) throw new IllegalArgumentException("Short ObjectConnectionState");
            ByteBuffer b = read(data);
            CubeConnection c = new CubeConnection();
            c.objectId = b.getInt();
            c.factoryId = b.getInt();
            c.objectType = b.getInt();
            c.connected = b.get() != 0;
            return c;
        }
    }

    public static final class CubeTapped {
        public long timestamp;
        public int objectId, numTaps, tapTime, tapNeg, tapPos;
        public static CubeTapped parse(byte[] data) {
            if (data.length < 12) throw new IllegalArgumentException("Short ObjectTapped");
            ByteBuffer b = read(data);
            CubeTapped t = new CubeTapped();
            t.timestamp = Integer.toUnsignedLong(b.getInt());
            t.objectId = b.getInt();
            t.numTaps = b.get() & 255;
            t.tapTime = b.get() & 255;
            t.tapNeg = b.get();
            t.tapPos = b.get();
            return t;
        }
    }

    public static final class CubeMoved {
        public long timestamp;
        public int objectId, axis;
        public float ax, ay, az;
        public static CubeMoved parse(byte[] data) {
            if (data.length < 21) throw new IllegalArgumentException("Short ObjectMoved");
            ByteBuffer b = read(data);
            CubeMoved m = new CubeMoved();
            m.timestamp = Integer.toUnsignedLong(b.getInt());
            m.objectId = b.getInt();
            m.ax = b.getFloat();
            m.ay = b.getFloat();
            m.az = b.getFloat();
            m.axis = b.get() & 255;
            return m;
        }
    }

    public static final class CubeUpAxis {
        public long timestamp;
        public int objectId, axis;
        public static CubeUpAxis parse(byte[] data) {
            if (data.length < 9) throw new IllegalArgumentException("Short ObjectUpAxisChanged");
            ByteBuffer b = read(data);
            CubeUpAxis u = new CubeUpAxis();
            u.timestamp = Integer.toUnsignedLong(b.getInt());
            u.objectId = b.getInt();
            u.axis = b.get() & 255;
            return u;
        }
    }

    public static final class CubePower {
        public int objectId, missedPackets, batteryLevel;
        public static CubePower parse(byte[] data) {
            if (data.length < 9) throw new IllegalArgumentException("Short ObjectPowerLevel");
            ByteBuffer b = read(data);
            CubePower p = new CubePower();
            p.objectId = b.getInt();
            p.missedPackets = b.getInt();
            p.batteryLevel = b.get() & 255;
            return p;
        }
    }

    public static final class NvStorageResult {
        public int tag, length, op, result;
        public byte[] data;
        public static NvStorageResult parse(byte[] raw) {
            if (raw.length < 12) throw new IllegalArgumentException("Short NvStorageOpResult");
            ByteBuffer b = read(raw);
            NvStorageResult r = new NvStorageResult();
            r.tag = b.getInt();
            r.length = b.getInt();
            r.op = b.get() & 255;
            r.result = b.get();
            int dataLen = b.getShort() & 65535;
            if (dataLen > b.remaining()) throw new IllegalArgumentException("Truncated NvStorageOpResult data");
            r.data = new byte[dataLen];
            b.get(r.data);
            return r;
        }
    }

    public static final class CameraCalib {
        public final float fx, fy, cx, cy;
        public CameraCalib(float fx, float fy, float cx, float cy) {
            this.fx = fx; this.fy = fy; this.cx = cx; this.cy = cy;
        }
        public static CameraCalib parse(byte[] data) {
            if (data == null || data.length < 16) throw new IllegalArgumentException("Short CameraCalib");
            ByteBuffer b = read(data);
            float fx = b.getFloat(), fy = b.getFloat(), cx = b.getFloat(), cy = b.getFloat();
            if (!Float.isFinite(fx) || !Float.isFinite(fy) || !Float.isFinite(cx) || !Float.isFinite(cy))
                throw new IllegalArgumentException("Nonfinite CameraCalib");
            return new CameraCalib(fx, fy, cx, cy);
        }
    }

    public static List<Integer> parseSavedCubeIds(byte[] data) {
        List<Integer> ids = new ArrayList<>();
        if (data == null || data.length < 28) return ids;
        ByteBuffer b = read(data);
        for (int i = 0; i < 7; i++) {
            int v = b.getInt();
            if (i >= 4 && v != 0) ids.add(v);
        }
        return ids;
    }
}

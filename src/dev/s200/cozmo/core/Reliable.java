package dev.s200.cozmo.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded cumulative ACK and ordered delivery, modulo 65534, as in PyCozmo. */
public final class Reliable {
    public int next=0, expected=0, lastReceived=Wire.OOB;
    private final LinkedHashMap<Integer,Wire.Packet> pending=new LinkedHashMap<>();
    private final Map<Integer,Wire.Packet> incoming=new LinkedHashMap<>();
    public boolean full() { return pending.size()>=62; }
    public Wire.Packet enqueue(Wire.Packet p) {
        if(full()||p.oob()) throw new IllegalStateException("Invalid reliable enqueue");
        p.seq=next;pending.put(next,p);next=(next+1)%Wire.MOD;return p;
    }
    public boolean acknowledge(int ack) {
        if(!pending.containsKey(ack))return false;
        List<Integer> keys=new ArrayList<>(pending.keySet());
        for(int key:keys) { pending.remove(key); if(key==ack)break; }
        return true;
    }
    public List<Wire.Packet> receive(Wire.Packet p) {
        List<Wire.Packet> result=new ArrayList<>();
        if(p.oob()) {result.add(p);return result;}
        int distance=(p.seq-expected+Wire.MOD)%Wire.MOD;
        if(p.seq<0||p.seq>=Wire.MOD||distance>=128)return result;
        incoming.putIfAbsent(p.seq,p);
        while(incoming.containsKey(expected)) {
            result.add(incoming.remove(expected));lastReceived=expected;expected=(expected+1)%Wire.MOD;
        }
        return result;
    }
    public List<Wire.Packet> pending() { return new ArrayList<>(pending.values()); }
    public int pendingCount() { return pending.size(); }
    public void cancelMotion() {
        for(Map.Entry<Integer,Wire.Packet> e:pending.entrySet()) {
            int id=e.getValue().id;
            if(id>=0x32&&id<=0x39) {
                Wire.Packet stop=Wire.command(0x3b);stop.seq=e.getKey();e.setValue(stop);
            }
        }
    }
}

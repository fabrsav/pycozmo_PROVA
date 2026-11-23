package dev.s200.cozmo.core;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** ImageChunk metadata is partial: chunk_count may be zero until the final chunk. */
public final class CameraAssembler {
    public static final class Image {
        public final byte[] data;public final int encoding,resolution;
        Image(byte[] data,int encoding,int resolution){this.data=data;this.encoding=encoding;this.resolution=resolution;}
    }
    private long id=-1;private int next=0,encoding=0,resolution=0;
    private final ByteArrayOutputStream partial=new ByteArrayOutputStream();
    public void reset(){id=-1;next=0;partial.reset();}
    public Image accept(byte[] data){
        if(data.length<20){reset();throw new IllegalArgumentException("Short camera chunk");}
        ByteBuffer b=Wire.read(data);long frame=Integer.toUnsignedLong(b.getInt(4));
        int total=data[14]&255,chunk=data[15]&255,length=b.getShort(18)&65535;
        if(length>data.length-20||(total>0&&chunk>=total)){reset();throw new IllegalArgumentException("Invalid camera chunk length/count");}
        if(chunk==0){reset();id=frame;encoding=data[12]&255;resolution=data[13]&255;}
        if(frame!=id||chunk!=next||partial.size()+length>230400){reset();return null;}
        partial.write(data,20,length);next++;
        if(total>0&&chunk==total-1){Image image=new Image(partial.toByteArray(),encoding,resolution);reset();return image;}
        return null;
    }
}

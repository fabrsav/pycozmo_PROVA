package dev.s200.cozmo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.media.FaceDetector;
import java.io.*;
import java.util.concurrent.*;
import dev.s200.cozmo.core.*;

/** Bounded JPEG decoding, 2D vision analysis, and atomic JPEG snapshot persistence outside the network/safety thread. */
public final class CameraFrames {
    private final RobotService service;
    private final CameraAssembler assembler=new CameraAssembler();
    private final VisionAnalyzer analyzer=new VisionAnalyzer();
    private byte[] gray,color;
    private int generation=0,seen=0,errors=0,complete=0;
    private volatile boolean snapshotRequested=false;
    private long lastFaceDetectMs=0,lastAutoSnapshotMs=0;
    private int lastFaceCount=0;
    private float lastFaceX=0f,lastFaceY=0f,lastFaceConf=0f,lastFaceEyeDist=0f;
    private final ThreadPoolExecutor decoder=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    CameraFrames(RobotService service){
        this.service=service;
        try{gray=asset("jpeg-gray.bin");color=asset("jpeg-color.bin");}catch(Exception e){service.event("camera_error","Header JPEG non disponibile");}
    }
    private byte[] asset(String name)throws Exception{
        try(InputStream in=service.getAssets().open(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))>=0)out.write(b,0,n);return out.toByteArray();}
    }
    public synchronized void reset(){assembler.reset();analyzer.reset();generation++;lastFaceCount=0;}
    public void close(){decoder.shutdownNow();}
    public void requestSnapshot(){
        snapshotRequested=true;
        Bitmap current=service.cameraImage;
        if(current!=null){
            decoder.execute(()->{try{writeSnapshot(current,true);}catch(Exception e){service.event("camera_error","Snapshot fallito: "+e.getMessage());}});
        }
    }
    public synchronized void accept(byte[] data){
        try{
            if(++seen==1){try(FileOutputStream out=new FileOutputStream(new File(service.getFilesDir(),"camera-first-packet.bin"))){out.write(data);}}
            CameraAssembler.Image image=assembler.accept(data);
            if(image==null)return;
            if(++complete==1){try(FileOutputStream out=new FileOutputStream(new File(service.getFilesDir(),"camera-first-frame.bin"))){out.write(image.data);}}
            final int session=generation;
            decoder.execute(()->decode(image,session));
        }catch(Exception e){assembler.reset();if(++errors<=5)service.event("camera_error",e.toString());}
    }
    private void decode(CameraAssembler.Image frame,int session){
        try{
            byte[] raw=frame.data;if(raw.length<2)return;
            boolean isColor=raw[0]!=0;
            byte[] image=(frame.encoding==8||frame.encoding==9)?mini(raw,isColor):raw;
            Bitmap bitmap=BitmapFactory.decodeByteArray(image,0,image.length);
            if(bitmap==null){
                synchronized(this){if(errors++<5)service.event("camera_error","JPEG non decodificabile: encoding="+frame.encoding+" bytes="+raw.length);}
                return;
            }
            if(isColor&&bitmap.getWidth()==160)bitmap=Bitmap.createScaledBitmap(bitmap,320,240,false);
            int w=bitmap.getWidth(),h=bitmap.getHeight();
            long now=Link.now();
            int[] rawArgb=new int[w*h];
            bitmap.getPixels(rawArgb,0,w,0,0,w,h);
            VisionAnalyzer.Result rawVr=analyzer.analyze(rawArgb,w,h,lastFaceCount,lastFaceX,lastFaceY,lastFaceConf,lastFaceEyeDist);
            Bitmap displayBmp=bitmap;
            if(rawVr.meanLuma<95.0f){
                int[] enh=VisionAnalyzer.enhanceLowLightPixels(rawArgb,w,h,rawVr.meanLuma);
                if(enh!=null){
                    displayBmp=Bitmap.createBitmap(enh,w,h,Bitmap.Config.ARGB_8888);
                }
            }
            boolean ranFaceDetect=false;
            if(now-lastFaceDetectMs>=750&&w>=64&&h>=64){
                lastFaceDetectMs=now;
                detectFaces(displayBmp,w,h);
                ranFaceDetect=true;
            }
            VisionAnalyzer.Result vr=ranFaceDetect
                ? new VisionAnalyzer.Result(rawVr.width,rawVr.height,rawVr.meanLuma,rawVr.contrastStd,
                    rawVr.motionScore,rawVr.motionX,rawVr.motionY,
                    rawVr.markerCandidates,rawVr.markerX,rawVr.markerY,rawVr.approxMarkerDistMm,
                    lastFaceCount,lastFaceX,lastFaceY,lastFaceConf,lastFaceEyeDist)
                : rawVr;
            boolean explicitSnap=snapshotRequested;
            if(explicitSnap||now-lastAutoSnapshotMs>=2000){
                snapshotRequested=false;
                lastAutoSnapshotMs=now;
                try{writeSnapshot(displayBmp,explicitSnap);}catch(Exception ignored){}
            }
            synchronized(this){
                if(session==generation){
                    service.cameraImage=displayBmp;service.cameraCount++;
                    service.onVisionResult(vr);
                    if(service.cameraCount%150==1)service.event("camera","frame="+service.cameraCount+" size="+w+"x"+h+" luma="+Math.round(vr.meanLuma));
                }
            }
        }catch(Exception e){synchronized(this){if(errors++<5)service.event("camera_error",e.toString());}}
    }
    private void detectFaces(Bitmap src,int w,int h){
        try{
            int evenW=w&~1;
            Bitmap rgb565=src.copy(Bitmap.Config.RGB_565,false);
            if(rgb565==null)return;
            if(rgb565.getWidth()!=evenW)rgb565=Bitmap.createBitmap(rgb565,0,0,evenW,h);
            FaceDetector detector=new FaceDetector(evenW,h,2);
            FaceDetector.Face[] faces=new FaceDetector.Face[2];
            int found=detector.findFaces(rgb565,faces);
            lastFaceCount=found;
            if(found>0&&faces[0]!=null){
                PointF mid=new PointF();
                faces[0].getMidPoint(mid);
                lastFaceX=(mid.x/(evenW*0.5f))-1.0f;
                lastFaceY=(mid.y/(h*0.5f))-1.0f;
                lastFaceConf=faces[0].confidence();
                lastFaceEyeDist=faces[0].eyesDistance();
            }else{
                lastFaceX=0f;lastFaceY=0f;lastFaceConf=0f;lastFaceEyeDist=0f;
            }
            rgb565.recycle();
        }catch(Exception ignored){
            lastFaceCount=0;
        }
    }
    private void writeSnapshot(Bitmap bmp,boolean logEvent)throws IOException{
        File dir=service.getFilesDir();
        File tmp=new File(dir,"latest_frame.jpg.tmp");
        File dst=new File(dir,"latest_frame.jpg");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            bmp.compress(Bitmap.CompressFormat.JPEG,88,out);
            out.flush();
        }
        if(!tmp.renameTo(dst)){
            try(FileInputStream in=new FileInputStream(tmp);FileOutputStream out=new FileOutputStream(dst)){
                byte[] buf=new byte[4096];int n;while((n=in.read(buf))>=0)out.write(buf,0,n);
            }
            tmp.delete();
        }
        if(logEvent)service.event("snapshot",dst.getAbsolutePath());
    }
    private byte[] mini(byte[] data,boolean isColor)throws Exception{
        byte[] header=(isColor?color:gray).clone();
        header[0x5e]=0;header[0x5f]=(byte)240;header[0x60]=(byte)(isColor?0:1);header[0x61]=(byte)(isColor?160:64);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(header);
        int n=data.length;while(n>1&&(data[n-1]&255)==255)n--;
        for(int i=1;i<n;i++){out.write(data[i]);if((data[i]&255)==255)out.write(0);}
        out.write(255);out.write(217);return out.toByteArray();
    }
}

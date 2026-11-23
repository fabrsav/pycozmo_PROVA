package dev.s200.cozmo.checks;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

/** Separate, ADB-only instrumentation process: does not restart the robot application's process. */
public final class DeviceChecks extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            UiAutomation ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            String text=args.getString("text","");
            if(text.isEmpty())throw new IllegalArgumentException("Missing text");
            AccessibilityNodeInfo root=ui.getRootInActiveWindow();
            if(root==null||!"dev.s200.cozmo".contentEquals(root.getPackageName()==null?"":root.getPackageName())){
                Intent open=new Intent().setClassName("dev.s200.cozmo","dev.s200.cozmo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(open);SystemClock.sleep(700);
            }
            AccessibilityNodeInfo found=null;
            for(int phase=0;phase<2&&found==null;phase++){
                for(int attempt=0;attempt<14;attempt++){
                    root=ui.getRootInActiveWindow();if(root==null){SystemClock.sleep(100);continue;}
                    for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){
                        if(node.getText()!=null&&node.getText().toString().equalsIgnoreCase(text)&&node.isVisibleToUser()){found=node;break;}
                    }
                    if(found!=null)break;
                    AccessibilityNodeInfo scroll=findScrollable(root);
                    if(scroll==null||!scroll.performAction(phase==0?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))break;
                    SystemClock.sleep(250);
                }
            }
            if(found==null)throw new IllegalStateException("Control not visible: "+text);
            if("true".equals(args.getString("probe"))){
                result.putString("result","Visible: "+text+" enabled="+found.isEnabled()+" checked="+found.isChecked());
            }else if(args.containsKey("checked")&&found.isCheckable()&&found.isChecked()==Boolean.parseBoolean(args.getString("checked"))){
                result.putString("result","Already in requested state: "+text);
            }else{
                AccessibilityNodeInfo clickable=found;
                while(!clickable.isClickable()&&clickable.getParent()!=null)clickable=clickable.getParent();
                if(!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("Click rejected: "+text);
                result.putString("result","Clicked: "+text);
            }
            SystemClock.sleep(400);finish(0,result);
        }catch(Exception e){result.putString("error",e.toString());finish(1,result);}
    }
    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node){
        if(node.isScrollable()&&node.isVisibleToUser())return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null){AccessibilityNodeInfo found=findScrollable(child);if(found!=null)return found;}}
        return null;
    }
}

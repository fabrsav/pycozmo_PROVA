package dev.s200.cozmo;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Credentials stored only on the phone, encrypted with a non-exportable Android Keystore key. */
final class Secrets {
    private static SecretKey key()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        String alias="CozmoS200Wifi";
        if(!ks.containsAlias(alias)){
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return (SecretKey)ks.getKey(alias,null);
    }
    static void save(Context c,String password)throws Exception{
        saveNamed(c,"wifi_v1",password);
    }
    static String read(Context c){
        return readNamed(c,"wifi_v1");
    }
    static void clearNamed(Context c,String prefKey){
        c.getSharedPreferences("robot",0).edit().remove(prefKey).apply();
    }
    static void saveNamed(Context c,String prefKey,String secret)throws Exception{
        if(secret==null||secret.isEmpty()){
            c.getSharedPreferences("robot",0).edit().remove(prefKey).apply();
            return;
        }
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)),iv=cipher.getIV();
        byte[] all=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(encrypted,0,all,iv.length,encrypted.length);
        c.getSharedPreferences("robot",0).edit().putString(prefKey,Base64.encodeToString(all,Base64.NO_WRAP)).apply();
    }
    static String readNamed(Context c,String prefKey){
        String value=c.getSharedPreferences("robot",0).getString(prefKey,null);if(value==null)return "";
        try{
            byte[] all=Base64.decode(value,Base64.NO_WRAP);if(all.length<29)return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(all,12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(all,12,all.length)),StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }
}

package com.convoy.offline;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Versioned, authenticated, bounded framing with a fresh key for every connection. */
public final class Wire {
 public static final int MAGIC=0x43565932, MAX=32768;
 public static final UUID BT_UUID=UUID.fromString("8f4337a0-6c5b-4d88-a926-5cb72ce4bdb3");
 public static final int PORT=45871;
 public static byte[] groupKey(String password) throws Exception {
  if(password==null)throw new IOException("Missing group key");
  PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),"Convoy-v2-group".getBytes("UTF-8"),120000,256);
  try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}
 }
 public static final class Channel {
  private final DataInputStream in; private final DataOutputStream out;
  private final byte[] key, sendPrefix, receivePrefix;
  private long sent=0, received=0;
  public Channel(InputStream input, OutputStream output, byte[] group, boolean server) throws Exception {
   in=new DataInputStream(new BufferedInputStream(input)); out=new DataOutputStream(new BufferedOutputStream(output));
   byte[] own=new byte[32], remote=new byte[32];new SecureRandom().nextBytes(own);
   out.writeInt(MAGIC);out.write(own);out.flush();
   if(in.readInt()!=MAGIC)throw new IOException("Not a Convoy connection");in.readFully(remote);
   Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(group,"HmacSHA256"));
   mac.update(server?own:remote);mac.update(server?remote:own);key=mac.doFinal();
   sendPrefix=new byte[]{0,0,0,(byte)(server?1:2)};receivePrefix=new byte[]{0,0,0,(byte)(server?2:1)};
   send("convoy-auth-v2".getBytes("UTF-8"));
   if(!Arrays.equals(receive(),"convoy-auth-v2".getBytes("UTF-8")))throw new IOException("Wrong group key");
  }
  private byte[] nonce(byte[] prefix,long sequence){return ByteBuffer.allocate(12).put(prefix).putLong(sequence).array();}
  public synchronized void send(byte[] data)throws Exception{
   if(data==null||data.length>MAX)throw new IOException("Packet too large or missing");
   Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce(sendPrefix,sent++)));
   byte[] encrypted=c.doFinal(data);out.writeInt(encrypted.length);out.write(encrypted);out.flush();
  }
  public byte[] receive()throws Exception{
   int n=in.readInt();if(n<16||n>MAX+16)throw new IOException("Invalid frame length");byte[] encrypted=new byte[n];in.readFully(encrypted);
   Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce(receivePrefix,received++)));return c.doFinal(encrypted);
  }
 }
 public static final class Packet {
  public int type;public String id,name,car="generic";public double lat,lon;public float accuracy;public long fixAge;public byte[] audio;
  public byte[] encode()throws IOException{
   validate();
   ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeByte(type);d.writeUTF(id);d.writeUTF(name);d.writeUTF(car==null?"generic":car);
   if(type==1){d.writeDouble(lat);d.writeDouble(lon);d.writeFloat(accuracy);d.writeLong(fixAge);}else if(type==2){d.writeInt(audio.length);d.write(audio);}return b.toByteArray();
  }
  public static Packet decode(byte[] data)throws IOException{
   if(data==null||data.length>MAX)throw new IOException("Packet too large or missing");
   DataInputStream d=new DataInputStream(new ByteArrayInputStream(data));Packet p=new Packet();p.type=d.readUnsignedByte();p.id=d.readUTF();p.name=d.readUTF();p.car=d.readUTF();
   p.validateIdentity();
   if(p.type==1){p.lat=d.readDouble();p.lon=d.readDouble();p.accuracy=d.readFloat();p.fixAge=d.readLong();}
   else if(p.type==2){int n=d.readInt();if(n<2||n>3200||n%2!=0)throw new IOException("Invalid audio");p.audio=new byte[n];d.readFully(p.audio);}else throw new IOException("Unknown packet");
   if(d.available()!=0)throw new IOException("Trailing bytes");p.validate();return p;
  }
  private void validateIdentity()throws IOException{
   if(id==null||id.length()!=36||name==null||name.length()>32||(car!=null&&car.length()>16))throw new IOException("Invalid identity");
  }
  private void validate()throws IOException{
   validateIdentity();
   if(type==1){if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180||!Float.isFinite(accuracy)||accuracy<0||fixAge< -1)throw new IOException("Invalid GPS");}
   else if(type==2){if(audio==null||audio.length<2||audio.length>3200||audio.length%2!=0)throw new IOException("Invalid audio");}
   else throw new IOException("Unknown packet");
  }
 }
}

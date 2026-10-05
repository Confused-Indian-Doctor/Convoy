package com.convoy.offline;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;
public class WireTest {
 static int checks=0;
 static void check(boolean good,String name){if(!good)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static byte[] key;
 static final class Pair implements AutoCloseable {
  Socket a,b;Wire.Channel server,client;
  Pair(byte[] ka,byte[] kb)throws Exception{try(ServerSocket listener=new ServerSocket(0)){a=new Socket("127.0.0.1",listener.getLocalPort());b=listener.accept();a.setSoTimeout(3000);b.setSoTimeout(3000);ExecutorService ex=Executors.newSingleThreadExecutor();Future<Wire.Channel> f=ex.submit(()->new Wire.Channel(b.getInputStream(),b.getOutputStream(),ka,true));try{client=new Wire.Channel(a.getInputStream(),a.getOutputStream(),kb,false);server=f.get();}catch(Exception e){a.close();b.close();throw e;}finally{ex.shutdownNow();}}}
  public void close()throws Exception{a.close();b.close();}
 }
 public static void main(String[] args)throws Exception{
  key=Wire.groupKey("test-only-123456789");check(key.length==32,"256-bit group key");
  check(Arrays.equals(key,Wire.groupKey("test-only-123456789")),"deterministic group derivation");
  try(Pair p=new Pair(key,key)){p.client.send("hello".getBytes("UTF-8"));check(new String(p.server.receive(),"UTF-8").equals("hello"),"authenticated client to host");p.server.send("reply".getBytes("UTF-8"));check(new String(p.client.receive(),"UTF-8").equals("reply"),"authenticated host to client");for(int i=0;i<100;i++){p.client.send(new byte[]{(byte)i});check(p.server.receive()[0]==(byte)i,"ordered frame "+i);}boolean rejected=false;try{p.client.send(new byte[Wire.MAX+1]);}catch(IOException e){rejected=true;}check(rejected,"oversize frame rejected");}
  boolean wrong=false;try(Pair p=new Pair(key,Wire.groupKey("different-password"))){}catch(Exception e){wrong=true;}check(wrong,"wrong group key rejected");
  Wire.Packet p=new Wire.Packet();p.id=UUID.randomUUID().toString();p.name="Al Ameen";p.type=1;p.lat=56.2;p.lon=-4.5;p.accuracy=6;p.fixAge=1234;
  Wire.Packet decoded=Wire.Packet.decode(p.encode());check(decoded.lat==56.2&&decoded.lon==-4.5&&decoded.fixAge==1234,"GPS packet roundtrip");
  p.fixAge=-1;check(Wire.Packet.decode(p.encode()).fixAge==-1,"no-fix sentinel preserved");
  p.lat=Double.NaN;boolean invalid=false;try{Wire.Packet.decode(p.encode());}catch(IOException e){invalid=true;}check(invalid,"invalid coordinate rejected");
  p.lat=56.2;p.type=2;p.audio=new byte[640];new Random(42).nextBytes(p.audio);check(Arrays.equals(p.audio,Wire.Packet.decode(p.encode()).audio),"PCM audio roundtrip");
  byte[] extra=Arrays.copyOf(p.encode(),p.encode().length+1);invalid=false;try{Wire.Packet.decode(extra);}catch(IOException e){invalid=true;}check(invalid,"trailing payload rejected");
  invalid=false;try{Wire.Packet.decode(new byte[]{1,0,20,1});}catch(IOException e){invalid=true;}check(invalid,"truncated payload rejected");
  // Active tampering and replay are rejected by AES-GCM with implicit ordered counters.
  java.security.SecureRandom random=new java.security.SecureRandom();byte[] challenge=new byte[32];random.nextBytes(challenge);
  ByteArrayOutputStream handshake=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(handshake);h.writeInt(Wire.MAGIC);h.write(challenge);
  byte[] badFrame=new byte[32];random.nextBytes(badFrame);h.writeInt(badFrame.length);h.write(badFrame);
  invalid=false;try{new Wire.Channel(new ByteArrayInputStream(handshake.toByteArray()),new ByteArrayOutputStream(),key,false);}catch(Exception e){invalid=true;}check(invalid,"forged authentication frame rejected");
  System.out.println("TOTAL "+checks+" checks passed");
 }
}

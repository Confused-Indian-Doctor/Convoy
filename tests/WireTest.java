package com.convoy.offline;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Transport-independent regression checks for the unchanged Convoy v2 wire format. */
public class WireTest {
 static int checks;
 static void check(boolean good,String name){if(!good)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 interface Checked {void run()throws Exception;}
 static void rejects(Checked operation,String name)throws Exception{
  boolean rejected=false;try{operation.run();}catch(IOException|java.security.GeneralSecurityException expected){rejected=true;}
  check(rejected,name);
 }
 // Queue streams allow the handshake and subsequent traffic to use different threads.
 static final class Pipe implements AutoCloseable {
  final ArrayBlockingQueue<Integer> bytes=new ArrayBlockingQueue<>(Wire.MAX*2+64);
  volatile boolean closed;
  final InputStream input=new InputStream(){
   @Override public int read()throws IOException{
    if(closed&&bytes.isEmpty())return -1;
    try{Integer value=bytes.poll(5,TimeUnit.SECONDS);if(value==null)throw new IOException("Test transport timeout");return value;}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
   }
   @Override public int read(byte[] data,int offset,int length)throws IOException{
    if(length==0)return 0;int first=read();if(first<0)return -1;data[offset]=(byte)first;int count=1;
    while(count<length){Integer value=bytes.poll();if(value==null)break;if(value<0){closed=true;break;}data[offset+count++]=value.byteValue();}return count;
   }
  };
  final OutputStream output=new OutputStream(){
   @Override public void write(int value)throws IOException{
    if(closed)throw new IOException("Test transport closed");
    try{if(!bytes.offer(value&255,5,TimeUnit.SECONDS))throw new IOException("Test transport full");}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
   }
  };
  @Override public void close(){closed=true;bytes.clear();bytes.offer(-1);}
 }
 static final class Pair implements AutoCloseable {
  final Pipe toServer=new Pipe(),toClient=new Pipe();
  final OutputStream clientRaw=toServer.output;
  final ByteArrayOutputStream clientFrames=new ByteArrayOutputStream();
  Wire.Channel server,client;
  Pair(byte[] serverKey,byte[] clientKey)throws Exception{
   ExecutorService ex=Executors.newSingleThreadExecutor();
   Future<Wire.Channel> ready=ex.submit(()->new Wire.Channel(toServer.input,toClient.output,serverKey,true));
   OutputStream observed=new OutputStream(){
    @Override public void write(int value)throws IOException{clientRaw.write(value);clientFrames.write(value);}
    @Override public void write(byte[] bytes,int offset,int length)throws IOException{clientRaw.write(bytes,offset,length);clientFrames.write(bytes,offset,length);}
    @Override public void flush()throws IOException{clientRaw.flush();}
   };
   try{client=new Wire.Channel(toClient.input,observed,clientKey,false);server=ready.get(5,TimeUnit.SECONDS);clientFrames.reset();}
   catch(Exception e){close();throw e;}finally{ex.shutdownNow();}
  }
  @Override public void close(){toServer.close();toClient.close();}
 }
 static Wire.Packet gps(){
  Wire.Packet p=new Wire.Packet();p.id="123e4567-e89b-12d3-a456-426614174000";p.name="Al Ameen";p.car="nc";
  p.type=1;p.lat=56.2;p.lon=-4.5;p.accuracy=6;p.fixAge=1234;return p;
 }
 public static void main(String[] args)throws Exception{
  byte[] key=Wire.groupKey("test-only-123456789");check(key.length==32,"256-bit group key");
  check(Arrays.equals(key,Wire.groupKey("test-only-123456789")),"deterministic group derivation");
  rejects(()->Wire.groupKey(null),"missing group key rejected");
  check(Wire.MAGIC==0x43565932&&Wire.PORT==45871&&Wire.BT_UUID.toString().equals("8f4337a0-6c5b-4d88-a926-5cb72ce4bdb3"),"previous-version transport identifiers preserved");
  try(Pair p=new Pair(key,key)){
   p.client.send("hello".getBytes("UTF-8"));check(new String(p.server.receive(),"UTF-8").equals("hello"),"authenticated client to host");
   p.server.send("reply".getBytes("UTF-8"));check(new String(p.client.receive(),"UTF-8").equals("reply"),"authenticated host to client");
   for(int i=0;i<100;i++){p.client.send(new byte[]{(byte)i});check(p.server.receive()[0]==(byte)i,"ordered frame "+i);}
   byte[] maximum=new byte[Wire.MAX];new Random(42).nextBytes(maximum);p.client.send(maximum);
   check(Arrays.equals(p.server.receive(),maximum),"maximum authenticated frame roundtrip");
   rejects(()->p.client.send(new byte[Wire.MAX+1]),"oversize frame rejected");
   rejects(()->p.client.send(null),"missing frame rejected");
  }
  rejects(()->{try(Pair ignored=new Pair(key,Wire.groupKey("different-password"))){}}, "wrong group key rejected");
  try(Pair p=new Pair(key,key)){
   p.client.send(new byte[]{7});check(p.server.receive()[0]==7,"replay fixture delivered once");
   p.clientRaw.write(p.clientFrames.toByteArray());p.clientRaw.flush();
   rejects(()->p.server.receive(),"replayed authenticated frame rejected");
  }
  try(Pair p=new Pair(key,key)){
   p.client.send(new byte[]{9});p.toServer.bytes.clear();
   byte[] altered=p.clientFrames.toByteArray();altered[altered.length-1]^=1;
   p.clientRaw.write(altered);p.clientRaw.flush();rejects(()->p.server.receive(),"tampered frame rejected");
  }
  byte[] legacyGps=Base64.getDecoder().decode("AQAkMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwAAhBbCBBbWVlbgACbmNATBmZmZmZmsASAAAAAAAAQMAAAAAAAAAAAATS");
  Wire.Packet p=gps(),decoded=Wire.Packet.decode(legacyGps);
  check(decoded.lat==56.2&&decoded.lon==-4.5&&decoded.fixAge==1234&&decoded.car.equals("nc"),"0.4.0 GPS fixture still decodes");
  check(Arrays.equals(p.encode(),legacyGps),"GPS packet bytes remain compatible with 0.4.0");
  p.fixAge=-1;check(Wire.Packet.decode(p.encode()).fixAge==-1,"no-fix sentinel preserved");
  p.lat=Double.NaN;rejects(()->p.encode(),"invalid outgoing coordinate rejected");
  byte[] malformed=legacyGps.clone();int latitudeOffset=malformed.length-28;
  java.nio.ByteBuffer.wrap(malformed,latitudeOffset,8).putDouble(Double.NaN);
  rejects(()->Wire.Packet.decode(malformed),"invalid incoming coordinate rejected");
  p.lat=56.2;p.fixAge=-2;rejects(()->p.encode(),"invalid GPS age rejected");
  p.fixAge=0;p.accuracy=Float.NaN;rejects(()->p.encode(),"invalid accuracy rejected");
  p.accuracy=6;p.car=null;check(Wire.Packet.decode(p.encode()).car.equals("generic"),"default car preserved");
  p.car="nc";p.type=2;p.audio=new byte[]{0x34,0x12,(byte)0xcc,(byte)0xed};
  byte[] legacyAudio=Base64.getDecoder().decode("AgAkMTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc0MDAwAAhBbCBBbWVlbgACbmMAAAAENBLM7Q==");
  check(Arrays.equals(p.audio,Wire.Packet.decode(legacyAudio).audio),"0.4.0 PCM fixture still decodes");
  check(Arrays.equals(p.encode(),legacyAudio),"PCM packet bytes remain compatible with 0.4.0");
  p.audio=new byte[3200];check(Wire.Packet.decode(p.encode()).audio.length==3200,"maximum PCM packet supported");
  p.audio=new byte[3];rejects(()->p.encode(),"partial PCM sample rejected");
  p.audio=null;rejects(()->p.encode(),"missing PCM rejected");
  p.audio=new byte[640];new Random(42).nextBytes(p.audio);check(Arrays.equals(p.audio,Wire.Packet.decode(p.encode()).audio),"PCM audio roundtrip");
  byte[] extra=Arrays.copyOf(p.encode(),p.encode().length+1);rejects(()->Wire.Packet.decode(extra),"trailing payload rejected");
  rejects(()->Wire.Packet.decode(new byte[]{1,0,20,1}),"truncated payload rejected");
  rejects(()->Wire.Packet.decode(new byte[Wire.MAX+1]),"oversized incoming packet rejected");
  rejects(()->Wire.Packet.decode(null),"missing incoming packet rejected");
  p.type=3;rejects(()->p.encode(),"unknown outgoing packet type rejected");
  byte[] unknown=legacyGps.clone();unknown[0]=3;rejects(()->Wire.Packet.decode(unknown),"unknown incoming packet type rejected");
  p.type=1;p.name="012345678901234567890123456789012";rejects(()->p.encode(),"oversized sender name rejected");
  // Authentication cannot be replaced with arbitrary bytes before a packet is exchanged.
  byte[] challenge=new byte[32];new java.security.SecureRandom().nextBytes(challenge);
  ByteArrayOutputStream handshake=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(handshake);
  h.writeInt(Wire.MAGIC);h.write(challenge);byte[] badFrame=new byte[32];new java.security.SecureRandom().nextBytes(badFrame);
  h.writeInt(badFrame.length);h.write(badFrame);
  rejects(()->new Wire.Channel(new ByteArrayInputStream(handshake.toByteArray()),new ByteArrayOutputStream(),key,false),"forged authentication frame rejected");
  System.out.println("TOTAL "+checks+" checks passed");
 }
}

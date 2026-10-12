package com.convoy.offline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** P-256 ECDH, RFC 5869 HKDF-SHA256 and AES-GCM isolate admission from the shared group key.
 * A fresh encrypted server challenge proves possession before any admission is committed.
 * These four bounded frames travel inside the unchanged authenticated Wire.Channel.
 */
public final class AdmissionPrivacy {
 private static final int HELLO=0x43565031,ENVELOPE=0x43564531,MAX=4096;
 private static final SecureRandom RANDOM=new SecureRandom();
 private AdmissionPrivacy(){}
 public static final class Client implements AutoCloseable{
  private final String sid;private final byte[] hello,requestKey,replyKey;private int state;
  private Client(String sid,byte[] hello,byte[][] keys){this.sid=sid;this.hello=hello;requestKey=keys[0];replyKey=keys[1];}
  public byte[] hello(){return hello.clone();}
  public byte[] request(byte[] challenge,byte[] request)throws IOException{
   if(state!=0||request==null||request.length>MAX-64)throw invalid();
   byte[] proof=open(replyKey,sid,"challenge",challenge);if(proof.length!=32)throw invalid();
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.write(proof);out.writeShort(request.length);out.write(request);state=1;
   return seal(requestKey,sid,"request",bytes.toByteArray());
  }
  public byte[] accepted(byte[] reply)throws IOException{if(state!=1)throw invalid();byte[] result=open(replyKey,sid,"ack",reply);state=2;return result;}
  @Override public void close(){Arrays.fill(requestKey,(byte)0);Arrays.fill(replyKey,(byte)0);state=-1;}
  @Override public String toString(){return "Private convoy admission client (credentials hidden)";}
 }
 public static final class Host implements AutoCloseable{
  private final String sid;private final byte[] requestKey,replyKey,challenge=new byte[32];private int state;
  private Host(String sid,byte[][] keys){this.sid=sid;requestKey=keys[0];replyKey=keys[1];RANDOM.nextBytes(challenge);}
  public byte[] challenge()throws IOException{if(state!=0)throw invalid();state=1;return seal(replyKey,sid,"challenge",challenge);}
  public byte[] request(byte[] encrypted)throws IOException{
   if(state!=1)throw invalid();byte[] bytes=open(requestKey,sid,"request",encrypted);DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));byte[] returned=new byte[32];in.readFully(returned);
   if(!MessageDigest.isEqual(challenge,returned))throw invalid();int length=in.readUnsignedShort();if(length>MAX-64||in.available()!=length)throw invalid();byte[] request=new byte[length];in.readFully(request);state=2;return request;
  }
  public byte[] accepted(byte[] ack)throws IOException{if(state!=2)throw invalid();state=3;return seal(replyKey,sid,"ack",ack);}
  @Override public void close(){Arrays.fill(requestKey,(byte)0);Arrays.fill(replyKey,(byte)0);Arrays.fill(challenge,(byte)0);state=-1;}
  @Override public String toString(){return "Private convoy admission host (credentials hidden)";}
 }
 public static Client client(ConvoyInvite invitation)throws IOException{
  if(invitation==null)throw invalid();byte[] shared=null;
  try{KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"),RANDOM);KeyPair pair=generator.generateKeyPair();byte[] client=pair.getPublic().getEncoded(),host=invitation.agreementPublicKey();
   KeyAgreement agreement=KeyAgreement.getInstance("ECDH");agreement.init(pair.getPrivate());agreement.doPhase(publicKey(host),true);shared=agreement.generateSecret();
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(HELLO);out.writeByte(1);ConvoyInvite.writeId(out,invitation.sessionId);out.writeShort(client.length);out.write(client);
   return new Client(invitation.sessionId,bytes.toByteArray(),keys(shared,invitation.sessionId,host,client));
  }catch(GeneralSecurityException error){throw invalid();}finally{if(shared!=null)Arrays.fill(shared,(byte)0);}
 }
 public static Host host(ConvoySession session,byte[] hello)throws IOException{
  if(session==null||hello==null||hello.length<103||hello.length>151)throw invalid();DataInputStream in=new DataInputStream(new ByteArrayInputStream(hello));if(in.readInt()!=HELLO||in.readUnsignedByte()!=1||!session.sessionId.equals(ConvoyInvite.readId(in)))throw invalid();int length=in.readUnsignedShort();if(length<80||length>128||in.available()!=length)throw invalid();byte[] client=new byte[length];in.readFully(client);byte[] shared=session.agreementSecret(publicKey(client));
  try{return new Host(session.sessionId,keys(shared,session.sessionId,session.agreementPublicKey(),client));}finally{Arrays.fill(shared,(byte)0);}
 }
 static PublicKey publicKey(byte[] encoded)throws IOException{
  try{if(encoded==null||encoded.length<80||encoded.length>128)throw invalid();PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));if(!(key instanceof ECPublicKey))throw invalid();
   AlgorithmParameters parameters=AlgorithmParameters.getInstance("EC");parameters.init(new ECGenParameterSpec("secp256r1"));ECParameterSpec expected=parameters.getParameterSpec(ECParameterSpec.class),actual=((ECPublicKey)key).getParams();
   if(!expected.getCurve().equals(actual.getCurve())||!expected.getGenerator().equals(actual.getGenerator())||!expected.getOrder().equals(actual.getOrder())||expected.getCofactor()!=actual.getCofactor())throw invalid();return key;
  }catch(GeneralSecurityException|RuntimeException invalid){throw invalid();}
 }
 private static byte[][] keys(byte[] shared,String sid,byte[] host,byte[] client)throws IOException{
  byte[] prk=null;try{MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(sid.getBytes(StandardCharsets.UTF_8));hash.update(host);byte[] salt=hash.digest(client);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(salt,"HmacSHA256"));prk=mac.doFinal(shared);
   byte[][] result=new byte[2][];for(int i=0;i<2;i++){mac.init(new SecretKeySpec(prk,"HmacSHA256"));mac.update((i==0?"Convoy admission request v1":"Convoy admission reply v1").getBytes(StandardCharsets.US_ASCII));result[i]=mac.doFinal(new byte[]{1});}return result;
  }catch(GeneralSecurityException error){throw invalid();}finally{if(prk!=null)Arrays.fill(prk,(byte)0);}
 }
 private static byte[] seal(byte[] key,String sid,String purpose,byte[] plain)throws IOException{
  if(plain==null||plain.length>MAX-35)throw invalid();try{byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(sid,purpose));byte[] ciphertext=cipher.doFinal(plain);
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(ENVELOPE);out.writeByte(1);out.write(nonce);out.writeShort(ciphertext.length);out.write(ciphertext);return bytes.toByteArray();
  }catch(GeneralSecurityException error){throw invalid();}
 }
 private static byte[] open(byte[] key,String sid,String purpose,byte[] bytes)throws IOException{
  if(bytes==null||bytes.length<35||bytes.length>MAX)throw invalid();DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=ENVELOPE||in.readUnsignedByte()!=1)throw invalid();byte[] nonce=new byte[12];in.readFully(nonce);int length=in.readUnsignedShort();if(length<16||in.available()!=length)throw invalid();byte[] ciphertext=new byte[length];in.readFully(ciphertext);
  try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(sid,purpose));return cipher.doFinal(ciphertext);}catch(GeneralSecurityException error){throw invalid();}
 }
 private static byte[] aad(String sid,String purpose){return ("Convoy admission v1\0"+sid+"\0"+purpose).getBytes(StandardCharsets.UTF_8);}
 private static IOException invalid(){return new IOException("Could not authenticate private convoy admission.");}
}

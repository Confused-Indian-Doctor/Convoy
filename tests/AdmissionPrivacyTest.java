package com.convoy.offline;

import java.io.*;
import java.util.*;

/** Known group members cannot read private reconnect material or replay a captured admission. */
public final class AdmissionPrivacyTest {
 private static int checks;private static final long NOW=1791763200000L;
 private static final String HOST="123e4567-e89b-12d3-a456-426614174000",DRIVER="123e4567-e89b-12d3-a456-426614174001";
 interface Action{void run()throws Exception;}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
 static void rejects(Action action,String message)throws Exception{try{action.run();}catch(IOException expected){check(true,message);return;}throw new AssertionError(message);}
 static boolean contains(byte[] haystack,byte[] needle){outer:for(int i=0;i<=haystack.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(haystack[i+j]!=needle[j])continue outer;return true;}return false;}
 public static void main(String[] args)throws Exception{
  try(ConvoySession session=ConvoySession.create(HOST,"Al",NOW)){
   ConvoyInvite invite=session.createInvite(ConvoyInvite.Network.lan(),Wire.PORT,1,NOW);
   byte[] inner=InviteHandshake.request(invite,DRIVER),firstCipher,firstHello,acceptedToken;
   try(AdmissionPrivacy.Client client=AdmissionPrivacy.client(invite)){
    firstHello=client.hello();check(firstHello.length<160,"only bounded public ECDH material is sent before host authentication");
    byte[] mutated=firstHello.clone();mutated[0]^=1;rejects(()->AdmissionPrivacy.host(session,mutated),"malformed private hello rejected");
    rejects(()->AdmissionPrivacy.host(session,new byte[152]),"oversized private hello rejected");
    rejects(()->client.accepted(new byte[60]),"acknowledgement before key confirmation rejected");
    try(AdmissionPrivacy.Host host=AdmissionPrivacy.host(session,firstHello)){
     byte[] challenge=host.challenge();rejects(()->host.challenge(),"host challenge cannot be issued twice");
     byte[] corrupted=challenge.clone();corrupted[corrupted.length-1]^=1;rejects(()->client.request(corrupted,inner),"tampered host proof rejected before credentials are sent");
     firstCipher=client.request(challenge,inner);check(!contains(firstCipher,invite.token().getBytes("UTF-8")),"signed invite is not exposed to group-key observers");
     rejects(()->client.request(challenge,inner),"client key confirmation cannot be reused");
     byte[] protectedInner=host.request(firstCipher);check(Arrays.equals(inner,protectedInner),"private admission preserves exact inner request");
     rejects(()->host.request(firstCipher),"same-connection admission replay rejected");
     InviteHandshake.Request request=InviteHandshake.readRequest(protectedInner,NOW);
     ConvoySession.AdmissionResult admitted=session.admit(request.invitation,request.driverId,request.resumptionToken(),NOW);
     acceptedToken=admitted.resumptionToken();byte[] ack=host.accepted(InviteHandshake.accepted(session,request,acceptedToken));
     check(!contains(ack,acceptedToken),"private reconnect credential is not present in group-visible acknowledgement");
     check(Arrays.equals(acceptedToken,InviteHandshake.verifyAccepted(client.accepted(ack),invite,inner)),"private signed acknowledgement round trip");
     rejects(()->client.accepted(ack),"private acknowledgement replay rejected");
    }
   }
   try(AdmissionPrivacy.Host replay=AdmissionPrivacy.host(session,firstHello)){
    replay.challenge();rejects(()->replay.request(firstCipher),"captured encrypted join cannot satisfy a fresh host challenge");
   }
   session.setAccepting(false);
   byte[] recovery=InviteHandshake.request(invite,DRIVER,acceptedToken);
   try(AdmissionPrivacy.Client client=AdmissionPrivacy.client(invite);AdmissionPrivacy.Host host=AdmissionPrivacy.host(session,client.hello())){
    byte[] encrypted=client.request(host.challenge(),recovery);check(!contains(encrypted,acceptedToken),"reconnect secret stays private even when the group key is known");
    InviteHandshake.Request request=InviteHandshake.readRequest(host.request(encrypted),NOW);
    check(session.admit(request.invitation,request.driverId,request.resumptionToken(),NOW).status==ConvoySession.Admission.RECONNECTED,"private reconnect works after new admissions close");
   }
   try(AdmissionPrivacy.Client client=AdmissionPrivacy.client(invite);ConvoySession fake=ConvoySession.create(HOST,"Al",NOW)){
    byte[] hello=client.hello();DataInputStream in=new DataInputStream(new ByteArrayInputStream(hello));in.readInt();in.readByte();ConvoyInvite.readId(in);int size=in.readUnsignedShort();byte[] key=new byte[size];in.readFully(key);
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0x43565031);out.writeByte(1);ConvoyInvite.writeId(out,fake.sessionId);out.writeShort(size);out.write(key);
    try(AdmissionPrivacy.Host fakeEndpoint=AdmissionPrivacy.host(fake,bytes.toByteArray())){rejects(()->client.request(fakeEndpoint.challenge(),recovery),"another group endpoint cannot obtain a reconnect credential before proving the correct host");}
   }
   try(WireTest.Pair group=new WireTest.Pair(invite.groupKey(),invite.groupKey());AdmissionPrivacy.Client client=AdmissionPrivacy.client(invite)){
    group.client.send(client.hello());try(AdmissionPrivacy.Host host=AdmissionPrivacy.host(session,group.server.receive())){
     group.server.send(host.challenge());byte[] cipher=client.request(group.client.receive(),recovery);group.client.send(cipher);byte[] groupVisible=group.server.receive();
     check(!contains(groupVisible,acceptedToken),"real AES-GCM group channel still does not reveal another driver's private reconnect token");
     InviteHandshake.Request request=InviteHandshake.readRequest(host.request(groupVisible),NOW);ConvoySession.AdmissionResult result=session.admit(request.invitation,request.driverId,request.resumptionToken(),NOW);
     group.server.send(host.accepted(InviteHandshake.accepted(session,request,result.resumptionToken())));check(Arrays.equals(result.resumptionToken(),InviteHandshake.verifyAccepted(client.accepted(group.client.receive()),invite,recovery)),"complete private admission works inside unchanged encrypted group transport");
    }
   }
   byte[] invalidKey=new byte[91];rejects(()->AdmissionPrivacy.publicKey(invalidKey),"invalid ECDH public key rejected");
   session.close();rejects(()->AdmissionPrivacy.host(session,firstHello),"ended session cannot authenticate private admissions");
  }
  System.out.println("TOTAL "+checks+" admission-privacy checks passed");
 }
}

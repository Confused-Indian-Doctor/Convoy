package com.convoy.offline;

import android.Manifest;import android.annotation.SuppressLint;import android.content.pm.PackageManager;
import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.bluetooth.*;import android.location.*;import android.media.*;import android.media.audiofx.*;import android.net.*;import android.net.wifi.*;import android.os.*;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;

public class ConvoyService extends Service {
 public static volatile ConvoyService current;
 public static final class Member {
  public String id,name,car="generic";public double lat,lon;public float accuracy;public long received,fixAge;public boolean located;
  public long age(){if(!located)return Long.MAX_VALUE;long elapsed=Math.max(0,SystemClock.elapsedRealtime()-received);return fixAge>Long.MAX_VALUE-elapsed?Long.MAX_VALUE:elapsed+fixAge;}
 }
 public final ConcurrentHashMap<String,Member> members=new ConcurrentHashMap<>();
 public volatile String status="Starting…", detail="", hotspotInfo="",talker="";
 public volatile boolean active=false,host=false, bluetooth=false,ptt=false,vox=false,muted=false,transmitting=false;
 public volatile boolean voiceEnabled=false;
 public volatile Location fix; public String myId,myName,myCar="generic";
 private byte[] key;private String address;
 private ConvoySession session;private ConvoyInvite joiningInvite,cachedInvite;
 private byte[] resumptionToken;private String cachedInviteState="";
 private LanDiscovery discovery;private HotspotBootstrap bootstrap;private volatile LanDiscovery.Endpoint endpoint;
 private volatile ConvoyInvite.Network invitationNetwork=ConvoyInvite.Network.lan();private volatile boolean networkReady=false,consumer=false;
 public boolean secureSession(){return consumer&&session!=null&&session.isActive();}
 public boolean invitationsExpired(){return session!=null&&System.currentTimeMillis()>=session.expiresAtMillis;}
 public boolean accepting(){return session!=null&&session.isAccepting()&&!invitationsExpired();}
 public void setAccepting(boolean value){if(session!=null){try{session.setAccepting(value);}catch(IOException invalid){return;}cachedInvite=null;cachedInviteState="";advertiseSession();}}
 public String sessionTitle(){return session!=null?session.convoyName:joiningInvite!=null?joiningInvite.convoyName:"Convoy";}
 public synchronized String inviteLink()throws IOException{if(!secureSession()||!accepting()||!networkReady||server==null)return "";String state=invitationNetwork.mode+"|"+invitationNetwork.ssid+"|"+driverCount()+"|"+server.getLocalPort();if(cachedInvite==null||!state.equals(cachedInviteState)){cachedInvite=session.createInvite(invitationNetwork,server.getLocalPort(),driverCount(),System.currentTimeMillis());cachedInviteState=state;}return cachedInvite.toUri();}
 public String connectionDiagnostics(){return status+"\nTransport: "+(bluetooth?"Bluetooth":"Local Wi-Fi/LAN")+"\n"+addresses()+"\nPort: "+(server!=null?server.getLocalPort():endpoint!=null?endpoint.port:Wire.PORT)+"\nSession: "+(session!=null?session.sessionId:joiningInvite!=null?joiningInvite.sessionId:"Legacy")+"\nDiscovery: "+(discovery==null?"Not active":discovery.status())+"\nProtocol: "+ConvoyInvite.VERSION+"\n"+detail;}
 private void advertiseSession(){if(!active||!host||session==null||server==null||discovery==null)return;try{discovery.advertise(new LanAdvertisement(session.sessionId,ConvoyInvite.VERSION,session.convoyName,driverCount(),session.isAccepting(),server.getLocalPort()));}catch(IOException e){detail="Local discovery is unavailable. Try reconnecting to the same Wi-Fi.";}}
 private void startDiscovery(Network network){if(discovery==null)return;try{discovery.discover(joiningInvite.sessionId,ConvoyInvite.VERSION,network);}catch(IOException unavailable){detail="Could not discover your host. Check that everyone is on the same local Wi-Fi.";}}
private final CopyOnWriteArrayList<Link> links=new CopyOnWriteArrayList<>();
 private final Set<Closeable> pending=ConcurrentHashMap.newKeySet();
 private final ExecutorService workers=Executors.newCachedThreadPool();private final Handler main=new Handler(Looper.getMainLooper());
 private ServerSocket server;private BluetoothServerSocket btServer;private CurrentLocation location;
 private Notification tripNotification;
 private WifiManager.LocalOnlyHotspotReservation hotspot;private WifiManager.WifiLock wifiLock;private PowerManager.WakeLock wakeLock;
 private volatile AudioRecord recorder;private volatile AudioTrack player;private final ArrayBlockingQueue<byte[]> playback=new ArrayBlockingQueue<>(12);
 private volatile long lastSound=0;private volatile String speakerId="";private volatile long speakerUntil=0;
 private AudioManager audioManager;private AudioFocusRequest focusRequest;private volatile boolean audioFocused=true;
 public int connectionCount(){return links.size();}
 public int driverCount(){if(host)return 1+links.size();long now=SystemClock.elapsedRealtime();return Math.max(1+links.size(),1+(int)members.values().stream().filter(m->now-m.received<=10000).count());}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null&&"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(active||intent==null)return START_NOT_STICKY;
  current=this;active=true;host=intent.getBooleanExtra("host",false);bluetooth=intent.getBooleanExtra("bluetooth",false);
  myName=intent.getStringExtra("name");if(myName==null||myName.isEmpty())myName="Driver";if(myName.length()>32)myName=myName.substring(0,32);myCar=intent.getStringExtra("car");if(myCar==null||myCar.isEmpty()||myCar.length()>16)myCar="generic";address=intent.getStringExtra("address");
  myId=getSharedPreferences("convoy",0).getString("id",null);if(myId==null){myId=UUID.randomUUID().toString();getSharedPreferences("convoy",0).edit().putString("id",myId).apply();}
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("trip","Active convoy",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ConvoyService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification n=new Notification.Builder(this,"trip").setSmallIcon(com.convoy.offline.R.drawable.icon).setContentTitle("Convoy • sharing enabled").setContentText("Local GPS and crew connection active • voice optional").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"End trip",stop).build()).build();tripNotification=n;
  try{
   if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Allow Location in Android app settings, then start the convoy again.");
   if(bluetooth)requireBluetoothPermission();
   if(Build.VERSION.SDK_INT>=29){
    int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION|ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
    startForeground(41,n,types);
   }else startForeground(41,n);
   wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Convoy:trip");wakeLock.acquire(12*60*60*1000L);
   WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);if(wm!=null&&!bluetooth){wifiLock=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"Convoy:radio");wifiLock.acquire();}
   location=CurrentLocation.get(this);
   location.setConvoyClient(this,snapshot->fix=snapshot.fix,true);
   audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
   focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener(change->{audioFocused=change==AudioManager.AUDIOFOCUS_GAIN;if(!audioFocused){ptt=false;vox=false;playback.clear();}},main).build();
   audioFocused=audioManager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
   if(!audioFocused)detail="Audio unavailable while another app or call has focus.";
   workers.execute(this::playLoop);
   if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)enableVoice();
   String pass=intent.getStringExtra("key");boolean autoHotspot=intent.getBooleanExtra("hotspot",false);
   consumer=intent.getBooleanExtra("consumer",false);
   if(consumer){
    if(host){session=ConvoySession.create(myId,myName,System.currentTimeMillis());key=session.groupKey();networkReady=!autoHotspot;}
    else{joiningInvite=ConvoyInvite.decode(intent.getStringExtra("invitation"),System.currentTimeMillis());key=joiningInvite.groupKey();bluetooth=joiningInvite.network.mode==ConvoyInvite.Mode.BLUETOOTH;if(bluetooth)address=joiningInvite.network.bluetoothAddress;}
    discovery=new LanDiscovery(this,new LanDiscovery.Listener(){@Override public void onResolved(LanDiscovery.Endpoint resolved){if(joiningInvite!=null&&joiningInvite.sessionId.equals(resolved.metadata.sessionId))endpoint=resolved;}@Override public void onLost(String id){if(joiningInvite!=null&&joiningInvite.sessionId.equals(id))endpoint=null;}@Override public void onNetworkChanged(){if(!host){endpoint=null;detail="Local network changed • finding convoy again";}else advertiseSession();}});
    if(!host&&!bluetooth){
     if(joiningInvite.network.mode==ConvoyInvite.Mode.HOTSPOT){bootstrap=new HotspotBootstrap(this);bootstrap.request(joiningInvite.network.ssid,joiningInvite.network.password(),joiningInvite.network.wpa3,new HotspotBootstrap.Listener(){@Override public void onAvailable(Network network){startDiscovery(network);}@Override public void onUnavailable(String reason){status="Wi-Fi connection needed";detail="Allow the Android Wi-Fi connection request, or join the host's Wi-Fi and try again.";}@Override public void onLost(){endpoint=null;status="Wi-Fi connection lost";}});}
     else startDiscovery(null);
    }
   }

   workers.execute(()->{try{if(!consumer)key=Wire.groupKey(pass);if(!active)return;if(host){if(bluetooth)hostBluetooth();else hostWifi();}else joinLoop();}catch(Exception e){status="Could not start connection";detail=message(e);}});
   if(host&&!bluetooth&&autoHotspot&&wm!=null)try{startAutomaticHotspot(wm);}catch(RuntimeException e){networkReady=true;detail="Automatic hotspot unavailable. Connect everyone to the same local Wi-Fi/router.";}
   main.post(heartbeat);
  }catch(Exception e){status="Permission or device error";detail=message(e);stopSelf();}
  return START_NOT_STICKY;
 }
 /** Called from the visible Activity after the user explicitly grants convoy voice permission. */
 public synchronized boolean enableVoice(){
  if(voiceEnabled)return true;
  if(!active||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false;
  try{
   if(Build.VERSION.SDK_INT>=30){int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION|ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;startForeground(41,tripNotification,types);}
   voiceEnabled=true;workers.execute(this::recordLoop);return true;
  }catch(RuntimeException e){voiceEnabled=false;detail="Voice unavailable: "+message(e);return false;}
 }
 private void requireBluetoothPermission()throws IOException{
  String permission=Build.VERSION.SDK_INT>=31?Manifest.permission.BLUETOOTH_CONNECT:Manifest.permission.BLUETOOTH;
  if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)throw new IOException("Allow Nearby devices / Bluetooth in Android app settings, then start the trip again.");
 }
 // Android 13 changes the required runtime permission; lint cannot model this helper's SDK-dependent guard.
 @SuppressLint("MissingPermission")
 private void startAutomaticHotspot(WifiManager wm){
  if(checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Wi-Fi control permission is unavailable.");
  if(Build.VERSION.SDK_INT>=33){if(checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Allow Nearby Wi-Fi devices for an automatic hotspot.");}
  else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Allow precise Location for an automatic hotspot.");
  wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback(){
   @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r){if(!active){r.close();return;}hotspot=r;try{String ssid,password;boolean wpa3=false;if(Build.VERSION.SDK_INT>=30){SoftApConfiguration c=r.getSoftApConfiguration();ssid=c.getSsid();password=c.getPassphrase();wpa3=c.getSecurityType()==SoftApConfiguration.SECURITY_TYPE_WPA3_SAE;}else{android.net.wifi.WifiConfiguration c=r.getWifiConfiguration();ssid=c.SSID;password=c.preSharedKey;if(ssid!=null&&ssid.startsWith("\""))ssid=ssid.substring(1,ssid.length()-1);if(password!=null&&password.startsWith("\""))password=password.substring(1,password.length()-1);}if(consumer){invitationNetwork=ConvoyInvite.Network.hotspot(ssid,password,wpa3);hotspotInfo="Local convoy Wi-Fi ready";}else hotspotInfo="Wi-Fi: "+ssid+"\nPassword: "+password;networkReady=true;cachedInvite=null;advertiseSession();detail="Friends can scan your invite to request joining this local Wi-Fi.";}catch(Exception invalid){detail="Could not prepare Wi-Fi invitation. Use an existing local Wi-Fi network.";}}
   @Override public void onFailed(int reason){networkReady=true;invitationNetwork=ConvoyInvite.Network.lan();detail="Automatic hotspot unavailable. Connect everyone to the same Wi-Fi/router, then scan the invite.";}
   @Override public void onStopped(){hotspotInfo="Hotspot stopped. Reconnect Wi-Fi or restart trip.";}
  },main);
 }
 private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 private final Runnable heartbeat=new Runnable(){public void run(){if(!active)return;try{
  Wire.Packet p=new Wire.Packet();p.type=1;p.id=myId;p.name=myName;p.car=myCar;Location f=fix;
  if(f==null){p.fixAge=-1;p.lat=0;p.lon=0;p.accuracy=0;}else{p.fixAge=Math.max(0,(SystemClock.elapsedRealtimeNanos()-f.getElapsedRealtimeNanos())/1000000);p.lat=f.getLatitude();p.lon=f.getLongitude();p.accuracy=f.getAccuracy();}
  broadcast(p.encode(),null);
  if(consumer&&host)advertiseSession();long now=SystemClock.elapsedRealtime();for(Link l:links)if(now-l.lastRead>10000)l.close();
  // Bound departed convoy state without erasing the immediately useful last known position.
  for(Member m:members.values())if(now-m.received>30*60*1000L)members.remove(m.id,m);
 }catch(Exception e){detail=message(e);}main.postDelayed(this,2000);}};
 // All Bluetooth helpers check the runtime permission immediately before protected APIs.
 @SuppressLint("MissingPermission")
 private BluetoothAdapter adapter()throws IOException{requireBluetoothPermission();BluetoothManager manager=getSystemService(BluetoothManager.class);BluetoothAdapter a=manager==null?null:manager.getAdapter();if(a==null||!a.isEnabled())throw new IOException("Turn on Bluetooth in Android settings");return a;}
 private void hostWifi()throws Exception{
  server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(consumer?0:Wire.PORT));status="Hosting on Wi-Fi";if(consumer)main.post(this::advertiseSession);
  while(active){Socket s=server.accept();s.setTcpNoDelay(true);s.setSoTimeout(12000);if(links.size()+pending.size()>=6){s.close();continue;}pending.add(s);workers.execute(()->connect(s,true));}
 }
 @SuppressLint("MissingPermission")
 private void hostBluetooth()throws Exception{
  requireBluetoothPermission();btServer=adapter().listenUsingRfcommWithServiceRecord("Convoy",Wire.BT_UUID);status="Hosting on Bluetooth";
  while(active){requireBluetoothPermission();BluetoothSocket s=btServer.accept();if(links.size()+pending.size()>=4){s.close();continue;}pending.add(s);workers.execute(()->connect(s,true));}
 }
 @SuppressLint("MissingPermission")
 private BluetoothSocket openBluetoothConnection()throws Exception{
  requireBluetoothPermission();
  BluetoothSocket s=adapter().getRemoteDevice(address).createRfcommSocketToServiceRecord(Wire.BT_UUID);
  pending.add(s);Runnable timeout=()->closeSocket(s);main.postDelayed(timeout,15000);
  try{s.connect();return s;}catch(Exception e){pending.remove(s);closeSocket(s);throw e;}
  finally{main.removeCallbacks(timeout);}
 }
 /** Older NSD APIs do not identify the network: explicitly bind LAN traffic, never the cellular default. */
 private Network localWifiNetwork(InetAddress hostAddress){ConnectivityManager cm=getSystemService(ConnectivityManager.class);Network fallback=null;for(Network network:cm.getAllNetworks()){NetworkCapabilities capabilities=cm.getNetworkCapabilities(network);if(capabilities==null||!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;if(fallback==null)fallback=network;LinkProperties properties=cm.getLinkProperties(network);if(properties!=null)for(RouteInfo route:properties.getRoutes())if(route.getDestination().getPrefixLength()>0&&route.getDestination().contains(hostAddress))return network;}return fallback;}
 private void joinLoop(){while(active){Closeable socket=null;try{
  status="Connecting to host…";
  if(bluetooth)socket=openBluetoothConnection();
  else{Socket s=new Socket();socket=s;pending.add(s);if(consumer){LanDiscovery.Endpoint target=endpoint;if(target==null){status="Finding convoy on local Wi-Fi…";throw new IOException("Host discovery pending");}Network n=bootstrap!=null?bootstrap.network():target.network;if(n==null)n=localWifiNetwork(target.host);if(n==null)throw new IOException("Local Wi-Fi connection required");n.bindSocket(s);s.connect(new InetSocketAddress(target.host,target.port),7000);}else{ConnectivityManager cm=getSystemService(ConnectivityManager.class);for(Network n:cm.getAllNetworks()){NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){n.bindSocket(s);break;}}s.connect(new InetSocketAddress(address,Wire.PORT),7000);}s.setTcpNoDelay(true);s.setSoTimeout(12000);}
  if(active)connect(socket,false);
 }catch(Exception e){if(active){status=consumer&&endpoint==null?"Finding convoy on local Wi-Fi…":"Disconnected • retrying";detail=consumer?"Keep the same local Wi-Fi connection. Waiting for the convoy host.":message(e)+". Check range, host and group key.";if(consumer&&discovery!=null)main.post(discovery::rediscover);}}
 finally{if(socket!=null){pending.remove(socket);closeSocket(socket);}}
 if(active)try{Thread.sleep(4000);}catch(InterruptedException e){return;}
 }}
 private void connect(Closeable socket,boolean serverSide){Runnable timeout=()->closeSocket(socket);main.postDelayed(timeout,12000);Link link=null;
  try{
   InputStream in;OutputStream out;if(socket instanceof Socket){in=((Socket)socket).getInputStream();out=((Socket)socket).getOutputStream();}else{in=((BluetoothSocket)socket).getInputStream();out=((BluetoothSocket)socket).getOutputStream();}
   Wire.Channel channel=new Wire.Channel(in,out,key,serverSide);String admittedId=null;
   if(consumer){if(serverSide){InviteHandshake.Request request=InviteHandshake.readRequest(channel.receive(),System.currentTimeMillis());ConvoySession.AdmissionResult result=session.admit(request.invitation,request.driverId,request.resumptionToken(),System.currentTimeMillis());if(result.status==ConvoySession.Admission.NEEDS_APPROVAL)throw new IOException("Host approval required");channel.send(InviteHandshake.accepted(session,request,result.resumptionToken()));admittedId=request.driverId;}else{byte[] requestFrame=InviteHandshake.request(joiningInvite,myId,resumptionToken);channel.send(requestFrame);resumptionToken=InviteHandshake.verifyAccepted(channel.receive(),joiningInvite,requestFrame);}}
   main.removeCallbacks(timeout);if(!active)return;
   link=new Link(socket,channel);link.identity=admittedId;if(admittedId!=null)for(Link other:links)if(admittedId.equals(other.identity))other.close();pending.remove(socket);links.add(link);status=host?"Hosting on "+(bluetooth?"Bluetooth":"Wi-Fi"):"Connected to convoy";detail="Encrypted local connection • no mobile data required";
   final Link ready=link;workers.execute(ready::writeLoop);link.readLoop();
  }catch(Exception e){if(active){detail=consumer?"Could not authenticate or retain the convoy connection. Ask the host for a fresh invite.":"Connection ended: "+message(e);if(!host)status="Disconnected • retrying";}}
  finally{main.removeCallbacks(timeout);pending.remove(socket);if(link!=null)link.close();else closeSocket(socket);}
 }
 private static void closeSocket(Closeable socket){try{socket.close();}catch(Exception ignored){}}
 private final class Link {
  final Closeable socket;final Wire.Channel channel;final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(12);volatile boolean open=true;volatile long lastRead=SystemClock.elapsedRealtime();volatile String identity;
  Link(Closeable s,Wire.Channel c){socket=s;channel=c;}
  void offer(byte[] p){if(!open)return;if(!queue.offer(p)){queue.poll();queue.offer(p);}}
  void writeLoop(){try{while(active&&open){byte[] p=queue.poll(1,TimeUnit.SECONDS);if(p!=null)channel.send(p);}}catch(Exception ignored){}finally{close();}}
  void readLoop()throws Exception{while(active&&open){byte[] bytes=channel.receive();Wire.Packet p=Wire.Packet.decode(bytes);lastRead=SystemClock.elapsedRealtime();
   if(host){if(identity==null){identity=p.id;for(Link other:links)if(other!=this&&identity.equals(other.identity))other.close();}if(!identity.equals(p.id)||p.id.equals(myId))throw new IOException("Unexpected sender");}
   if(p.id.equals(myId))continue;
   if(p.type==1){Member m=new Member();m.id=p.id;m.name=p.name;m.car=p.car;m.lat=p.lat;m.lon=p.lon;m.accuracy=p.accuracy;m.fixAge=p.fixAge;m.received=lastRead;m.located=p.fixAge>=0;members.put(m.id,m);}
   if(p.type==2){if(!host||(!transmitting&&(speakerId.equals(p.id)||lastRead>speakerUntil))){speakerId=p.id;speakerUntil=lastRead+350;talker=p.name;lastSound=lastRead;if(!muted&&!transmitting&&audioFocused){if(!playback.offer(p.audio)){playback.poll();playback.offer(p.audio);}}if(host)broadcast(bytes,this);}}
   else if(host)broadcast(bytes,this);
  }}
  void close(){if(!open)return;open=false;links.remove(this);closeSocket(socket);queue.clear();if(!host&&active)status="Disconnected • retrying";}
 }
 private void broadcast(byte[] p,Link except){for(Link l:links)if(l!=except)l.offer(p);}
 public String voiceStatus(){if(!audioFocused)return "Audio paused by another app";if(transmitting)return "Transmitting • "+myName;if(SystemClock.elapsedRealtime()-lastSound<600)return "Speaking • "+talker;if(!voiceEnabled)return "Listening • allow microphone to talk";return vox?"Hands-free • listening for your voice":"Ready to listen";}
 private void recordLoop(){AudioRecord r=null;AcousticEchoCanceler echo=null;NoiseSuppressor noise=null;
  try{
   if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone permission was not granted.");
   int minimum=AudioRecord.getMinBufferSize(8000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(minimum<0)throw new IOException("8 kHz audio unsupported");
   r=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,8000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,3200));recorder=r;
   if(r.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone unavailable");
   if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(r.getAudioSessionId());if(echo!=null)echo.setEnabled(true);}
   if(NoiseSuppressor.isAvailable()){noise=NoiseSuppressor.create(r.getAudioSessionId());if(noise!=null)noise.setEnabled(true);}
   byte[] buffer=new byte[640];boolean recording=false;long hold=0;
   while(active){
    boolean want=(ptt||vox)&&audioFocused&&connectionCount()>0;
    if(!want){transmitting=false;if(recording){r.stop();recording=false;}Thread.sleep(40);continue;}
    if(!recording){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone permission was revoked.");r.startRecording();recording=true;}
    int n=r.read(buffer,0,buffer.length);if(n<=0){Thread.sleep(40);continue;}
    long now=SystemClock.elapsedRealtime();long energy=0;for(int i=0;i+1<n;i+=2){short s=(short)((buffer[i]&255)|(buffer[i+1]<<8));energy+=(long)s*s;}
    double rms=Math.sqrt(energy/(n/2.0));if(rms>1800)hold=now+300;
    boolean speak=ptt||(vox&&now<hold&&now-lastSound>600);
    // Half duplex: wait for the current speaker to finish. PTT never mixes with incoming speech.
    speak=speak&&now-lastSound>400;
    transmitting=speak;
    if(speak&&active&&audioFocused&&(ptt||vox)){Wire.Packet p=new Wire.Packet();p.type=2;p.id=myId;p.name=myName;p.car=myCar;p.audio=Arrays.copyOf(buffer,n-n%2);broadcast(p.encode(),null);}
   }
  }catch(Exception e){if(active)detail="Microphone: "+message(e);}
  finally{transmitting=false;voiceEnabled=false;ptt=false;vox=false;if(echo!=null)echo.release();if(noise!=null)noise.release();if(r!=null){try{r.stop();}catch(Exception ignored){}r.release();}recorder=null;}
 }
 private void playLoop(){AudioTrack t=null;try{
  int min=AudioTrack.getMinBufferSize(8000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<0)throw new IOException("8 kHz playback unsupported");
  t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(Math.max(min,3200)).setTransferMode(AudioTrack.MODE_STREAM).build();player=t;t.play();
  while(active){byte[] data=playback.poll(1,TimeUnit.SECONDS);if(data!=null&&!muted&&!transmitting&&audioFocused)t.write(data,0,data.length);}
 }catch(Exception e){if(active)detail="Speaker: "+message(e);}finally{if(t!=null){try{t.stop();}catch(Exception ignored){}t.release();}player=null;}}
 public String addresses(){try{ArrayList<String> list=new ArrayList<>();Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface n=all.nextElement();if(!n.isUp()||n.isLoopback())continue;Enumeration<InetAddress> addresses=n.getInetAddresses();while(addresses.hasMoreElements()){InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&a.isSiteLocalAddress())list.add(a.getHostAddress()+" ("+n.getName()+")");}}return list.isEmpty()?"Waiting for a Wi-Fi / hotspot address…":android.text.TextUtils.join("\n",list);}catch(Exception e){return "IP address unavailable";}}
 @Override public void onDestroy(){
  active=false;ptt=false;vox=false;transmitting=false;voiceEnabled=false;main.removeCallbacksAndMessages(null);
  // Stop blocking native audio reads before interrupting workers; each worker owns release().
  AudioRecord r=recorder;if(r!=null)try{r.stop();}catch(RuntimeException ignored){}
  AudioTrack t=player;if(t!=null)try{t.stop();t.flush();}catch(RuntimeException ignored){}
  for(Link l:links)l.close();for(Closeable c:pending)closeSocket(c);pending.clear();
  if(server!=null)closeSocket(server);if(btServer!=null)try{btServer.close();}catch(Exception ignored){}
  if(session!=null)session.close();cachedInvite=null;joiningInvite=null;if(key!=null)Arrays.fill(key,(byte)0);if(resumptionToken!=null)Arrays.fill(resumptionToken,(byte)0);if(discovery!=null)discovery.close();if(bootstrap!=null)bootstrap.close();if(hotspot!=null)hotspot.close();if(location!=null)location.setConvoyClient(this,null,false);
  if(audioManager!=null&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);
  if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
  workers.shutdownNow();playback.clear();members.clear();if(current==this)current=null;
  stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
 }
}

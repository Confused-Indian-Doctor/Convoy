package com.convoy.offline;

import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.bluetooth.*;import android.location.*;import android.media.*;import android.media.audiofx.*;import android.net.*;import android.net.wifi.*;import android.os.*;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;

public class ConvoyService extends Service implements LocationListener {
 public static volatile ConvoyService current;
 public static final class Member {
  public String id,name,car="generic";public double lat,lon;public float accuracy;public long received,fixAge;public boolean located;
  public long age(){return located?Math.max(0,SystemClock.elapsedRealtime()-received)+fixAge:Long.MAX_VALUE;}
 }
 public final ConcurrentHashMap<String,Member> members=new ConcurrentHashMap<>();
 public volatile String status="Starting…", detail="", hotspotInfo="",talker="";
 public volatile boolean active=false,host=false, bluetooth=false,ptt=false,vox=false,muted=false,transmitting=false;
 public volatile Location fix; public String myId,myName,myCar="generic";
 private byte[] key;private String address;private final CopyOnWriteArrayList<Link> links=new CopyOnWriteArrayList<>();
 private final Set<Closeable> pending=ConcurrentHashMap.newKeySet();
 private final ExecutorService workers=Executors.newCachedThreadPool();private final Handler main=new Handler(Looper.getMainLooper());
 private ServerSocket server;private BluetoothServerSocket btServer;private LocationManager gps;
 private WifiManager.LocalOnlyHotspotReservation hotspot;private WifiManager.WifiLock wifiLock;private PowerManager.WakeLock wakeLock;
 private volatile AudioRecord recorder;private volatile AudioTrack player;private final ArrayBlockingQueue<byte[]> playback=new ArrayBlockingQueue<>(12);
 private volatile long lastSound=0;private volatile String speakerId="";private volatile long speakerUntil=0;
 private AudioManager audioManager;private AudioFocusRequest focusRequest;private volatile boolean audioFocused=true;
 private long lastGpsNotice=0;
 public int connectionCount(){return links.size();}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null&&"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(active||intent==null)return START_NOT_STICKY;
  current=this;active=true;host=intent.getBooleanExtra("host",false);bluetooth=intent.getBooleanExtra("bluetooth",false);
  myName=intent.getStringExtra("name");myCar=intent.getStringExtra("car");if(myCar==null||myCar.isEmpty())myCar="generic";address=intent.getStringExtra("address");
  myId=getSharedPreferences("convoy",0).getString("id",null);if(myId==null){myId=UUID.randomUUID().toString();getSharedPreferences("convoy",0).edit().putString("id",myId).apply();}
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("trip","Active convoy",NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ConvoyService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification n=new Notification.Builder(this,"trip").setSmallIcon(com.convoy.offline.R.drawable.icon).setContentTitle("Convoy • sharing enabled").setContentText("GPS active · microphone used for talk / hands-free").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"End trip",stop).build()).build();
  try{
   if(Build.VERSION.SDK_INT>=29)startForeground(41,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(41,n);
   wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Convoy:trip");wakeLock.acquire(12*60*60*1000L);
   WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);wifiLock=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"Convoy:radio");wifiLock.acquire();
   gps=(LocationManager)getSystemService(LOCATION_SERVICE);
   if(gps.isProviderEnabled(LocationManager.GPS_PROVIDER))gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,0,this,Looper.getMainLooper());
   else detail="Turn on Location in Android settings. Waiting for GPS.";
   audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
   focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener(change->{audioFocused=change==AudioManager.AUDIOFOCUS_GAIN;if(!audioFocused){ptt=false;vox=false;playback.clear();}},main).build();
   audioFocused=audioManager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
   if(!audioFocused)detail="Audio unavailable while another app or call has focus.";
   workers.execute(this::recordLoop);workers.execute(this::playLoop);
   String pass=intent.getStringExtra("key");boolean autoHotspot=intent.getBooleanExtra("hotspot",false);
   workers.execute(()->{try{key=Wire.groupKey(pass);if(!active)return;if(host){if(bluetooth)hostBluetooth();else hostWifi();}else joinLoop();}catch(Exception e){status="Could not start connection";detail=message(e);}});
   if(host&&!bluetooth&&autoHotspot)wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback(){
    @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r){if(!active){r.close();return;}hotspot=r;if(Build.VERSION.SDK_INT>=30){SoftApConfiguration c=r.getSoftApConfiguration();hotspotInfo="Wi-Fi: "+c.getSsid()+"\nPassword: "+c.getPassphrase();}else{android.net.wifi.WifiConfiguration c=r.getWifiConfiguration();hotspotInfo="Wi-Fi: "+c.SSID+"\nPassword: "+c.preSharedKey;}detail="Ask friends to join this Wi-Fi, then enter this phone’s IP below.";}
    @Override public void onFailed(int reason){detail="Automatic hotspot unavailable ("+reason+"). Turn on your phone’s hotspot manually, or use Bluetooth.";}
    @Override public void onStopped(){hotspotInfo="Hotspot stopped. Reconnect Wi-Fi or restart trip.";}
   },main);
   main.post(heartbeat);
  }catch(Exception e){status="Permission or device error";detail=message(e);stopSelf();}
  return START_NOT_STICKY;
 }
 private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 private final Runnable heartbeat=new Runnable(){public void run(){if(!active)return;try{
  Wire.Packet p=new Wire.Packet();p.type=1;p.id=myId;p.name=myName;p.car=myCar;Location f=fix;
  if(f==null){p.fixAge=-1;p.lat=0;p.lon=0;p.accuracy=0;}else{p.fixAge=Math.max(0,(SystemClock.elapsedRealtimeNanos()-f.getElapsedRealtimeNanos())/1000000);p.lat=f.getLatitude();p.lon=f.getLongitude();p.accuracy=f.getAccuracy();}
  broadcast(p.encode(),null);
  long now=SystemClock.elapsedRealtime();for(Link l:links)if(now-l.lastRead>10000)l.close();
  // Bound departed convoy state without erasing the immediately useful last known position.
  for(Member m:members.values())if(now-m.received>30*60*1000L)members.remove(m.id,m);
 }catch(Exception e){detail=message(e);}main.postDelayed(this,2000);}};
 @Override public void onLocationChanged(Location l){fix=new Location(l);}
 @Override public void onProviderEnabled(String p){try{gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,0,this,Looper.getMainLooper());}catch(SecurityException ignored){}}
 @Override public void onProviderDisabled(String p){detail="GPS disabled • positions will become stale";}
 @Override public void onStatusChanged(String p,int s,Bundle b){}
 private BluetoothAdapter adapter()throws IOException{BluetoothManager manager=getSystemService(BluetoothManager.class);BluetoothAdapter a=manager==null?null:manager.getAdapter();if(a==null||!a.isEnabled())throw new IOException("Turn on Bluetooth in Android settings");return a;}
 private void hostWifi()throws Exception{
  server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(Wire.PORT));status="Hosting on Wi-Fi";
  while(active){Socket s=server.accept();s.setTcpNoDelay(true);s.setSoTimeout(12000);if(links.size()+pending.size()>=6){s.close();continue;}pending.add(s);workers.execute(()->connect(s,true));}
 }
 private void hostBluetooth()throws Exception{
  btServer=adapter().listenUsingRfcommWithServiceRecord("Convoy",Wire.BT_UUID);status="Hosting on Bluetooth";
  while(active){BluetoothSocket s=btServer.accept();if(links.size()+pending.size()>=4){s.close();continue;}pending.add(s);workers.execute(()->connect(s,true));}
 }
 private void joinLoop(){while(active){Closeable socket=null;try{
  status="Connecting to host…";
  if(bluetooth){BluetoothSocket s=adapter().getRemoteDevice(address).createRfcommSocketToServiceRecord(Wire.BT_UUID);socket=s;pending.add(s);final Closeable timeoutSocket=s;Runnable timeout=()->closeSocket(timeoutSocket);main.postDelayed(timeout,15000);try{s.connect();}finally{main.removeCallbacks(timeout);}}
  else{Socket s=new Socket();socket=s;pending.add(s);ConnectivityManager cm=getSystemService(ConnectivityManager.class);for(Network n:cm.getAllNetworks()){NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){n.bindSocket(s);break;}}s.connect(new InetSocketAddress(address,Wire.PORT),7000);s.setTcpNoDelay(true);s.setSoTimeout(12000);}
  if(active)connect(socket,false);
 }catch(Exception e){if(active){status="Disconnected • retrying";detail=message(e)+". Check range, host and group key.";}}
 finally{if(socket!=null){pending.remove(socket);closeSocket(socket);}}
 if(active)try{Thread.sleep(4000);}catch(InterruptedException e){return;}
 }}
 private void connect(Closeable socket,boolean serverSide){Runnable timeout=()->closeSocket(socket);main.postDelayed(timeout,12000);Link link=null;
  try{
   InputStream in;OutputStream out;if(socket instanceof Socket){in=((Socket)socket).getInputStream();out=((Socket)socket).getOutputStream();}else{in=((BluetoothSocket)socket).getInputStream();out=((BluetoothSocket)socket).getOutputStream();}
   Wire.Channel channel=new Wire.Channel(in,out,key,serverSide);main.removeCallbacks(timeout);if(!active)return;
   link=new Link(socket,channel);pending.remove(socket);links.add(link);status=host?"Hosting on "+(bluetooth?"Bluetooth":"Wi-Fi"):"Connected to convoy";detail="Encrypted local connection • no mobile data required";
   final Link ready=link;workers.execute(ready::writeLoop);link.readLoop();
  }catch(Exception e){if(active){detail="Connection ended: "+message(e);if(!host)status="Disconnected • retrying";}}
  finally{main.removeCallbacks(timeout);pending.remove(socket);if(link!=null)link.close();else closeSocket(socket);}
 }
 private static void closeSocket(Closeable socket){try{socket.close();}catch(Exception ignored){}}
 private final class Link {
  final Closeable socket;final Wire.Channel channel;final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(12);volatile boolean open=true;volatile long lastRead=SystemClock.elapsedRealtime();String identity;
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
 public String voiceStatus(){if(!audioFocused)return "Audio paused by another app";if(transmitting)return "Transmitting • "+myName;if(SystemClock.elapsedRealtime()-lastSound<600)return "Speaking • "+talker;return vox?"Hands-free • listening for your voice":"Ready to listen";}
 private void recordLoop(){AudioRecord r=null;AcousticEchoCanceler echo=null;NoiseSuppressor noise=null;
  try{
   int minimum=AudioRecord.getMinBufferSize(8000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(minimum<0)throw new IOException("8 kHz audio unsupported");
   r=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,8000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,3200));recorder=r;
   if(r.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone unavailable");
   if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(r.getAudioSessionId());if(echo!=null)echo.setEnabled(true);}
   if(NoiseSuppressor.isAvailable()){noise=NoiseSuppressor.create(r.getAudioSessionId());if(noise!=null)noise.setEnabled(true);}
   byte[] buffer=new byte[640];boolean recording=false;long hold=0;
   while(active){
    boolean want=(ptt||vox)&&audioFocused&&connectionCount()>0;
    if(!want){transmitting=false;if(recording){r.stop();recording=false;}Thread.sleep(40);continue;}
    if(!recording){r.startRecording();recording=true;}
    int n=r.read(buffer,0,buffer.length);if(n<=0){Thread.sleep(40);continue;}
    long now=SystemClock.elapsedRealtime();long energy=0;for(int i=0;i+1<n;i+=2){short s=(short)((buffer[i]&255)|(buffer[i+1]<<8));energy+=(long)s*s;}
    double rms=Math.sqrt(energy/(n/2.0));if(rms>1800)hold=now+300;
    boolean speak=ptt||(vox&&now<hold&&now-lastSound>600);
    // Half duplex: wait for the current speaker to finish. PTT never mixes with incoming speech.
    speak=speak&&now-lastSound>400;
    transmitting=speak;
    if(speak){Wire.Packet p=new Wire.Packet();p.type=2;p.id=myId;p.name=myName;p.car=myCar;p.audio=Arrays.copyOf(buffer,n-n%2);broadcast(p.encode(),null);}
   }
  }catch(Exception e){if(active)detail="Microphone: "+message(e);}
  finally{transmitting=false;if(echo!=null)echo.release();if(noise!=null)noise.release();if(r!=null){try{r.stop();}catch(Exception ignored){}r.release();}recorder=null;}
 }
 private void playLoop(){AudioTrack t=null;try{
  int min=AudioTrack.getMinBufferSize(8000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
  t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(Math.max(min,3200)).setTransferMode(AudioTrack.MODE_STREAM).build();player=t;t.play();
  while(active){byte[] data=playback.poll(1,TimeUnit.SECONDS);if(data!=null&&!muted&&!transmitting&&audioFocused)t.write(data,0,data.length);}
 }catch(Exception e){if(active)detail="Speaker: "+message(e);}finally{if(t!=null){try{t.stop();}catch(Exception ignored){}t.release();}player=null;}}
 public String addresses(){try{ArrayList<String> list=new ArrayList<>();Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface n=all.nextElement();if(!n.isUp()||n.isLoopback())continue;Enumeration<InetAddress> addresses=n.getInetAddresses();while(addresses.hasMoreElements()){InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&a.isSiteLocalAddress())list.add(a.getHostAddress()+" ("+n.getName()+")");}}return list.isEmpty()?"Waiting for a Wi-Fi / hotspot address…":android.text.TextUtils.join("\n",list);}catch(Exception e){return "IP address unavailable";}}
 @Override public void onDestroy(){active=false;ptt=false;vox=false;main.removeCallbacksAndMessages(null);for(Link l:links)l.close();for(Closeable c:pending)closeSocket(c);pending.clear();if(server!=null)closeSocket(server);if(btServer!=null)try{btServer.close();}catch(Exception ignored){}if(hotspot!=null)hotspot.close();if(gps!=null)gps.removeUpdates(this);if(audioManager!=null&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();workers.shutdownNow();members.clear();current=null;stopForeground(true);super.onDestroy();}
}

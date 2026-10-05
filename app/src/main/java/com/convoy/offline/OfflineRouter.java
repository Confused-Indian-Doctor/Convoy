package com.convoy.offline;

import android.content.Context;
import java.io.*;
import java.util.*;

/** Compact A* car router over the bundled Shrewsbury road graph. */
public final class OfflineRouter {
 public static final class Instruction {
  public String text; public double lat,lon; public float distanceFromStart;
  Instruction(String t,double a,double o,float d){text=t;lat=a;lon=o;distanceFromStart=d;}
 }
 public static final class Route {
  public final ArrayList<double[]> points=new ArrayList<>();
  public final ArrayList<Instruction> instructions=new ArrayList<>();
  public float distanceMeters; public int etaSeconds;
 }
 private double[] lat,lon; private int[] first,count,to,nameId; private float[] edgeDistance,edgeSpeed; private String[] names;
 private volatile boolean ready=false; public boolean isReady(){return ready;}
 public synchronized void load(Context c)throws IOException{
  if(ready)return;
  try(DataInputStream d=new DataInputStream(new BufferedInputStream(c.getAssets().open("route.graph"),1<<20))){
   if(d.readInt()!=0x43564731)throw new IOException("Unsupported route graph");
   int n=d.readInt(),e=d.readInt(),m=d.readInt();
   if(n<1||n>1500000||e<1||e>6000000||m<1||m>500000)throw new IOException("Invalid route graph");
   lat=new double[n];lon=new double[n];first=new int[n];count=new int[n];
   for(int i=0;i<n;i++){lat[i]=d.readDouble();lon[i]=d.readDouble();first[i]=d.readInt();count[i]=d.readInt();}
   to=new int[e];edgeDistance=new float[e];edgeSpeed=new float[e];nameId=new int[e];
   for(int i=0;i<e;i++){to[i]=d.readInt();edgeDistance[i]=d.readFloat();edgeSpeed[i]=d.readFloat();nameId[i]=d.readInt();}
   names=new String[m];for(int i=0;i<m;i++){int len=d.readInt();if(len<0||len>65536)throw new IOException("Invalid road name");byte[] b=new byte[len];d.readFully(b);names[i]=new String(b,java.nio.charset.StandardCharsets.UTF_8);}
  }
  ready=true;
 }
 private static double rad(double v){return v*Math.PI/180.0;}
 private static float hav(double a,double o,double b,double p){double x=rad(p-o)*Math.cos(rad((a+b)/2)),y=rad(b-a);return (float)(6371000*Math.sqrt(x*x+y*y));}
 private int nearest(double a,double o){
  int best=-1;double best2=Double.POSITIVE_INFINITY;double ca=Math.cos(rad(a));
  for(int i=0;i<lat.length;i++){double y=lat[i]-a,x=(lon[i]-o)*ca,v=x*x+y*y;if(v<best2){best2=v;best=i;}}
  return best;
 }
 private static final class Q implements Comparable<Q>{int i;double f;Q(int i,double f){this.i=i;this.f=f;}public int compareTo(Q q){return Double.compare(f,q.f);}}
 public Route route(double a,double o,double b,double p)throws IOException{
  if(!ready)throw new IOException("Routing data is still loading");
  int start=nearest(a,o),goal=nearest(b,p);if(start<0||goal<0)throw new IOException("No road nearby");
  float snapA=hav(a,o,lat[start],lon[start]),snapB=hav(b,p,lat[goal],lon[goal]);
  if(snapA>2500||snapB>2500)throw new IOException("Destination is outside this offline road region");
  int n=lat.length;double[] g=new double[n];Arrays.fill(g,Double.POSITIVE_INFINITY);int[] prev=new int[n],prevEdge=new int[n];Arrays.fill(prev,-1);Arrays.fill(prevEdge,-1);boolean[] closed=new boolean[n];
  PriorityQueue<Q> q=new PriorityQueue<>();g[start]=0;q.add(new Q(start,hav(lat[start],lon[start],lat[goal],lon[goal])/30.0));
  int expanded=0;
  while(!q.isEmpty()){
   Q cur=q.poll();int u=cur.i;if(closed[u])continue;closed[u]=true;if(u==goal)break;if(++expanded>900000)throw new IOException("Route is too complex for this region");
   int end=first[u]+count[u];for(int ei=first[u];ei<end;ei++){int v=to[ei];if(v<0||v>=n||closed[v])continue;double sec=edgeDistance[ei]/Math.max(2.0,edgeSpeed[ei]/3.6);double ng=g[u]+sec;if(ng<g[v]){g[v]=ng;prev[v]=u;prevEdge[v]=ei;double h=hav(lat[v],lon[v],lat[goal],lon[goal])/35.0;q.add(new Q(v,ng+h));}}
  }
  if(start!=goal&&prev[goal]<0)throw new IOException("No drivable offline route found");
  ArrayList<Integer> rev=new ArrayList<>();int x=goal;rev.add(x);while(x!=start){x=prev[x];if(x<0)break;rev.add(x);}Collections.reverse(rev);
  Route r=new Route();r.points.add(new double[]{a,o});for(int idx:rev)r.points.add(new double[]{lat[idx],lon[idx]});r.points.add(new double[]{b,p});
  float metres=snapA+snapB;double seconds=(snapA+snapB)/8.0;for(int k=1;k<rev.size();k++){int ei=prevEdge[rev.get(k)];metres+=edgeDistance[ei];seconds+=edgeDistance[ei]/Math.max(2.0,edgeSpeed[ei]/3.6);}r.distanceMeters=metres;r.etaSeconds=(int)Math.round(seconds);
  buildInstructions(r,rev,prevEdge);return r;
 }
 private void buildInstructions(Route r,ArrayList<Integer> path,int[] prevEdge){
  if(path.size()<2){r.instructions.add(new Instruction("Arrive",r.points.get(r.points.size()-1)[0],r.points.get(r.points.size()-1)[1],r.distanceMeters));return;}
  float cumulative=0;String lastName="";double lastBearing=Double.NaN;
  for(int k=1;k<path.size();k++){
   int u=path.get(k-1),v=path.get(k),ei=prevEdge[v];String road=(ei>=0&&nameId[ei]>=0&&nameId[ei]<names.length)?names[nameId[ei]]:"road";float d=ei>=0?edgeDistance[ei]:0;double bearing=bearing(lat[u],lon[u],lat[v],lon[v]);
   boolean changed=!road.equals(lastName)&&!road.equals("road");double delta=Double.isNaN(lastBearing)?0:angle(bearing-lastBearing);
   if(k==1){r.instructions.add(new Instruction("Head onto "+road,lat[u],lon[u],cumulative));}
   else if(changed||Math.abs(delta)>42){String turn=Math.abs(delta)<25?"Continue":(delta>0?(Math.abs(delta)>135?"Make a U-turn":"Turn right"):(Math.abs(delta)>135?"Make a U-turn":"Turn left"));String suffix=road.equals("road")?"":" onto "+road;r.instructions.add(new Instruction(turn+suffix,lat[u],lon[u],cumulative));}
   cumulative+=d;lastName=road;lastBearing=bearing;
  }
  double[] end=r.points.get(r.points.size()-1);r.instructions.add(new Instruction("Arrive at destination",end[0],end[1],r.distanceMeters));
 }
 private static double bearing(double a,double o,double b,double p){double y=Math.sin(rad(p-o))*Math.cos(rad(b));double x=Math.cos(rad(a))*Math.sin(rad(b))-Math.sin(rad(a))*Math.cos(rad(b))*Math.cos(rad(p-o));return (Math.toDegrees(Math.atan2(y,x))+360)%360;}
 private static double angle(double x){return ((x+540)%360)-180;}
}

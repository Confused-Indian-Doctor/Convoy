package com.convoy.offline;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.Files;
public final class GpxTest {
 static int checks;
 static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static InputStream input(String xml){return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));}
 static GpxRoute parse(String xml)throws IOException{return GpxParser.parse(input(xml),"Fallback");}
 static void rejects(String xml,String name){try{parse(xml);throw new AssertionError(name);}catch(IOException expected){check(true,name);}}
 public static void main(String[] args)throws Exception{
 String points="<trkpt lat='52' lon='-3'/><trkpt lat='52.01' lon='-3'/>";
 String track="<gpx><trk><name>Test track</name><trkseg>"+points+"</trkseg></trk></gpx>";
 GpxRoute route=parse(track);check(route.name.equals("Test track"),"named track");check(route.totalMeters>1100&&route.totalMeters<1120,"track length");
 GpxRoute rte=parse("<gpx><rte><name>Route</name><rtept lat='52' lon='-3'/><rtept lat='52.01' lon='-3'/></rte></gpx>");check(rte.name.equals("Route")&&rte.pointCount==2,"route points");
 GpxRoute segments=parse("<gpx><trk><trkseg>"+points+"</trkseg><trkseg><trkpt lat='55' lon='-2'/><trkpt lat='55.01' lon='-2'/></trkseg></trk></gpx>");check(segments.segments.size()==2&&segments.totalMeters<2250,"segments never gain connecting edges");
 GpxRoute waypoints=parse("<gpx><wpt lat='52.005' lon='-3'><name>Fuel</name><desc>Meeting</desc></wpt><trk><trkseg>"+points+"</trkseg></trk></gpx>");check(waypoints.waypoints.size()==1&&waypoints.waypoints.get(0).name.equals("Fuel"),"named waypoints");
 check(route.progress(52.005,-3,Double.NaN).percent>49&&route.progress(52.005,-3,Double.NaN).percent<51,"offline midpoint progress");check(route.progress(52.005,-3.01,Double.NaN).offRouteMeters>600,"off-route distance");
 rejects("<gpx/>","empty GPX");rejects("<gpx><trk>","malformed XML");rejects(track.replace("lat='52'","lat='NaN'"),"NaN rejected");rejects(track.replace("lat='52'","lat='91'"),"invalid latitude");rejects(track.replace("lat='52'",""),"missing coordinate");rejects("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+track,"DTD rejected");
 try{GpxParser.parse(new InputStream(){int remaining=GpxParser.MAX_BYTES+1;public int read(){return remaining-->0?' ': -1;}public int read(byte[] b,int off,int len){if(remaining<=0)return -1;int n=Math.min(len,remaining);java.util.Arrays.fill(b,off,off+n,(byte)' ');remaining-=n;return n;}});throw new AssertionError();}catch(IOException expected){check(true,"oversize file rejected");}
 File root=Files.createTempDirectory("convoy-gpx-test").toFile();RouteLibrary library=new RouteLibrary(root);RouteLibrary.Entry first=library.importRoute(input(track),"file.gpx");RouteLibrary.Entry second=library.importRoute(input(track),"other.gpx");check(library.list().size()==2,"multiple routes persisted");library.setActive(first.id,true);check(new RouteLibrary(root).isFollowing()&&new RouteLibrary(root).activeId().equals(first.id),"active route survives restart");library.rename(first.id,"Renamed");check(library.load(first.id).name.equals("Renamed"),"rename persists without changing original");try{library.importRoute(input("broken"),"bad");throw new AssertionError();}catch(IOException expected){check(library.list().size()==2,"failed import preserves library");}library.delete(first.id);check(library.list().size()==1&&library.activeId()==null,"active deletion clears selection");check(library.load(second.id).pointCount==2,"deletion preserves other route");
 System.out.println("TOTAL "+checks+" GPX checks passed");
 }
}

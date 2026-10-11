package com.convoy.offline;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.util.*;

/** Imports and persists a GPX track/route for offline display. */
public final class GpxRoute {
 public static final class Point {
  public final double lat,lon;
  Point(double lat,double lon){this.lat=lat;this.lon=lon;}
 }
 public static final class Route {
  public final String name;
  public final ArrayList<Point> points;
  Route(String name,ArrayList<Point> points){this.name=name;this.points=points;}
 }
 private static final String FILE="active-route.gpx";
 private GpxRoute(){}

 public static Route load(Context context){
  File file=new File(context.getFilesDir(),FILE);
  if(!file.isFile())return null;
  try(InputStream input=new BufferedInputStream(new FileInputStream(file))){return parse(input);}
  catch(Exception ignored){return null;}
 }

 public static Route importFrom(Context context,Uri uri)throws IOException{
  File temp=new File(context.getFilesDir(),FILE+".import");
  try(InputStream input=context.getContentResolver().openInputStream(uri);
      OutputStream output=new BufferedOutputStream(new FileOutputStream(temp))){
   if(input==null)throw new IOException("Could not read that GPX file");
   byte[] buffer=new byte[65536];long total=0;int n;
   while((n=input.read(buffer))!=-1){
    total+=n;
    if(total>64L*1024*1024)throw new IOException("GPX file is unexpectedly large");
    output.write(buffer,0,n);
   }
  }catch(Exception e){temp.delete();throw e instanceof IOException?(IOException)e:new IOException(e);}
  Route route;
  try(InputStream input=new BufferedInputStream(new FileInputStream(temp))){route=parse(input);}
  catch(Exception e){temp.delete();throw e instanceof IOException?(IOException)e:new IOException("Could not parse GPX",e);}
  if(route==null||route.points.size()<2){temp.delete();throw new IOException("No usable GPX track or route points were found");}
  File target=new File(context.getFilesDir(),FILE);
  try{
   java.nio.file.Files.move(temp.toPath(),target.toPath(),
     java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }catch(java.nio.file.AtomicMoveNotSupportedException e){
   java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }
  return route;
 }

 public static void clear(Context context){new File(context.getFilesDir(),FILE).delete();}

 public static Route parse(InputStream input)throws IOException{
  ArrayList<Point> track=new ArrayList<>(),route=new ArrayList<>(),waypoints=new ArrayList<>();
  String name="Imported GPX route";
  try{
   XmlPullParser parser=Xml.newPullParser();
   parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
   parser.setInput(input,"UTF-8");
   int event=parser.getEventType();
   String parent="";
   while(event!=XmlPullParser.END_DOCUMENT){
    if(event==XmlPullParser.START_TAG){
     String tag=parser.getName();
     if("trk".equals(tag)||"rte".equals(tag)||"wpt".equals(tag))parent=tag;
     if("trkpt".equals(tag)||"rtept".equals(tag)||"wpt".equals(tag)){
      String la=parser.getAttributeValue(null,"lat"),lo=parser.getAttributeValue(null,"lon");
      try{
       double lat=Double.parseDouble(la),lon=Double.parseDouble(lo);
       if(Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=-90&&lat<=90&&lon>=-180&&lon<=180){
        Point point=new Point(lat,lon);
        if("trkpt".equals(tag))track.add(point); else if("rtept".equals(tag))route.add(point); else waypoints.add(point);
       }
      }catch(Exception ignored){}
     }else if("name".equals(tag)&&("trk".equals(parent)||"rte".equals(parent))){
      String value=parser.nextText();
      if(value!=null&&!value.trim().isEmpty())name=value.trim();
     }
    }else if(event==XmlPullParser.END_TAG){
     String tag=parser.getName();
     if(tag.equals(parent))parent="";
    }
    event=parser.next();
   }
  }catch(Exception e){throw new IOException("Invalid GPX: "+e.getMessage(),e);}
  ArrayList<Point> points=!track.isEmpty()?track:!route.isEmpty()?route:waypoints;
  if(points.size()<2)throw new IOException("GPX needs at least two track or route points");
  return new Route(name,points);
 }
}

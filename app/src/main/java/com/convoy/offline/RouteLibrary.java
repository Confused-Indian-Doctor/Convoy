package com.convoy.offline;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Private, atomic GPX originals and metadata. No route point becomes a convoy stop. */
public final class RouteLibrary {
 private final File root, directory;
 public static final class Entry {
  public final String id,name; public final double totalMeters; public final int segmentCount,pointCount,waypointCount;
  Entry(String id,String name,double meters,int segments,int points,int waypoints){this.id=id;this.name=name;totalMeters=meters;segmentCount=segments;pointCount=points;waypointCount=waypoints;}
 }
 public RouteLibrary(File filesRoot){root=filesRoot;directory=new File(root,"routes");}
 private File file(String id,String suffix)throws IOException{if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IOException("Invalid route identifier");return new File(directory,id+suffix);}
 private void ensure()throws IOException{if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Route storage unavailable");}
 private static Properties read(File file)throws IOException{Properties p=new Properties();if(file.isFile())try(InputStream in=new FileInputStream(file)){p.load(in);}return p;}
 private static void write(File file,Properties p)throws IOException{File temporary=new File(file.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(temporary)){p.store(out,"Convoy route metadata");out.getFD().sync();}try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}}
 private Entry entry(String id)throws IOException{Properties p=read(file(id,".properties"));try{return new Entry(id,p.getProperty("name","Imported GPX route"),Double.parseDouble(p.getProperty("meters","0")),Integer.parseInt(p.getProperty("segments","0")),Integer.parseInt(p.getProperty("points","0")),Integer.parseInt(p.getProperty("waypoints","0")));}catch(RuntimeException e){throw new IOException("Corrupt route metadata",e);}}
 public synchronized Entry importRoute(InputStream input,String fallback)throws IOException{
  ensure();byte[] bytes=GpxParser.readBounded(input);GpxRoute route=GpxParser.parse(new ByteArrayInputStream(bytes),fallback);String id=UUID.randomUUID().toString();File original=file(id,".gpx"),temp=file(id,".gpx.tmp");
  try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}Files.move(temp.toPath(),original.toPath(),StandardCopyOption.ATOMIC_MOVE);Properties p=new Properties();p.setProperty("name",route.name);p.setProperty("meters",Double.toString(route.totalMeters));p.setProperty("segments",Integer.toString(route.segments.size()));p.setProperty("points",Integer.toString(route.pointCount));p.setProperty("waypoints",Integer.toString(route.waypoints.size()));write(file(id,".properties"),p);return entry(id);}catch(IOException e){temp.delete();original.delete();file(id,".properties").delete();throw e;}
 }
 public synchronized List<Entry> list()throws IOException{
  ensure();migrate();List<Entry> entries=new ArrayList<>();File[] files=directory.listFiles();if(files==null)throw new IOException("Route storage unavailable");for(File f:files)if(f.getName().matches("[0-9a-f-]{36}\\.properties")){String id=f.getName().substring(0,36);if(file(id,".gpx").isFile())try{entries.add(entry(id));}catch(IOException ignored){}}entries.sort(Comparator.comparing(e->e.name.toLowerCase(Locale.ROOT)));return entries;
 }
 private void migrate()throws IOException{File old=new File(root,"active-route.gpx");if(!old.isFile())return;File marker=new File(directory,"legacy-migrated");if(marker.exists())return;Entry imported;try(InputStream in=new FileInputStream(old)){imported=importRoute(in,"Imported GPX route");}setActive(imported.id,false);Properties p=new Properties();p.setProperty("id",imported.id);write(marker,p);}
 public synchronized GpxRoute load(String id)throws IOException{Entry e=entry(id);try(InputStream in=new FileInputStream(file(id,".gpx"))){GpxRoute route=GpxParser.parse(in,e.name);return new GpxRoute(e.name,route.segments,route.waypoints);}}
 public synchronized void rename(String id,String name)throws IOException{if(name==null||name.trim().isEmpty()||name.length()>120)throw new IOException("Enter a name of 1–120 characters");Properties p=read(file(id,".properties"));if(!file(id,".gpx").isFile())throw new IOException("Route no longer exists");p.setProperty("name",name.trim());write(file(id,".properties"),p);}
 public synchronized void delete(String id)throws IOException{File original=file(id,".gpx");if(id.equals(activeId()))setActive(null,false);if(original.exists()&&!original.delete())throw new IOException("Could not delete route");File metadata=file(id,".properties");if(metadata.exists()&&!metadata.delete())throw new IOException("Could not delete route metadata");}
 public synchronized void setActive(String id,boolean follow)throws IOException{ensure();if(id!=null&&!file(id,".gpx").isFile())throw new IOException("Route no longer exists");Properties p=new Properties();if(id!=null)p.setProperty("id",id);p.setProperty("following",Boolean.toString(id!=null&&follow));write(new File(directory,"active.properties"),p);}
 public synchronized String activeId()throws IOException{return read(new File(directory,"active.properties")).getProperty("id");}
 public synchronized boolean isFollowing()throws IOException{return Boolean.parseBoolean(read(new File(directory,"active.properties")).getProperty("following","false"));}
 public synchronized GpxRoute activeRoute()throws IOException{String id=activeId();return id==null?null:load(id);}
 public synchronized File originalFile(String id)throws IOException{return file(id,".gpx");}
}

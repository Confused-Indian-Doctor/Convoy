package com.convoy.offline;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;

/** Read-only local place index generated for the bundled vector map region. */
public final class PlaceIndex {
 private final Context context; private SQLiteDatabase db;
 public PlaceIndex(Context c){context=c.getApplicationContext();}
 public synchronized void open()throws IOException{
  if(db!=null&&db.isOpen())return;
  File target=new File(context.getFilesDir(),"places-v2.db");
  if(!target.exists()){
   File temporary=File.createTempFile("places-", ".db",context.getFilesDir());
   try{try(InputStream in=context.getAssets().open("places.db");OutputStream out=new FileOutputStream(temporary)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    if(!temporary.renameTo(target))throw new IOException("Could not install offline place index");
   }finally{if(temporary.exists())temporary.delete();}
  }
  db=SQLiteDatabase.openDatabase(target.getPath(),null,SQLiteDatabase.OPEN_READONLY);
 }
 public synchronized void close(){if(db!=null){db.close();db=null;}}
 private static String val(Cursor c,int i){String s=c.isNull(i)?"":c.getString(i);return s==null?"":s;}
 public synchronized ArrayList<PlaceInfo> search(String query,double nearLat,double nearLon)throws IOException{
  open();ArrayList<PlaceInfo> out=new ArrayList<>();String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);if(q.isEmpty())return out;
  String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
  String sql="SELECT name,category,lat,lon,address,phone,website,opening_hours,brand,cuisine,operator,wheelchair,internet_access FROM places WHERE (name_lc LIKE ? ESCAPE '\\' OR category_lc LIKE ? ESCAPE '\\' OR brand_lc LIKE ? ESCAPE '\\')";
  ArrayList<String> args=new ArrayList<>(Arrays.asList(like,like,like));
  // Rank the whole matching set before limiting it; the old LIMIT 160 could discard
  // every nearby match simply because it was inserted later into the database.
  if(Double.isFinite(nearLat)&&Double.isFinite(nearLon)){
   sql+=" ORDER BY (lat-?)*(lat-?)+(lon-?)*(lon-?)*? LIMIT 30";double cos=Math.cos(Math.toRadians(nearLat));
   args.add(Double.toString(nearLat));args.add(Double.toString(nearLat));args.add(Double.toString(nearLon));args.add(Double.toString(nearLon));args.add(Double.toString(cos*cos));
  }else{sql+=" ORDER BY CASE WHEN name_lc=? THEN 0 ELSE 1 END,name_lc LIMIT 30";args.add(q);}
  try(Cursor c=db.rawQuery(sql,args.toArray(new String[0]))){while(c.moveToNext()){PlaceInfo p=new PlaceInfo(val(c,0),val(c,1),c.getDouble(2),c.getDouble(3));p.address=val(c,4);p.phone=val(c,5);p.website=val(c,6);p.openingHours=val(c,7);p.brand=val(c,8);p.cuisine=val(c,9);p.operator=val(c,10);p.wheelchair=val(c,11);p.internetAccess=val(c,12);if(p.name.isEmpty())p.name=p.brand.isEmpty()?p.category:p.brand;p.source="OpenStreetMap";out.add(p);}}
  if(Double.isFinite(nearLat)&&Double.isFinite(nearLon))out.sort(Comparator.comparingDouble(p->distance2(nearLat,nearLon,p.lat,p.lon)));
  if(out.size()>30)return new ArrayList<>(out.subList(0,30));return out;
 }
 public synchronized PlaceInfo nearest(double lat,double lon,String nameHint)throws IOException{
  open();double span=.002;PlaceInfo best=null;double bestD=Double.POSITIVE_INFINITY;String hint=normalName(nameHint);
  try(Cursor c=db.rawQuery("SELECT name,category,lat,lon,address,phone,website,opening_hours,brand,cuisine,operator,wheelchair,internet_access FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",new String[]{Double.toString(lat-span),Double.toString(lat+span),Double.toString(lon-span),Double.toString(lon+span)})){while(c.moveToNext()){String name=val(c,0),candidate=normalName(name);if(!hint.isEmpty()&&!candidate.equals(hint)&&!(Math.min(hint.length(),candidate.length())>=4&&(candidate.contains(hint)||hint.contains(candidate))))continue;double d=distance2(lat,lon,c.getDouble(2),c.getDouble(3));if(d<bestD){bestD=d;PlaceInfo p=new PlaceInfo(name,val(c,1),c.getDouble(2),c.getDouble(3));p.address=val(c,4);p.phone=val(c,5);p.website=val(c,6);p.openingHours=val(c,7);p.brand=val(c,8);p.cuisine=val(c,9);p.operator=val(c,10);p.wheelchair=val(c,11);p.internetAccess=val(c,12);p.source="OpenStreetMap";best=p;}}}
  double meters=Math.sqrt(bestD)*Math.PI/180*6371000;return meters<(hint.isEmpty()?25:100)?best:null;
 }
 private static String normalName(String name){return name==null?"":java.text.Normalizer.normalize(name.toLowerCase(Locale.ROOT),java.text.Normalizer.Form.NFD).replaceAll("[^\\p{L}\\p{N}]","");}
 private static double distance2(double a,double o,double b,double p){double x=(p-o)*Math.cos(Math.toRadians((a+b)/2)),y=b-a;return x*x+y*y;}
}

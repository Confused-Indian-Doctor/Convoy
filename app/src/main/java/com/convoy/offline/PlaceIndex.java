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
  if(!target.exists())try(InputStream in=context.getAssets().open("places.db");OutputStream out=new FileOutputStream(target)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
  db=SQLiteDatabase.openDatabase(target.getPath(),null,SQLiteDatabase.OPEN_READONLY);
 }
 public synchronized void close(){if(db!=null){db.close();db=null;}}
 private static String val(Cursor c,int i){String s=c.isNull(i)?"":c.getString(i);return s==null?"":s;}
 public synchronized ArrayList<PlaceInfo> search(String query,double nearLat,double nearLon)throws IOException{
  open();ArrayList<PlaceInfo> out=new ArrayList<>();String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);if(q.isEmpty())return out;
  String like="%"+q.replace("%","\\%").replace("_","\\_")+"%";
  String sql="SELECT name,category,lat,lon,address,phone,website,opening_hours,brand,cuisine,operator,wheelchair,internet_access FROM places WHERE name_lc LIKE ? ESCAPE '\\' OR category_lc LIKE ? ESCAPE '\\' OR brand_lc LIKE ? ESCAPE '\\' LIMIT 160";
  try(Cursor c=db.rawQuery(sql,new String[]{like,like,like})){while(c.moveToNext()){PlaceInfo p=new PlaceInfo(val(c,0),val(c,1),c.getDouble(2),c.getDouble(3));p.address=val(c,4);p.phone=val(c,5);p.website=val(c,6);p.openingHours=val(c,7);p.brand=val(c,8);p.cuisine=val(c,9);p.operator=val(c,10);p.wheelchair=val(c,11);p.internetAccess=val(c,12);if(p.name.isEmpty())p.name=p.brand.isEmpty()?p.category:p.brand;p.source="OpenStreetMap";out.add(p);}}
  if(Double.isFinite(nearLat)&&Double.isFinite(nearLon))out.sort(Comparator.comparingDouble(p->distance2(nearLat,nearLon,p.lat,p.lon)));
  if(out.size()>30)return new ArrayList<>(out.subList(0,30));return out;
 }
 public synchronized PlaceInfo nearest(double lat,double lon,String nameHint)throws IOException{
  open();double span=.015;PlaceInfo best=null;double bestD=Double.POSITIVE_INFINITY;
  try(Cursor c=db.rawQuery("SELECT name,category,lat,lon,address,phone,website,opening_hours,brand,cuisine,operator,wheelchair,internet_access FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 700",new String[]{Double.toString(lat-span),Double.toString(lat+span),Double.toString(lon-span),Double.toString(lon+span)})){while(c.moveToNext()){String name=val(c,0);double d=distance2(lat,lon,c.getDouble(2),c.getDouble(3));if(nameHint!=null&&!nameHint.isEmpty()&&!name.toLowerCase(Locale.ROOT).contains(nameHint.toLowerCase(Locale.ROOT)))d*=1.8;if(d<bestD){bestD=d;PlaceInfo p=new PlaceInfo(name,val(c,1),c.getDouble(2),c.getDouble(3));p.address=val(c,4);p.phone=val(c,5);p.website=val(c,6);p.openingHours=val(c,7);p.brand=val(c,8);p.cuisine=val(c,9);p.operator=val(c,10);p.wheelchair=val(c,11);p.internetAccess=val(c,12);p.source="OpenStreetMap";best=p;}}}
  return bestD<0.00012?best:null;
 }
 private static double distance2(double a,double o,double b,double p){double x=(p-o)*Math.cos(Math.toRadians((a+b)/2)),y=b-a;return x*x+y*y;}
}

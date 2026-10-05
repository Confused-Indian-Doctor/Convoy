package com.convoy.offline;

public final class PlaceInfo {
 public String name="", category="", address="", phone="", website="", openingHours="", source="", brand="", cuisine="", operator="", wheelchair="", internetAccess="";
 public double lat, lon;
 public PlaceInfo() {}
 public PlaceInfo(String name,String category,double lat,double lon){this.name=name;this.category=category;this.lat=lat;this.lon=lon;}
 public String subtitle(){
  StringBuilder b=new StringBuilder();
  if(category!=null&&!category.isEmpty())b.append(category.replace('_',' '));
  if(brand!=null&&!brand.isEmpty()&&!brand.equalsIgnoreCase(name)){if(b.length()>0)b.append(" • ");b.append(brand);}
  if(address!=null&&!address.isEmpty()){if(b.length()>0)b.append(" • ");b.append(address);}
  return b.toString();
 }
}

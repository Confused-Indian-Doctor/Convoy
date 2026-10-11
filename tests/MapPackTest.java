package com.convoy.offline;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;
public final class MapPackTest {
 static int checks;static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 private static byte[] fixture(){
  byte[] data=new byte[136];ByteBuffer h=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
  System.arraycopy("PMTiles".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,data,0,7);data[7]=3;
  h.putLong(8,127).putLong(16,5).putLong(24,132).putLong(32,2).putLong(40,134).putLong(48,0).putLong(56,134).putLong(64,2);
  h.putLong(72,1).putLong(80,1).putLong(88,1);data[96]=1;data[97]=1;data[98]=1;data[99]=1;data[100]=0;data[101]=15;
  h.putInt(102,-1800000000).putInt(106,-850000000).putInt(110,1800000000).putInt(114,850000000);data[118]=0;
  data[127]=1;data[128]=0;data[129]=1;data[130]=2;data[131]=1;data[132]='{';data[133]='}';data[134]=26;data[135]=0;
  return data;
 }
 public static void main(String[] args)throws Exception{
 File root=Files.createTempDirectory("convoy-map-pack-tests").toFile();MapPackStore store=new MapPackStore(root);
 check(store.list().size()==6,"five UK regions plus development map");check(store.selected().bundled,"bundled fallback");
 MapPack imported=store.importPack(new ByteArrayInputStream(fixture()),"../../test.pmtiles","scotland");check(imported.installed&&imported.selected&&imported.bytes==136,"real import installs and selects validated map");check(imported.file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator),"document name cannot escape storage");
 check(new MapPackStore(root).selected().id.equals(imported.id),"selection persists");check(store.coverage(57,-4).mapAvailable&&!store.coverage(57,-4).routingAvailable,"basemap does not imply routing");
 File old=imported.file;try{store.replacePack(new ByteArrayInputStream(new byte[]{1}),"bad",imported.id);throw new AssertionError();}catch(IOException expected){check(old.isFile()&&store.selectedFile().equals(old),"failed replacement retains selected map");}
 MapPack replacement=store.replacePack(new ByteArrayInputStream(fixture()),"replacement.pmtiles",imported.id);check(replacement.file.isFile()&&!old.exists(),"successful replacement commits new generation");
 try{store.remove(MapPackCatalog.BUNDLED_ID);throw new AssertionError();}catch(IOException expected){check(true,"bundled map cannot be removed");}
 store.remove(imported.id);check(store.selected().bundled,"removing selected map restores bundled map");
 try{new MapPackStore(root,100).importPack(new ByteArrayInputStream(fixture()),"huge","custom");throw new AssertionError();}catch(IOException expected){check(store.selected().bundled,"oversize map preserves catalog");}
 Files.write(new File(root,"region.pmtiles").toPath(),fixture());store.migrateLegacy("old map");check(store.selected().installed&&!new File(root,"region.pmtiles").exists(),"legacy custom map migration");
 check(store.storageBytes()>=136,"storage reflects actual files");
 for(String path:args){PmtilesArchive.Info real=PmtilesArchive.validate(new File(path));check(real.bytes>136,"real bundled PMTiles validates: "+new File(path).getName());}System.out.println("TOTAL "+checks+" map-pack checks passed");
 }
}

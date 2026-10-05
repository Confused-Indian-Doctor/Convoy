import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public final class PmtilesValidationTest {
 private static int checks=0;
 private static final Path scratch;
 static {try{scratch=Files.createTempDirectory("convoy-pmtiles-tests");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
 private static byte[] fixture(){
  byte[] data=new byte[136];ByteBuffer h=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
  System.arraycopy("PMTiles".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,data,0,7);data[7]=3;
  h.putLong(8,127).putLong(16,5).putLong(24,132).putLong(32,2).putLong(40,134).putLong(48,0).putLong(56,134).putLong(64,2);
  h.putLong(72,1).putLong(80,1).putLong(88,1);data[96]=1;data[97]=1;data[98]=1;data[99]=1;data[100]=0;data[101]=15;
  h.putInt(102,-1800000000).putInt(106,-850000000).putInt(110,1800000000).putInt(114,850000000);data[118]=0;
  data[127]=1;data[128]=0;data[129]=1;data[130]=2;data[131]=1;data[132]='{';data[133]='}';data[134]=26;data[135]=0;
  return data;
 }
 private static void valid(String name,byte[] data)throws Exception{
  Path file=scratch.resolve(name+".pmtiles");Files.write(file,data);PmtilesHeaderHarness.validatePmtiles(file.toFile());checks++;
 }
 private static void invalid(String name,byte[] data)throws Exception{
  Path file=scratch.resolve(name+".pmtiles");Files.write(file,data);
  try{PmtilesHeaderHarness.validatePmtiles(file.toFile());throw new AssertionError("Accepted "+name);}catch(IOException expected){checks++;}
 }
 private static byte[] value64(int at,long value){byte[] f=fixture();ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).putLong(at,value);return f;}
 private static byte[] value32(int at,int value){byte[] f=fixture();ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).putInt(at,value);return f;}
 private static byte[] value8(int at,int value){byte[] f=fixture();f[at]=(byte)value;return f;}
 public static void main(String[] args)throws Exception{
  valid("valid-vector-v3",fixture());
  invalid("magic-only-truncation",Arrays.copyOf(fixture(),7));invalid("truncated-full-header",Arrays.copyOf(fixture(),126));
  invalid("truncated-tile-section",Arrays.copyOf(fixture(),135));invalid("wrong-magic",value8(0,'X'));invalid("version-two",value8(7,2));
  invalid("root-outside-file",value64(8,1000));invalid("section-over-header",value64(8,126));invalid("negative-offset",value64(8,-1));
  invalid("overflow-length",value64(16,Long.MAX_VALUE));invalid("overlapping-data",value64(24,127));invalid("empty-tiles",value64(64,0));
  invalid("entries-exceed-addressed",value64(80,2));invalid("zero-addressed",value64(72,0));invalid("contents-exceed-entries",value64(88,2));
  invalid("unknown-compression",value8(97,0));invalid("invalid-clustering",value8(96,2));invalid("raster-pack",value8(99,2));
  invalid("inverted-zoom",value8(100,16));invalid("oversized-zoom",value8(101,32));invalid("oversized-center-zoom",value8(118,32));
  invalid("inverted-bounds",value32(102,1800000001));invalid("bad-latitude",value32(114,900000001));invalid("bad-center",value32(119,1800000001));
  valid("no-leaf-section",value64(40,0));
  Path target=scratch.resolve("installed.pmtiles"),replacement=scratch.resolve("replacement.pmtiles");byte[] old="existing map".getBytes();
  Files.write(target,old);Files.write(replacement,fixture());PmtilesHeaderHarness.validatePmtiles(replacement.toFile());
  Files.move(replacement,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  if(!Arrays.equals(Files.readAllBytes(target),fixture())||Files.exists(replacement))throw new AssertionError("Atomic replacement failed");checks++;
  byte[] before=Files.readAllBytes(target);try{Files.move(scratch.resolve("missing.pmtiles"),target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);throw new AssertionError("Missing source was moved");}catch(NoSuchFileException expected){}
  if(!Arrays.equals(before,Files.readAllBytes(target)))throw new AssertionError("Existing pack changed on failure");checks++;
  System.out.println("PMTiles header/import checks passed: "+checks);
 }
}

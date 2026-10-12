package com.convoy.offline;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Testable bounded downloader. A partial archive can never become an installed pack. */
public final class MapDownloadEngine {
    public static final long FREE_RESERVE_BYTES=64L*1024*1024;
    public interface Cancellation { boolean cancelled(); }
    public interface Progress { void update(long bytes,long total,String phase); }
    public interface Transport { Response open(String url,long offset,String strongEtag)throws IOException; }
    public interface Space { long freeBytes(); }
    public static final class PermanentFailure extends IOException { public PermanentFailure(String message){super(message);} }
    public static final class Response implements Closeable {
        public final int status;public final long length,start,total;public final String etag,encoding;
        public final InputStream body;private final Runnable closer;
        public Response(int status,long length,long start,long total,String etag,String encoding,InputStream body,Runnable closer){
            this.status=status;this.length=length;this.start=start;this.total=total;this.etag=etag;this.encoding=encoding;this.body=body;this.closer=closer;
        }
        public void close()throws IOException{try{if(body!=null)body.close();}finally{if(closer!=null)closer.run();}}
    }
    private final Transport transport;
    public MapDownloadEngine(){this(new HttpTransport());}
    public MapDownloadEngine(Transport transport){this.transport=transport;}
    public File download(MapPackSource source,File directory,Space space,Cancellation cancellation,Progress progress)throws IOException{
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Could not create download storage");
        File part=new File(directory,source.regionId+"-"+source.sha256.substring(0,16)+".part");
        File[] previous=directory.listFiles();
        if(previous!=null)for(File file:previous)if(file.getName().matches(java.util.regex.Pattern.quote(source.regionId)+"-[a-f0-9]{16}\\.part(?:\\.properties(?:\\.tmp)?)?")&&!file.getName().startsWith(part.getName()))file.delete();
        File state=new File(part.getPath()+".properties");Properties saved=readState(state);
        String etag=saved.getProperty("etag","");
        if(!source.fingerprint().equals(saved.getProperty("source"))||!strongEtag(etag)||part.length()>source.bytes){part.delete();state.delete();etag="";}
        long offset=part.isFile()?part.length():0;long began=System.nanoTime();
        checkCancelled(cancellation);checkStorage(source.bytes,offset,space.freeBytes());
        if(offset<source.bytes){
            try(Response response=transport.open(source.downloadUrl,offset,etag)){
                if(response.status==408||response.status==429||response.status>=500)throw new IOException("Map server temporarily unavailable; download will retry");
                if(response.status!=200&&response.status!=206)throw new PermanentFailure("Map download unavailable (HTTP "+response.status+")");
                if(response.encoding!=null&&!response.encoding.isEmpty()&&!"identity".equalsIgnoreCase(response.encoding))throw new PermanentFailure("Map server changed the download encoding");
                if(response.status==206){
                    if(response.start!=offset||response.total!=source.bytes||response.length!=source.bytes-offset||
                            (offset>0&&(!strongEtag(response.etag)||!etag.equals(response.etag))))throw new PermanentFailure("Map resume response does not match the original file");
                }else{offset=0;if(response.length>=0&&response.length!=source.bytes)throw new PermanentFailure("Map download size does not match its manifest");checkStorage(source.bytes,0,space.freeBytes()+part.length());}
                etag=strongEtag(response.etag)?response.etag:"";
                writeState(state,source,etag);
                try(FileOutputStream output=new FileOutputStream(part,response.status==206&&offset>0)){
                    byte[] buffer=new byte[65536];int count;long copied=offset;long lastReported=0;
                    while((count=response.body.read(buffer))!=-1){checkCancelled(cancellation);if(System.nanoTime()-began>TimeUnit.MINUTES.toNanos(50))throw new IOException("Map download checkpoint; remaining bytes will retry");if(count==0)continue;
                        if(count>source.bytes-copied)throw new PermanentFailure("Map download exceeds its declared size");
                        output.write(buffer,0,count);copied+=count;
                        long now=System.nanoTime();if(progress!=null&&(now-lastReported>250000000L||copied==source.bytes)){progress.update(copied,source.bytes,"Downloading");lastReported=now;}
                    }
                    output.getFD().sync();
                    if(copied!=source.bytes)throw new IOException("Map download interrupted; retrying remaining bytes");
                }
            }catch(PermanentFailure permanent){part.delete();state.delete();throw permanent;}
        }
        checkCancelled(cancellation);if(progress!=null)progress.update(source.bytes,source.bytes,"Verifying checksum");
        if(!sha256(part,cancellation).equals(source.sha256)){part.delete();state.delete();throw new PermanentFailure("Map checksum mismatch; the existing installed map is unchanged");}
        checkCancelled(cancellation);if(progress!=null)progress.update(source.bytes,source.bytes,"Validating map");
        try{PmtilesArchive.Info info=PmtilesArchive.validate(part);MapPackStore.validateExpected(info,source);}
        catch(IOException invalid){part.delete();state.delete();throw new PermanentFailure("Downloaded map validation failed: "+invalid.getMessage());}
        checkCancelled(cancellation);state.delete();return part;
    }
    public static void discard(MapPackSource source,File directory){File part=new File(directory,source.regionId+"-"+source.sha256.substring(0,16)+".part");part.delete();new File(part.getPath()+".properties").delete();}
    public static void checkStorage(long expected,long downloaded,long free)throws IOException{
        if(expected<0||downloaded<0||downloaded>expected||free<expected-downloaded+FREE_RESERVE_BYTES)
            throw new PermanentFailure("Not enough storage. Free space for the new map plus 64 MiB is required; the current map will be kept.");
    }
    public static String sha256(File file,Cancellation cancellation)throws IOException{
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new BufferedInputStream(new FileInputStream(file))){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){checkCancelled(cancellation);digest.update(buffer,0,n);}}
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IOException("SHA-256 is unavailable",impossible);}
    }
    private static boolean strongEtag(String etag){return etag!=null&&etag.length()>=2&&etag.length()<=256&&etag.startsWith("\"")&&etag.endsWith("\"")&&!etag.contains("\r")&&!etag.contains("\n");}
    private static void checkCancelled(Cancellation cancellation)throws InterruptedIOException{if(Thread.currentThread().isInterrupted()||(cancellation!=null&&cancellation.cancelled()))throw new InterruptedIOException("Map download cancelled");}
    private static Properties readState(File file)throws IOException{Properties p=new Properties();if(!file.exists())return p;if(file.length()>4096){file.delete();return p;}try(InputStream in=new FileInputStream(file)){p.load(in);}catch(IllegalArgumentException corrupt){file.delete();}return p;}
    private static void writeState(File file,MapPackSource source,String etag)throws IOException{
        Properties p=new Properties();p.setProperty("source",source.fingerprint());p.setProperty("etag",etag);File temp=new File(file.getPath()+".tmp");
        try{try(FileOutputStream out=new FileOutputStream(temp)){p.store(out,"Resumable Convoy map download");out.getFD().sync();}Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{temp.delete();}
    }
    public static final class HttpTransport implements Transport {
        public Response open(String url,long offset,String etag)throws IOException{
            URL target=new URL(url);HttpURLConnection connection=null;
            for(int redirects=0;redirects<=5;redirects++){
                if(!"https".equalsIgnoreCase(target.getProtocol())||target.getUserInfo()!=null)throw new PermanentFailure("Map download requires HTTPS");
                connection=(HttpURLConnection)target.openConnection();connection.setConnectTimeout(20000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("User-Agent","Convoy-Offline-Maps/1");
                if(offset>0){connection.setRequestProperty("Range","bytes="+offset+"-");connection.setRequestProperty("If-Range",etag);}
                int status=connection.getResponseCode();if(status==301||status==302||status==303||status==307||status==308){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null||redirects==5)throw new IOException("Map download redirect failed");target=new URL(target,location);continue;}
                long length=connection.getContentLengthLong(),start=0,total=length;
                if(status==206){String range=connection.getHeaderField("Content-Range");
                    if(range==null||!range.matches("bytes [0-9]+-[0-9]+/[0-9]+")){connection.disconnect();throw new PermanentFailure("Invalid map resume range");}
                    try{String[] pieces=range.substring(6).split("[-/]");start=Long.parseLong(pieces[0]);long end=Long.parseLong(pieces[1]);total=Long.parseLong(pieces[2]);if(end<start||end-start+1!=length)throw new NumberFormatException();}
                    catch(NumberFormatException invalid){connection.disconnect();throw new PermanentFailure("Invalid map resume size");}
                }
                HttpURLConnection owned=connection;
                InputStream body=status==200||status==206?connection.getInputStream():null;
                return new Response(status,length,start,total,connection.getHeaderField("ETag"),connection.getHeaderField("Content-Encoding"),body,owned::disconnect);
            }
            throw new IOException("Map server redirect loop");
        }
    }
}

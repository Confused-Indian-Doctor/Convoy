package com.convoy.offline;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Production downloader exercised with deterministic transport failures rather than mocked decisions. */
public final class MapDownloadTest {
    static int checks;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static void rejects(Throwing action,String name)throws Exception{try{action.run();throw new AssertionError(name);}catch(IOException expected){check(true,name);}}
    interface Throwing{void run()throws Exception;}
    static byte[] fixture(MapPackCatalog.Bounds bounds){byte[] data=new byte[136];ByteBuffer h=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy("PMTiles".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,data,0,7);data[7]=3;
        h.putLong(8,127).putLong(16,5).putLong(24,132).putLong(32,2).putLong(40,134).putLong(48,0).putLong(56,134).putLong(64,2);
        h.putLong(72,1).putLong(80,1).putLong(88,1);data[96]=1;data[97]=1;data[98]=1;data[99]=1;data[101]=13;
        h.putInt(102,(int)Math.round(bounds.west*1e7)).putInt(106,(int)Math.round(bounds.south*1e7)).putInt(110,(int)Math.round(bounds.east*1e7)).putInt(114,(int)Math.round(bounds.north*1e7));
        data[127]=1;data[128]=0;data[129]=1;data[130]=2;data[131]=1;data[132]='{';data[133]='}';data[134]=26;data[135]=0;return data;
    }
    static String hash(byte[] bytes)throws Exception{StringBuilder b=new StringBuilder();for(byte n:MessageDigest.getInstance("SHA-256").digest(bytes))b.append(String.format("%02x",n&255));return b.toString();}
    static MapPackSource source(String region,String version,byte[] bytes)throws Exception{return new MapPackSource("uk-"+region,region,"uk",MapPackCatalog.region(region).name,version,"2026-10-11","https://example.test/"+region+".pmtiles",bytes.length,hash(bytes),0,13,MapPackCatalog.region(region).bounds,"OpenStreetMap / Protomaps",true,true,false,false);}
    static MapDownloadEngine.Response response(byte[] data,long offset){return new MapDownloadEngine.Response(offset==0?200:206,data.length-offset,offset,data.length,"\"stable-version\"","identity",new ByteArrayInputStream(data,(int)offset,data.length-(int)offset),null);}
    static File download(MapPackSource source,byte[] data,File directory)throws IOException{return new MapDownloadEngine((url,offset,etag)->response(data,offset)).download(source,directory,()->1024*1024*1024L,()->false,null);}
    static String manifest(String hash){return "{\"schema_version\":1,\"country_id\":\"uk\",\"packs\":[{\"id\":\"uk-scotland\",\"region_id\":\"scotland\",\"country_id\":\"uk\",\"name\":\"Scotland\",\"bounds\":[-8.8,54.6,-0.5,61.0],\"version\":\"v1\",\"data_date\":\"2026-10-11\",\"download_url\":\"https://example.test/scotland.pmtiles\",\"download_bytes\":136,\"sha256\":\""+hash+"\",\"min_zoom\":0,\"max_zoom\":13,\"attribution\":\"OpenStreetMap\",\"basemap\":true,\"pois\":true,\"search\":false,\"routing\":false}]}";}
    static MapPackManifest parse(String text)throws IOException{return MapPackManifest.parse(new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    public static void main(String[] args)throws Exception{
        byte[] scotland=fixture(MapPackCatalog.region("scotland").bounds);MapPackSource source=source("scotland","v1",scotland);
        MapPackManifest parsed=parse(manifest(source.sha256));check(parsed.forRegion("scotland").bytes==136,"manifest real size and region metadata");
        check(parsed.forRegion("uk-scotland").sha256.equals(source.sha256),"manifest hash and public pack ID");
        MapPackManifest oldCached=parse(manifest(source.sha256).replace("2026-10-11","2026-10-10").replace("\"v1\"","\"old-cache\""));
        check(MapPackManifest.preferNewest(parsed,oldCached).forRegion("scotland").version.equals("v1"),"newer bundled catalog is not hidden by old app cache");
        MapPackManifest newCached=parse(manifest(source.sha256).replace("2026-10-11","2026-10-12").replace("\"v1\"","\"new-cache\""));
        check(MapPackManifest.preferNewest(parsed,newCached).forRegion("scotland").version.equals("new-cache"),"newer cached catalog survives app upgrade");
        MapPackManifest corrected=parse(manifest(source.sha256).replace("{\"schema_version\"","{\"generated_at\":\"2026-10-12T10:00:00Z\",\"schema_version\"").replace("\"v1\"","\"correction\""));
        check(MapPackManifest.preferNewest(corrected,parsed).forRegion("scotland").version.equals("correction"),"same-data-date corrected bundled catalog wins by generation time");
        check(MapPackManifest.preferNewest(parsed,corrected).forRegion("scotland").version.equals("correction"),"same-data-date corrected cached catalog wins by generation time");
        rejects(()->parse(manifest(source.sha256).replace("schema_version\":1","schema_version\":2")),"unsupported manifest version");
        rejects(()->parse(manifest(source.sha256).replace("country_id\":\"uk","country_id\":\"in")),"UK-only manifest country");
        rejects(()->parse(manifest(source.sha256).replace("https://","http://")),"insecure download URL rejected");
        rejects(()->parse(manifest(source.sha256).replace(source.sha256,"bogus")),"missing strong checksum rejected");
        rejects(()->parse(manifest(source.sha256).replace("\"min_zoom\":0","\"min_zoom\":14")),"invalid zoom metadata rejected");
        rejects(()->parse(manifest(source.sha256).replace("\"schema_version\":1","\"schema_version\":1,\"schema_version\":1")),"duplicate JSON manifest fields rejected");
        rejects(()->parse("{\"schema_version\":1"),"malformed manifest rejected");
        for(Object[] location:new Object[][]{{57.48,-4.22,"scotland"},{55.95,-3.19,"scotland"},{54.98,-1.61,"north-england"},{54.89,-2.94,"north-england"},{55.77,-2.01,"north-england"},{54.995,-3.065,"scotland"},{53.48,-2.24,"north-england"},{51.51,-0.13,"south-england"},{51.45,-2.59,"south-england"},{51.48,-3.18,"wales"},{53.05,-3.00,"wales"},{53.19,-2.89,"north-england"},{51.62,-3.94,"wales"},{54.60,-5.93,"northern-ireland"}}){check(MapPackCatalog.at((double)location[0],(double)location[1]).id.equals(location[2]),"GPS region "+location[2]+" "+location[0]);}
        check(MapPackCatalog.at(28.6,77.2)==null,"non-UK position has no region");
        File root=Files.createTempDirectory("convoy-download-tests").toFile(),downloadDir=new File(root,"downloads");
        File complete=download(source,scotland,downloadDir);check(complete.length()==source.bytes,"complete real-byte download");
        MapPackStore store=new MapPackStore(root);MapPack installed=store.installDownloaded(complete,source);
        check(installed.official&&installed.version.equals("v1")&&installed.dataDate.equals("2026-10-11"),"verified immutable map metadata persists");
        check(store.selected().bundled,"download never unexpectedly changes the selected map");
        check(store.chooseAt(57.4,-4.2,12).id.equals("scotland"),"automatic map choice for current region");
        check(!installed.searchAvailable&&!installed.routingAvailable&&installed.poisAvailable,"rendered POIs do not imply search or driving graph");
        check(!source.updateAvailable(installed),"current manifest matches installed map");
        MapPackSource v2=source("scotland","v2",scotland);check(v2.updateAvailable(installed),"new version update available");
        MapPackSource older=new MapPackSource(source.id,source.regionId,source.countryId,source.name,"old","2026-10-10",source.downloadUrl,source.bytes,source.sha256,source.minZoom,source.maxZoom,source.bounds,source.attribution,true,true,false,false);
        check(!older.updateAvailable(installed),"older map catalog is not advertised as an update");
        File old=installed.file;
        File cancelledInstall=download(v2,scotland,downloadDir);
        rejects(()->store.installDownloaded(cancelledInstall,v2,()->true),"cancel during installation preserves catalog");
        check(old.isFile()&&store.coveringPacks(57.4,-4.2).get(0).version.equals("v1"),"cancelled update keeps prior installed version");
        byte[] corrupt=scotland.clone();corrupt[0]=0;
        rejects(()->download(v2,corrupt,downloadDir),"checksum mismatch rejected");check(old.isFile(),"failed download keeps previous installed map");
        byte[] noTiles=scotland.clone();ByteBuffer.wrap(noTiles).order(ByteOrder.LITTLE_ENDIAN).putLong(64,0);
        MapPackSource invalidTiles=source("scotland","v3",noTiles);
        rejects(()->download(invalidTiles,noTiles,downloadDir),"matching hash with invalid PMTiles rejected");check(old.isFile(),"failed PMTiles validation retains old map");
        byte[] wrongRegion=fixture(MapPackCatalog.region("wales").bounds);
        MapPackSource incorrectRegion=new MapPackSource("uk-scotland","scotland","uk","Scotland","v4","2026-10-11","https://example.test/scotland.pmtiles",wrongRegion.length,hash(wrongRegion),0,13,MapPackCatalog.region("wales").bounds,"OpenStreetMap",true,true,false,false);
        rejects(()->download(incorrectRegion,wrongRegion,downloadDir),"manifest identity cannot disguise a different UK region");
        rejects(()->new MapDownloadEngine((url,offset,etag)->new MapDownloadEngine.Response(503,0,0,0,"","",null,null)).download(v2,downloadDir,()->1000000000L,()->false,null),"temporary download failure is surfaced for retry");
        rejects(()->MapDownloadEngine.checkStorage(source.bytes,0,source.bytes+MapDownloadEngine.FREE_RESERVE_BYTES-1),"insufficient safe storage rejected");
        MapDownloadEngine.checkStorage(1000,900,MapDownloadEngine.FREE_RESERVE_BYTES+100);check(true,"storage check counts remaining bytes and existing-map free-space usage");
        File retryDir=new File(root,"retry");long[] resumed={-1};int[] calls={0};
        MapDownloadEngine flaky=new MapDownloadEngine((url,offset,etag)->{
            calls[0]++;resumed[0]=offset;if(calls[0]==1)return new MapDownloadEngine.Response(200,scotland.length,0,scotland.length,"\"stable-version\"","identity",new InputStream(){int i;public int read()throws IOException{if(i==50)throw new IOException("temporary outage");return scotland[i++]&255;}},null);
            check(etag.equals("\"stable-version\""),"retry uses strong If-Range ETag");return response(scotland,offset);
        });
        rejects(()->flaky.download(source,retryDir,()->1000000000L,()->false,null),"interrupted download preserves validated resume state");
        File retried=flaky.download(source,retryDir,()->1000000000L,()->false,null);check(resumed[0]==50&&retried.length()==scotland.length,"retry resumes actual partial bytes using HTTP Range");
        File cancelDir=new File(root,"cancel");boolean[] cancel={false};
        rejects(()->new MapDownloadEngine((url,offset,etag)->response(scotland,offset)).download(source,cancelDir,()->1000000000L,()->cancel[0],(bytes,total,phase)->cancel[0]=true),"cancelled transfer never installs");
        check(old.isFile(),"cancel keeps prior installed pack");
        check(download(source,scotland,cancelDir).length()==136,"cancelled partial can be safely retried");
        File badResume=new File(root,"bad-resume");
        MapDownloadEngine changed=new MapDownloadEngine((url,offset,etag)->new MapDownloadEngine.Response(206,scotland.length,1,scotland.length,"\"wrong\"","identity",new ByteArrayInputStream(scotland),null));
        rejects(()->changed.download(source,badResume,()->1000000000L,()->false,null),"invalid resume Content-Range rejected");
        File replacement=download(v2,scotland,downloadDir);MapPack newer=store.installDownloaded(replacement,v2);check(newer.version.equals("v2")&&old.isFile(),"update retains prior MapLibre source while committing new generation");
        store.pruneRevisions(newer);check(old.isFile(),"renderer acknowledgement retains five-minute revision grace");
        old.setLastModified(System.currentTimeMillis()-MapPackStore.REVISION_GRACE_MILLIS-1000);
        store.pruneRevisions(installed);check(old.isFile(),"obsolete renderer cannot reclaim the current generation");
        store.pruneRevisions(newer);check(!old.exists()&&newer.file.isFile(),"current renderer acknowledgement safely reclaims expired previous revision");
        byte[] wales=fixture(MapPackCatalog.region("wales").bounds);MapPackSource w=source("wales","v1",wales);store.installDownloaded(download(w,wales,downloadDir),w);
        check(store.coveringPacks(51.48,-3.18).stream().anyMatch(p->p.id.equals("wales")),"multiple installed regions independently cover their area");
        check(store.chooseAt(51.48,-3.18,12).id.equals("wales"),"automatic regional map switching");
        store.remove("scotland");check(!newer.file.exists()&&store.chooseAt(57.4,-4.2,12)==null,"downloaded map deletion clears coverage");
        check(store.storageStats().maps>0&&store.storageStats().free>0,"actual storage and free space reported");
        if(args.length>0){try(InputStream input=new FileInputStream(args[0])){MapPackManifest live=MapPackManifest.parse(input);check(live.packs.size()==6,"published five-region plus overview manifest parses");check(live.forRegion("scotland").bytes==178784492L,"published Scotland size is actual generated size");}}
        System.out.println("TOTAL "+checks+" map-download checks passed");
    }
}

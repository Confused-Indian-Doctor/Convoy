package com.convoy.offline;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.file.*;

/** Public catalog cache and storage facade; all I/O entry points are called off the Activity thread. */
public final class MapDownloads {
    public static final String MANIFEST_URL="https://github.com/Confused-Indian-Doctor/Convoy/releases/download/uk-maps-v1/uk-map-manifest.json";
    private MapDownloads(){}
    public static MapPackManifest loadManifest(Context context)throws IOException {
        File cached=new File(context.getFilesDir(),"maps/uk/download-manifest.json");
        if(cached.isFile())try(InputStream input=new FileInputStream(cached)){return MapPackManifest.parse(input);}catch(IOException corrupt){cached.delete();}
        try(InputStream input=context.getAssets().open("uk-map-manifest.json")){return MapPackManifest.parse(input);}
    }
    /** HTTPS catalog refresh is explicit; previously usable catalog remains on connection/validation failure. */
    public static MapPackManifest refreshManifest(Context context)throws IOException {
        URL target=new URL(MANIFEST_URL);byte[] bytes=null;
        for(int redirects=0;redirects<=5;redirects++){
            if(!"https".equals(target.getProtocol()))throw new IOException("Map catalog requires HTTPS");
            HttpURLConnection connection=(HttpURLConnection)target.openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("User-Agent","Convoy-Offline-Maps/1");
            try{int code=connection.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String location=connection.getHeaderField("Location");if(location==null||redirects==5)throw new IOException("Map catalog redirect failed");target=new URL(target,location);continue;}
                if(code!=200)throw new IOException("Map catalog is unavailable (HTTP "+code+")");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(n>MapPackManifest.MAX_BYTES-output.size())throw new IOException("Map catalog is too large");output.write(buffer,0,n);}bytes=output.toByteArray();}
                break;
            }finally{connection.disconnect();}
        }
        if(bytes==null)throw new IOException("Map catalog download failed");MapPackManifest manifest=MapPackManifest.parse(new ByteArrayInputStream(bytes));
        File directory=new File(context.getFilesDir(),"maps/uk");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Could not save map catalog");
        File temporary=File.createTempFile("download-manifest-",".tmp",directory);
        try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}Files.move(temporary.toPath(),new File(directory,"download-manifest.json").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{temporary.delete();}
        return manifest;
    }
    public static File downloadDirectory(Context context){return new File(context.getFilesDir(),"maps/uk/.downloads");}
}

package com.convoy.offline;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded versioned public catalog. Incomplete entries are never advertised as downloadable. */
public final class MapPackManifest {
    public static final int MAX_BYTES = 256 * 1024;
    public final List<MapPackSource> packs;
    public final int schemaVersion;
    private MapPackManifest(List<MapPackSource> packs) { this.schemaVersion=1; this.packs=Collections.unmodifiableList(packs); }
    public MapPackSource forRegion(String id) { for (MapPackSource source:packs) if(source.regionId.equals(id)||source.id.equals(id))return source;return null; }
    public static MapPackManifest parse(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(n>MAX_BYTES-output.size())throw new IOException("Map manifest is too large");output.write(buffer,0,n);}
        String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString();}
        catch(CharacterCodingException invalid){throw new IOException("Invalid map manifest encoding",invalid);}
        Map<String,Object> root=object(StrictJson.parse(text));
        if(number(root,"schema_version")!=1||!"uk".equals(string(root,"country_id")))throw new IOException("Unsupported map manifest version or country");
        Object entries=root.get("packs");if(!(entries instanceof List)||((List<?>)entries).size()>32)throw new IOException("Invalid map manifest packs");
        List<MapPackSource> packs=new ArrayList<>();Set<String> ids=new HashSet<>(),regions=new HashSet<>();
        for(Object entry:(List<?>)entries){Map<String,Object> p=object(entry);Object raw=p.get("bounds");if(!(raw instanceof List)||((List<?>)raw).size()!=4)throw new IOException("Invalid map region bounds");
            List<?> b=(List<?>)raw;MapPackCatalog.Bounds bounds;
            try{bounds=new MapPackCatalog.Bounds(coordinate(b.get(1)),coordinate(b.get(0)),coordinate(b.get(3)),coordinate(b.get(2)));}
            catch(IllegalArgumentException invalid){throw new IOException("Invalid map bounds",invalid);}
            MapPackSource source=new MapPackSource(string(p,"id"),string(p,"region_id"),string(p,"country_id"),string(p,"name"),string(p,"version"),
                    string(p,"data_date"),string(p,"download_url"),number(p,"download_bytes"),string(p,"sha256"),integer(p,"min_zoom"),integer(p,"max_zoom"),
                    bounds,string(p,"attribution"),bool(p,"basemap"),bool(p,"pois"),bool(p,"search"),bool(p,"routing"));
            if(!ids.add(source.id)||!regions.add(source.regionId))throw new IOException("Duplicate map pack region");packs.add(source);
        }
        return new MapPackManifest(packs);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object object)throws IOException{if(!(object instanceof Map))throw new IOException("Expected map manifest object");return(Map<String,Object>)object;}
    private static String string(Map<String,Object> object,String key)throws IOException{Object value=object.get(key);if(!(value instanceof String))throw new IOException("Missing map manifest "+key);return(String)value;}
    private static long number(Map<String,Object> object,String key)throws IOException{Object value=object.get(key);if(!(value instanceof Long))throw new IOException("Invalid map manifest "+key);return(Long)value;}
    private static int integer(Map<String,Object> object,String key)throws IOException{long value=number(object,key);if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IOException("Invalid map integer");return(int)value;}
    private static boolean bool(Map<String,Object> object,String key)throws IOException{Object value=object.get(key);if(!(value instanceof Boolean))throw new IOException("Missing map coverage flag "+key);return(Boolean)value;}
    private static double coordinate(Object value)throws IOException{if(!(value instanceof Number))throw new IOException("Invalid bounds coordinate");double result=((Number)value).doubleValue();if(!Double.isFinite(result))throw new IOException("Invalid bounds coordinate");return result;}
}

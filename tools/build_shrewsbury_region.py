#!/usr/bin/env python3
"""Build the Convoy Shrewsbury offline place index and compact car-routing graph.

The visual basemap/Overture PMTiles are extracted separately by the CI workflow.
This script fetches only the drivable OSM ways and named POIs needed at runtime.
"""
from __future__ import annotations
import gzip, json, math, os, re, sqlite3, struct, sys, time, urllib.parse, urllib.request
from collections import defaultdict
from pathlib import Path

SOUTH=52.418661
WEST=-3.231321
NORTH=52.996939
EAST=-2.276879
BBOX=f"{SOUTH},{WEST},{NORTH},{EAST}"
MAGIC=0x43564731
UA="Convoy-Android/0.4 offline-region-builder (OpenStreetMap data build)"
ENDPOINTS=[
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass-api.de/api/interpreter",
]

ROAD_QUERY=f'''[out:json][timeout:240];
way["highway"~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service)$"]({BBOX});
out tags geom;'''
PLACE_QUERY=f'''[out:json][timeout:240];
(
 nwr["amenity"]({BBOX});
 nwr["shop"]({BBOX});
 nwr["tourism"]({BBOX});
 nwr["leisure"]({BBOX});
 nwr["healthcare"]({BBOX});
 nwr["office"]({BBOX});
 nwr["craft"]({BBOX});
 nwr["historic"]({BBOX});
);
out center tags;'''

DEFAULT_KMH={
 "motorway":112,"motorway_link":64,"trunk":96,"trunk_link":56,"primary":80,"primary_link":48,
 "secondary":64,"secondary_link":40,"tertiary":56,"tertiary_link":40,"unclassified":48,
 "residential":32,"living_street":16,"service":24,
}

def request_overpass(query:str, cache:Path):
    if cache.exists():
        with gzip.open(cache,"rt",encoding="utf-8") as f: return json.load(f)
    data=urllib.parse.urlencode({"data":query}).encode()
    last=None
    for round_no in range(4):
        for endpoint in ENDPOINTS:
            try:
                req=urllib.request.Request(endpoint,data=data,headers={"User-Agent":UA,"Content-Type":"application/x-www-form-urlencoded"})
                with urllib.request.urlopen(req,timeout=300) as r:
                    raw=r.read()
                doc=json.loads(raw.decode("utf-8"))
                cache.parent.mkdir(parents=True,exist_ok=True)
                with gzip.open(cache,"wt",encoding="utf-8",compresslevel=6) as f:json.dump(doc,f,separators=(",",":"),ensure_ascii=False)
                return doc
            except Exception as e:
                last=e
                print(f"Overpass attempt failed: {endpoint}: {e}",file=sys.stderr)
                time.sleep(3+round_no*5)
    raise RuntimeError(f"Overpass failed: {last}")

def hav(lat1,lon1,lat2,lon2):
    r=6371000.0
    p1,p2=math.radians(lat1),math.radians(lat2)
    dp=math.radians(lat2-lat1); dl=math.radians(lon2-lon1)
    a=math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*r*math.asin(min(1,math.sqrt(a)))

def maxspeed_kmh(raw, default):
    if not raw:return float(default)
    s=str(raw).lower().strip()
    if s in {"walk","signals","none","variable","national"}:return float(default)
    # If multiple speeds exist, use the first numeric value.
    m=re.search(r"(\d+(?:\.\d+)?)",s)
    if not m:return float(default)
    v=float(m.group(1))
    if "mph" in s:v*=1.609344
    return max(8.0,min(130.0,v))

def clean_name(tags):
    return (tags.get("name") or tags.get("ref") or tags.get("highway") or "road").strip()[:180]

def build_graph(doc,outfile:Path):
    node_index={}; nodes=[]; raw_edges=[]
    names=[]; name_ids={}
    def node(osm_id,lat,lon):
        # Preserve OSM topology by node id. Coordinate-only merging can incorrectly
        # connect bridges/tunnels that cross at the same latitude/longitude.
        key=("id",int(osm_id)) if osm_id is not None else ("xy",round(float(lat),7),round(float(lon),7))
        idx=node_index.get(key)
        if idx is None:
            idx=len(nodes); node_index[key]=idx; nodes.append((float(lat),float(lon)))
        return idx
    def name_id(s):
        i=name_ids.get(s)
        if i is None:i=len(names);name_ids[s]=i;names.append(s)
        return i
    ways=0
    for el in doc.get("elements",[]):
        if el.get("type")!="way":continue
        t=el.get("tags",{}); h=t.get("highway","")
        geom=el.get("geometry") or []
        if h not in DEFAULT_KMH or len(geom)<2:continue
        access=(t.get("motor_vehicle") or t.get("motorcar") or t.get("access") or "").lower()
        if access in {"no","private"}:continue
        osm_nodes=el.get("nodes") or []
        nids=[node(osm_nodes[i] if i < len(osm_nodes) else None,g["lat"],g["lon"]) for i,g in enumerate(geom)]
        kmh=maxspeed_kmh(t.get("maxspeed"),DEFAULT_KMH[h]); nid=name_id(clean_name(t))
        one=(t.get("oneway") or "").lower(); junction=(t.get("junction") or "").lower()
        forward=one not in {"-1","reverse"}
        backward=one not in {"yes","1","true"} and junction!="roundabout"
        if one in {"-1","reverse"}:backward=True
        for j in range(1,len(nids)):
            u,v=nids[j-1],nids[j]; a=nodes[u]; b=nodes[v]; dist=hav(a[0],a[1],b[0],b[1])
            if dist<0.3:continue
            if forward:raw_edges.append((u,v,float(dist),float(kmh),nid))
            if backward:raw_edges.append((v,u,float(dist),float(kmh),nid))
        ways+=1
    adj=defaultdict(list)
    for u,v,d,s,n in raw_edges:adj[u].append((v,d,s,n))
    # Flatten in node order so Java can use first/count arrays.
    flat=[]; first=[]; count=[]
    for i in range(len(nodes)):
        first.append(len(flat)); es=adj.get(i,[]);count.append(len(es));flat.extend(es)
    outfile.parent.mkdir(parents=True,exist_ok=True)
    with open(outfile,"wb") as f:
        f.write(struct.pack(">iiii",MAGIC,len(nodes),len(flat),len(names)))
        for i,(la,lo) in enumerate(nodes):f.write(struct.pack(">ddii",la,lo,first[i],count[i]))
        for v,d,s,n in flat:f.write(struct.pack(">iffi",v,d,s,n))
        for name in names:
            b=name.encode("utf-8",errors="replace")[:65536]
            f.write(struct.pack(">i",len(b)));f.write(b)
    print(f"Routing graph: {ways} ways, {len(nodes)} nodes, {len(flat)} directed edges, {len(names)} road names, {outfile.stat().st_size/1e6:.1f} MB")

def addr(tags):
    free=tags.get("addr:full")
    if free:return free.strip()
    line=[]
    hn=tags.get("addr:housenumber",""); street=tags.get("addr:street","")
    if hn or street:line.append((hn+" "+street).strip())
    for k in ("addr:city","addr:town","addr:village","addr:postcode"):
        v=tags.get(k,"").strip()
        if v and v not in line:line.append(v)
    return ", ".join(line)

def category(tags):
    for k in ("amenity","shop","tourism","healthcare","leisure","historic","office","craft"):
        v=tags.get(k)
        if v and v!="yes":return v
    return "place"

def build_places(doc,outfile:Path):
    if outfile.exists():outfile.unlink()
    db=sqlite3.connect(outfile)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("CREATE TABLE places(name TEXT,name_lc TEXT,category TEXT,category_lc TEXT,lat REAL,lon REAL,address TEXT,phone TEXT,website TEXT,opening_hours TEXT,brand TEXT,brand_lc TEXT,cuisine TEXT,operator TEXT,wheelchair TEXT,internet_access TEXT)")
    rows=[];seen=set()
    for el in doc.get("elements",[]):
        t=el.get("tags",{}); name=(t.get("name") or t.get("brand") or "").strip()
        if not name:continue
        if el.get("type")=="node":la,lo=el.get("lat"),el.get("lon")
        else:
            c=el.get("center") or {};la,lo=c.get("lat"),c.get("lon")
        if la is None or lo is None:continue
        cat=category(t);brand=(t.get("brand") or "").strip()
        key=(round(float(la),6),round(float(lo),6),name.lower(),cat)
        if key in seen:continue
        seen.add(key)
        phone=(t.get("contact:phone") or t.get("phone") or "").strip()
        web=(t.get("contact:website") or t.get("website") or "").strip()
        opening=(t.get("opening_hours") or "").strip()
        cuisine=(t.get("cuisine") or "").strip(); operator=(t.get("operator") or "").strip(); wheelchair=(t.get("wheelchair") or "").strip(); internet=(t.get("internet_access") or "").strip()
        rows.append((name,name.lower(),cat,cat.lower(),float(la),float(lo),addr(t),phone,web,opening,brand,brand.lower(),cuisine,operator,wheelchair,internet))
    db.executemany("INSERT INTO places VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",rows)
    db.execute("CREATE INDEX places_name ON places(name_lc)")
    db.execute("CREATE INDEX places_cat ON places(category_lc)")
    db.execute("CREATE INDEX places_brand ON places(brand_lc)")
    db.execute("CREATE INDEX places_geo ON places(lat,lon)")
    db.commit();db.close()
    print(f"Place index: {len(rows)} named POIs, {outfile.stat().st_size/1e6:.1f} MB")

def main():
    out=Path(sys.argv[1] if len(sys.argv)>1 else "app/src/main/assets").resolve();out.mkdir(parents=True,exist_ok=True)
    cache=Path(os.environ.get("CONVOY_REGION_CACHE",str(out.parent.parent.parent/"build"/"region-cache")))
    roads=request_overpass(ROAD_QUERY,cache/"roads.json.gz")
    places=request_overpass(PLACE_QUERY,cache/"places.json.gz")
    build_graph(roads,out/"route.graph")
    build_places(places,out/"places.db")

if __name__=="__main__":main()

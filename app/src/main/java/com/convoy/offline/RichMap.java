package com.convoy.offline;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.graphics.RectF;
import android.location.Location;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.*;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Vector, PMTiles-backed driving map with offline POIs, crew markers and local routing. */
public final class RichMap extends FrameLayout {
 public interface OnPlaceSelectedListener { void onPlace(PlaceInfo place); }
 public String attribution="© OpenStreetMap contributors · © Overture Maps Foundation";
 public String mapName="Shrewsbury rich offline vector map";
 private final MapView mapView; private MapLibreMap map; private Style style;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService workers=Executors.newSingleThreadExecutor();
 private final OfflineRouter router=new OfflineRouter(); private final PlaceIndex index;
 private ArrayList<TripPlan.Waypoint> waypoints=new ArrayList<>();
 private boolean navigationMode=true,follow=true,routeBusy=false,disposed=false;
 private volatile boolean routerLoading=true;
 private double zoom=15,lastDrivingBearing=0; private long lastRouteAt=0; private int routeGeneration=0,styleGeneration=0;
 private float progressMeters=Float.NaN;
 private OfflineRouter.Route route; private PlaceInfo manualTarget; private String routedTargetKey="";
 private OnPlaceSelectedListener placeListener;
 private volatile String instruction="",targetName=""; private volatile float distanceRemaining=-1,maneuverDistance=-1; private volatile int etaSeconds=-1;
 private static final String[] CAR_KEYS={"nc","gt86","r34","supra","nsx","gc8","evo6","ae86","z350","na","generic"};

 public RichMap(Context c){
  super(c); index=new PlaceIndex(c); MapLibre.getInstance(c.getApplicationContext()); mapView=new MapView(c); addView(mapView,new FrameLayout.LayoutParams(-1,-1));mapView.onCreate(null);
  mapView.getMapAsync(m->{if(disposed)return;map=m;m.getUiSettings().setLogoEnabled(false);m.getUiSettings().setAttributionEnabled(false);m.getUiSettings().setCompassEnabled(true);m.setMinZoomPreference(8);m.setMaxZoomPreference(18);m.addOnCameraMoveStartedListener(reason->{if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)follow=false;});m.addOnCameraIdleListener(()->{if(!follow)zoom=map.getCameraPosition().zoom;});m.addOnMapClickListener(this::tap);load();});
  workers.execute(()->{try{router.load(c.getApplicationContext().getAssets().open("route.graph"));}catch(Exception ignored){}finally{routerLoading=false;}main.post(()->{if(!disposed)refresh();});});
 }
 public void setOnPlaceSelectedListener(OnPlaceSelectedListener l){placeListener=l;}
 public boolean isNavigationMode(){return navigationMode;}
 public void setNavigationMode(boolean enabled){navigationMode=enabled;follow=true;refreshCamera(true);}
 public void recenter(){follow=true;refreshCamera(true);}
 public void changeZoom(int delta){zoom=Math.max(8,Math.min(18,zoom+delta));if(map!=null)map.animateCamera(CameraUpdateFactory.zoomTo(zoom),250);}
 public void focus(double lat,double lon){follow=false;if(map!=null)map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(lat,lon)).zoom(Math.max(15,zoom)).tilt(0).bearing(0).build()),450);}
 public void setWaypoints(List<TripPlan.Waypoint> p){waypoints=new ArrayList<>(p);if(manualTarget==null)invalidateRouteIfTargetChanged();updateDynamic();}
 public void navigateTo(PlaceInfo p){manualTarget=p;invalidateRoute();targetName=p.name;follow=true;refresh();}
 public void clearManualTarget(){manualTarget=null;invalidateRoute();refresh();}
 public boolean hasManualTarget(){return manualTarget!=null;}
 public String getInstruction(){return instruction;}
 public String getTargetName(){return targetName;}
 public float getDistanceRemaining(){return distanceRemaining;}
 public float getManeuverDistance(){return maneuverDistance;}
 public int getEtaSeconds(){return etaSeconds;}
 public boolean routingReady(){return router.isReady();}
 public void onStart(){mapView.onStart();} public void onResume(){mapView.onResume();} public void onPause(){mapView.onPause();} public void onStop(){mapView.onStop();} public void onLowMemory(){mapView.onLowMemory();}
 public void onSaveInstanceState(Bundle b){mapView.onSaveInstanceState(b);}
 public void dispose(){disposed=true;routeGeneration++;styleGeneration++;workers.shutdownNow();main.removeCallbacksAndMessages(null);index.close();mapView.onDestroy();}

 public void load(){
  if(map==null||disposed)return;
  final int generation=++styleGeneration;
  final Context context=getContext().getApplicationContext();
  final File imported=new File(context.getFilesDir(),"region.pmtiles");
  final boolean useImported=imported.isFile();
  mapName=useImported?context.getSharedPreferences("convoy",0).getString("mapName","Imported vector region"):"Shrewsbury rich offline vector map";
  workers.execute(()->{try{
   File apk=new File(context.getApplicationInfo().sourceDir),directory=new File(context.getFilesDir(),"bundled-vector-maps");
   File basemap=useImported?imported:LocalTiles.extractAsset(apk,directory,"shrewsbury.pmtiles");
   File places=LocalTiles.extractAsset(apk,directory,"overture-shrewsbury.pmtiles");
   String json=readAsset("convoy-style.json")
     .replace("__BASEMAP_URI__",escapeJson("pmtiles://"+android.net.Uri.fromFile(basemap)))
     .replace("__PLACES_URI__",escapeJson("pmtiles://"+android.net.Uri.fromFile(places)));
   main.post(()->{if(disposed||generation!=styleGeneration)return;style=null;map.setStyle(new Style.Builder().fromJson(json),s->{if(disposed||generation!=styleGeneration)return;style=s;addCarImages(s);updateDynamic();map.setCameraPosition(new CameraPosition.Builder().target(new LatLng(52.7078,-2.7541)).zoom(13.3).build());refresh();});});
  }catch(Exception error){main.post(()->{if(disposed||generation!=styleGeneration)return;android.util.Log.e("ConvoyMap","Offline map preparation failed",error);Toast.makeText(getContext(),"Vector map: "+error.getMessage(),Toast.LENGTH_LONG).show();});}});
 }
 private String readAsset(String name)throws IOException{try(InputStream in=getContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8");}}
 private String escapeJson(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
 private void addCarImages(Style s){
  int[] res={R.drawable.car_nc,R.drawable.car_gt86,R.drawable.car_r34,R.drawable.car_supra,R.drawable.car_nsx,R.drawable.car_gc8,R.drawable.car_evo6,R.drawable.car_ae86,R.drawable.car_350z,R.drawable.car_na,R.drawable.car_generic};
  for(int i=0;i<CAR_KEYS.length;i++){Bitmap b=BitmapFactory.decodeResource(getResources(),res[i]);if(b!=null)s.addImage("car-"+CAR_KEYS[i],b);}
 }
 public void refresh(){if(disposed)return;updateDynamic();maybeRoute();refreshCamera(false);updateNavigationProgress();}
 private void updateDynamic(){if(style==null)return;try{
  ArrayList<Feature> wfs=new ArrayList<>();ArrayList<Point> tripPoints=new ArrayList<>();int n=1;for(TripPlan.Waypoint w:waypoints){Feature f=Feature.fromGeometry(Point.fromLngLat(w.lon,w.lat));f.addStringProperty("label",n+"  "+w.name);f.addStringProperty("type",w.type);wfs.add(f);if(!w.done)tripPoints.add(Point.fromLngLat(w.lon,w.lat));n++;}set("waypoints",FeatureCollection.fromFeatures(wfs));ArrayList<Feature> t=new ArrayList<>();if(tripPoints.size()>1)t.add(Feature.fromGeometry(LineString.fromLngLats(tripPoints)));set("trip",FeatureCollection.fromFeatures(t));
  ConvoyService s=ConvoyService.current;ArrayList<Feature> own=new ArrayList<>(),crew=new ArrayList<>();if(s!=null&&s.fix!=null){Feature f=Feature.fromGeometry(Point.fromLngLat(s.fix.getLongitude(),s.fix.getLatitude()));f.addStringProperty("name","You");f.addStringProperty("icon","car-"+safeCar(s.myCar));f.addNumberProperty("bearing",s.fix.hasBearing()&&s.fix.hasSpeed()&&s.fix.getSpeed()>1.5?s.fix.getBearing():lastDrivingBearing);own.add(f);}if(s!=null)for(ConvoyService.Member m:s.members.values())if(m.located){Feature f=Feature.fromGeometry(Point.fromLngLat(m.lon,m.lat));f.addStringProperty("name",m.name+(m.age()>15000?" · stale":""));f.addStringProperty("icon","car-"+safeCar(m.car));crew.add(f);}set("own",FeatureCollection.fromFeatures(own));set("crew",FeatureCollection.fromFeatures(crew));
  ArrayList<Feature> rf=new ArrayList<>();if(route!=null&&route.points.size()>1){ArrayList<Point> pp=new ArrayList<>();for(double[] x:route.points)pp.add(Point.fromLngLat(x[1],x[0]));rf.add(Feature.fromGeometry(LineString.fromLngLats(pp)));}set("route",FeatureCollection.fromFeatures(rf));
  ArrayList<Feature> destination=new ArrayList<>();PlaceInfo target=target();if(target!=null){Feature point=Feature.fromGeometry(Point.fromLngLat(target.lon,target.lat));point.addStringProperty("name",target.name);destination.add(point);}set("destination",FeatureCollection.fromFeatures(destination));
 }catch(Exception ignored){}
 }
 private String safeCar(String key){if(key!=null)for(String k:CAR_KEYS)if(k.equals(key))return k;return "generic";}
 private void set(String id,FeatureCollection fc){GeoJsonSource src=style.getSourceAs(id);if(src!=null)src.setGeoJson(fc);}
 private PlaceInfo target(){if(manualTarget!=null)return manualTarget;for(TripPlan.Waypoint w:waypoints)if(!w.done)return new PlaceInfo(w.name,w.type,w.lat,w.lon);return null;}
 private String targetKey(){PlaceInfo p=target();return p==null?"":String.format(Locale.US,"%.6f,%.6f:%s",p.lat,p.lon,p.name);}
 private void invalidateRouteIfTargetChanged(){if(!targetKey().equals(routedTargetKey))invalidateRoute();}
 private void invalidateRoute(){routeGeneration++;route=null;routedTargetKey="";progressMeters=Float.NaN;lastRouteAt=0;instruction="";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;updateDynamic();}
 private void maybeRoute(){
  PlaceInfo t=target();ConvoyService s=ConvoyService.current;if(t==null){targetName="";return;}targetName=t.name;if(s==null||!freshFix(s.fix)||routeBusy||!router.isReady()||disposed)return;
  Location fix=s.fix;String key=targetKey();long now=SystemClock.elapsedRealtime();boolean need=route==null||!key.equals(routedTargetKey);
  if(!need&&now-lastRouteAt>10000){float threshold=Math.max(75,fix.hasAccuracy()?fix.getAccuracy()*2:75);need=route.progress(fix.getLatitude(),fix.getLongitude(),progressMeters).distanceToRoute>threshold;}
  if(!need||(lastRouteAt!=0&&now-lastRouteAt<5000))return;
  routeBusy=true;instruction="Calculating offline route…";double a=fix.getLatitude(),o=fix.getLongitude();int generation=routeGeneration;lastRouteAt=now;
  workers.execute(()->{try{OfflineRouter.Route result=router.route(a,o,t.lat,t.lon);main.post(()->{if(disposed)return;routeBusy=false;if(generation!=routeGeneration||!key.equals(targetKey())){maybeRoute();return;}route=result;routedTargetKey=key;progressMeters=Float.NaN;updateDynamic();updateNavigationProgress();});}catch(Exception e){main.post(()->{if(disposed)return;routeBusy=false;if(generation!=routeGeneration||!key.equals(targetKey())){maybeRoute();return;}instruction="Offline route unavailable: "+e.getMessage();distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;});}});
 }
 private boolean freshFix(Location fix){return fix!=null&&Double.isFinite(fix.getLatitude())&&Double.isFinite(fix.getLongitude())&&Math.abs(fix.getLatitude())<=90&&Math.abs(fix.getLongitude())<=180&&fix.getElapsedRealtimeNanos()>0&&Math.max(0,(SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos())/1000000)<=15000;}
 private void updateNavigationProgress(){
  PlaceInfo t=target();if(t==null){instruction="";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;return;}
  ConvoyService s=ConvoyService.current;if(s==null||s.fix==null){instruction="Waiting for GPS location…";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;return;}
  if(!freshFix(s.fix)){instruction="GPS signal lost · waiting for a fresh fix";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;return;}
  if(route==null){if(routerLoading)instruction="Preparing offline routing…";else if(!router.isReady())instruction="Offline routing data unavailable";return;}
  Location f=s.fix;OfflineRouter.Progress progress=route.progress(f.getLatitude(),f.getLongitude(),progressMeters);progressMeters=progress.distanceFromStart;distanceRemaining=progress.remainingDistance;etaSeconds=progress.etaSeconds;
  float[] targetDistance=new float[1];Location.distanceBetween(f.getLatitude(),f.getLongitude(),t.lat,t.lon,targetDistance);
  if(distanceRemaining<=35&&targetDistance[0]<=35){instruction="You have arrived";distanceRemaining=0;etaSeconds=0;maneuverDistance=0;return;}
  if(routeBusy){instruction="Recalculating offline route…";maneuverDistance=-1;return;}
  float offRouteThreshold=Math.max(75,f.hasAccuracy()?f.getAccuracy()*2:75);if(progress.distanceToRoute>offRouteThreshold){instruction="Off route · recalculating…";maneuverDistance=-1;return;}
  OfflineRouter.Instruction chosen=route.nextInstruction(progressMeters);instruction=chosen==null?"Follow the highlighted route":chosen.text;maneuverDistance=chosen==null?-1:Math.max(0,chosen.distanceFromStart-progressMeters);
 }
 private void refreshCamera(boolean force){if(map==null||disposed||(!follow&&!force))return;ConvoyService s=ConvoyService.current;if(s==null||s.fix==null||(!force&&!freshFix(s.fix)))return;Location f=s.fix;if(f.hasBearing()&&f.hasSpeed()&&f.getSpeed()>1.5)lastDrivingBearing=f.getBearing();double bearing=navigationMode?lastDrivingBearing:0;double tilt=navigationMode?50:0;CameraPosition p=new CameraPosition.Builder().target(new LatLng(f.getLatitude(),f.getLongitude())).zoom(zoom).bearing(bearing).tilt(tilt).padding(new double[]{0,0,0,navigationMode?getHeight()*0.28:0}).build();map.animateCamera(CameraUpdateFactory.newCameraPosition(p),force?500:700);}
 private boolean tap(LatLng ll){if(map==null||disposed)return false;PointF p=map.getProjection().toScreenLocation(ll);float radius=22*getResources().getDisplayMetrics().density;List<Feature> fs=map.queryRenderedFeatures(new RectF(p.x-radius,p.y-radius,p.x+radius,p.y+radius),new String[]{"overture-place-label","overture-place-dot","poi-important-label","poi-label","poi-important-dot","poi-dot"});if(fs.isEmpty())return false;Feature f=fs.get(0);float closest=Float.MAX_VALUE;for(Feature candidate:fs)if(candidate.geometry() instanceof Point){Point point=(Point)candidate.geometry();PointF pixel=map.getProjection().toScreenLocation(new LatLng(point.latitude(),point.longitude()));float distance=(pixel.x-p.x)*(pixel.x-p.x)+(pixel.y-p.y)*(pixel.y-p.y);if(distance<closest){closest=distance;f=candidate;}}PlaceInfo info=featurePlace(f,ll);if(info==null)return false;workers.execute(()->{try{PlaceInfo local=index.nearest(info.lat,info.lon,info.name);if(local!=null){if(info.address.isEmpty())info.address=local.address;if(info.phone.isEmpty())info.phone=local.phone;if(info.website.isEmpty())info.website=local.website;if(info.openingHours.isEmpty())info.openingHours=local.openingHours;}}catch(Exception ignored){}main.post(()->{if(!disposed&&placeListener!=null)placeListener.onPlace(info);});});return true;}
 private PlaceInfo featurePlace(Feature f,LatLng tap){
  try{double la=tap.getLatitude(),lo=tap.getLongitude();if(f.geometry() instanceof Point){Point q=(Point)f.geometry();la=q.latitude();lo=q.longitude();}String name=prop(f,"@name");if(name.isEmpty())name=prop(f,"name");String cat=prop(f,"basic_category");if(cat.isEmpty())cat=prop(f,"kind");if(name.isEmpty()&&cat.isEmpty())return null;PlaceInfo p=new PlaceInfo(name.isEmpty()?cat.replace('_',' '):name,cat,la,lo);p.website=firstJson(prop(f,"websites"));p.phone=firstJson(prop(f,"phones"));p.address=addressJson(prop(f,"addresses"));p.source=f.hasProperty("@name")?"Overture Maps":"OpenStreetMap";return p;}catch(Exception e){return null;}
 }
 private String prop(Feature f,String k){try{return f.hasProperty(k)&&!f.getProperty(k).isJsonNull()?(f.getProperty(k).isJsonPrimitive()?f.getProperty(k).getAsString():f.getProperty(k).toString()):"";}catch(Exception e){return "";}}
 private String firstJson(String s){if(s==null||s.isEmpty())return "";try{Object value=new org.json.JSONTokener(s).nextValue();if(value instanceof org.json.JSONArray){org.json.JSONArray array=(org.json.JSONArray)value;for(int i=0;i<array.length();i++){Object item=array.opt(i);if(item instanceof String&&!((String)item).trim().isEmpty())return (String)item;}return "";}if(value instanceof String)return (String)value;}catch(Exception ignored){}return s;}
 private String addressJson(String s){if(s==null||s.isEmpty())return "";try{Object value=new org.json.JSONTokener(s).nextValue();if(value instanceof org.json.JSONArray)value=((org.json.JSONArray)value).opt(0);if(!(value instanceof org.json.JSONObject))return "";org.json.JSONObject address=(org.json.JSONObject)value;ArrayList<String> parts=new ArrayList<>();for(String key:new String[]{"freeform","locality","postcode"}){String part=address.optString(key,"");if(!part.isEmpty()&&!part.equals("null")&&!parts.contains(part))parts.add(part);}return android.text.TextUtils.join(", ",parts);}catch(Exception e){return "";}}
 public void search(String query,SearchCallback cb){
  if(disposed)return;
  ConvoyService service=ConvoyService.current;
  Location candidate=service==null?null:service.fix;
  final Location near=freshFix(candidate)?new Location(candidate):null;
  workers.execute(()->{try{
   ArrayList<PlaceInfo> results=index.search(query,
           near==null?Double.NaN:near.getLatitude(),near==null?Double.NaN:near.getLongitude());
   main.post(()->{if(!disposed)cb.onResults(results);});
  }catch(Exception error){main.post(()->{if(!disposed)cb.onResults(new ArrayList<PlaceInfo>());});}});
 }
 public void searchCategory(String category,SearchCallback cb){search(category,cb);}
 public interface SearchCallback{void onResults(ArrayList<PlaceInfo> places);}
}

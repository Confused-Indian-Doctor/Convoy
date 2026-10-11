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
import org.maplibre.android.geometry.LatLngBounds;
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
 private final CurrentLocation locations;
 private MapPack selectedPack;
 private ArrayList<TripPlan.Waypoint> waypoints=new ArrayList<>();
 private boolean navigationMode=true,follow=true,routeBusy=false,disposed=false;
 private volatile boolean routerLoading=true;
 private double zoom=15,lastDrivingBearing=0; private long lastRouteAt=0; private int routeGeneration=0,styleGeneration=0;
 private float progressMeters=Float.NaN;
 private OfflineRouter.Route route; private PlaceInfo manualTarget; private String routedTargetKey="";
 private GpxRoute gpxRoute;
 private volatile GpxRoute.Progress gpxProgress;
 private FeatureCollection gpxGeometry=FeatureCollection.fromFeatures(new ArrayList<Feature>()),gpxWaypointGeometry=FeatureCollection.fromFeatures(new ArrayList<Feature>());
 private boolean followingGpx=false,gpxProgressBusy=false,pendingGpxFocus=false,cameraInitialised=false,rejoiningGpx=false;
 private long lastGpxProgressAt=0,lastGpxFixNanos=0,lastBearingFixNanos=0;
 private int gpxGeneration=0;
 private boolean bearingKnown=false;
 private OnPlaceSelectedListener placeListener;
 private volatile String instruction="",targetName=""; private volatile float distanceRemaining=-1,maneuverDistance=-1; private volatile int etaSeconds=-1;
 private static final String[] CAR_KEYS={"nc","gt86","r34","supra","nsx","gc8","evo6","ae86","z350","na","generic"};

 public RichMap(Context c){
  super(c); index=new PlaceIndex(c); locations=CurrentLocation.get(c); MapLibre.getInstance(c.getApplicationContext()); mapView=new MapView(c); addView(mapView,new FrameLayout.LayoutParams(-1,-1));mapView.onCreate(null);
  mapView.getMapAsync(m->{if(disposed)return;map=m;m.getUiSettings().setLogoEnabled(false);m.getUiSettings().setAttributionEnabled(false);m.getUiSettings().setCompassEnabled(true);m.setMinZoomPreference(3);m.setMaxZoomPreference(18);m.addOnCameraMoveStartedListener(reason->{if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)follow=false;});m.addOnCameraIdleListener(()->{if(!follow)zoom=map.getCameraPosition().zoom;});m.addOnMapClickListener(this::tap);load();});
  workers.execute(()->{try{router.load(c.getApplicationContext().getAssets().open("route.graph"));}catch(Exception ignored){}finally{routerLoading=false;}main.post(()->{if(!disposed)refresh();});});
 }
 public void setOnPlaceSelectedListener(OnPlaceSelectedListener l){placeListener=l;}
 public boolean isNavigationMode(){return navigationMode;}
 public void setNavigationMode(boolean enabled){navigationMode=enabled;follow=true;refreshCamera(true);}
 public void recenter(){follow=true;refreshCamera(true);}
 public void changeZoom(int delta){zoom=Math.max(3,Math.min(18,zoom+delta));if(map!=null)map.animateCamera(CameraUpdateFactory.zoomTo(zoom),250);}
 public void focus(double lat,double lon){follow=false;if(map!=null)map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(lat,lon)).zoom(Math.max(15,zoom)).tilt(0).bearing(0).build()),450);}
 public void setWaypoints(List<TripPlan.Waypoint> p){waypoints=new ArrayList<>(p);if(manualTarget==null)invalidateRouteIfTargetChanged();updateDynamic();}
 public void navigateTo(PlaceInfo p){manualTarget=p;rejoiningGpx=false;invalidateRoute();targetName=p.name;follow=true;refresh();}
 public void clearManualTarget(){manualTarget=null;rejoiningGpx=false;invalidateRoute();refresh();}
 public boolean hasManualTarget(){return manualTarget!=null;}
 public String getInstruction(){return instruction;}
 public String getTargetName(){return targetName;}
 public float getDistanceRemaining(){return distanceRemaining;}
 public float getManeuverDistance(){return maneuverDistance;}
 public int getEtaSeconds(){return etaSeconds;}
 public boolean routingReady(){return router.isReady();}
 public boolean routingAvailable(double lat,double lon){return router.isReady()&&OfflineCoverage.bundledDataContains(lat,lon);}
 public boolean searchAvailable(double lat,double lon){return OfflineCoverage.bundledDataContains(lat,lon);}
 public String searchCoverageText(){return "Offline search: Shrewsbury development region";}
 public String getCoverageStatus(){Location fix=locations.fix();LatLng center=map==null?null:map.getCameraPosition().target;double lat=fix!=null?fix.getLatitude():center==null?52.7078:center.getLatitude(),lon=fix!=null?fix.getLongitude():center==null?-2.7541:center.getLongitude();boolean mapAvailable=selectedPack==null?OfflineCoverage.bundledDataContains(lat,lon):selectedPack.bounds.contains(lat,lon);boolean data=OfflineCoverage.bundledDataContains(lat,lon);return (mapAvailable?"Map available":"Map coverage not installed")+" • "+(data?"Search available":"Search not installed")+" • "+(data&&router.isReady()?"Routing available":"Routing not installed");}
 public void reloadSelectedMap(){load();}
 /** Render immutable original segments; GPX geometry is never passed to the road router. */
 public void setGpxRoute(GpxRoute selected,boolean following){
  if(disposed)return;
  if(gpxRoute==selected){setGpxFollowing(following);return;}
  gpxRoute=selected;followingGpx=selected!=null&&following;gpxProgress=null;gpxProgressBusy=false;lastGpxProgressAt=lastGpxFixNanos=0;pendingGpxFocus=false;int generation=++gpxGeneration;
  gpxGeometry=FeatureCollection.fromFeatures(new ArrayList<Feature>());gpxWaypointGeometry=FeatureCollection.fromFeatures(new ArrayList<Feature>());updateGpxSources();
  if(rejoiningGpx)clearManualTarget();
  if(selected==null)return;
  workers.execute(()->{
   ArrayList<Feature> lines=new ArrayList<>(),places=new ArrayList<>();
   for(GpxRoute.Segment segment:selected.segments){ArrayList<Point> points=new ArrayList<>();for(GpxRoute.Point point:segment.points){if(Thread.currentThread().isInterrupted())return;points.add(Point.fromLngLat(point.lon,point.lat));}if(points.size()>1)lines.add(Feature.fromGeometry(LineString.fromLngLats(points)));}
   for(GpxRoute.Waypoint waypoint:selected.waypoints){Feature f=Feature.fromGeometry(Point.fromLngLat(waypoint.lon,waypoint.lat));f.addStringProperty("name",waypoint.name);f.addStringProperty("type",waypoint.type);f.addStringProperty("description",waypoint.description);f.addStringProperty("symbol",waypoint.symbol);f.addBooleanProperty("gpxWaypoint",true);places.add(f);}
   FeatureCollection geometry=FeatureCollection.fromFeatures(lines),markers=FeatureCollection.fromFeatures(places);
   main.post(()->{if(disposed||generation!=gpxGeneration||gpxRoute!=selected)return;gpxGeometry=geometry;gpxWaypointGeometry=markers;updateGpxSources();maybeGpxProgress();});
  });
 }
 public void setGpxFollowing(boolean following){boolean wasFollowing=followingGpx;followingGpx=gpxRoute!=null&&following;if(followingGpx&&!wasFollowing){follow=true;refreshCamera(true);}maybeGpxProgress();}
 public boolean isFollowingGpx(){return followingGpx;}
 public GpxRoute.Progress getGpxProgress(){return gpxProgress;}
 public void focusGpxRoute(){if(gpxRoute==null)return;if(map==null||style==null){pendingGpxFocus=true;return;}pendingGpxFocus=false;GpxRoute.Bounds b=gpxRoute.bounds;focusBounds(b.south,b.west,b.north,b.east);}
 private void focusBounds(double south,double west,double north,double east){if(map==null)return;follow=false;double padding=Math.max(.001,Math.max(north-south,east-west)*.015);LatLngBounds bounds=new LatLngBounds.Builder().include(new LatLng(Math.min(90,north+padding),Math.min(180,east+padding))).include(new LatLng(Math.max(-90,south-padding),Math.max(-180,west-padding))).build();map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds,(int)(48*getResources().getDisplayMetrics().density)),600);}
 private void updateGpxSources(){if(style!=null){set("gpx",gpxGeometry);set("gpx-waypoints",gpxWaypointGeometry);}}
 private void maybeGpxProgress(){
  if(disposed||gpxRoute==null||gpxProgressBusy)return;Location fix=locations.fix();if(!freshFix(fix))return;long now=SystemClock.elapsedRealtime();if(now-lastGpxProgressAt<1000||fix.getElapsedRealtimeNanos()==lastGpxFixNanos)return;
  GpxRoute selected=gpxRoute;int generation=gpxGeneration;double previous=gpxProgress==null?Double.NaN:gpxProgress.alongMeters;gpxProgressBusy=true;lastGpxProgressAt=now;lastGpxFixNanos=fix.getElapsedRealtimeNanos();
  workers.execute(()->{try{GpxRoute.Progress result=selected.progress(fix.getLatitude(),fix.getLongitude(),previous);main.post(()->{if(disposed||generation!=gpxGeneration||selected!=gpxRoute)return;gpxProgressBusy=false;gpxProgress=result;});}catch(RuntimeException error){main.post(()->{if(disposed||generation!=gpxGeneration)return;gpxProgressBusy=false;android.util.Log.w("ConvoyMap","GPX progress unavailable",error);});}});
 }
 /** Calculate a separate blue approach route when both endpoints have installed road data. */
 public String rejoinGpx(){
  if(gpxRoute==null)return "Select a GPX route first.";Location fix=locations.fix();if(!freshFix(fix))return "Waiting for a fresh GPS fix.";GpxRoute.Progress progress=gpxProgress;if(progress==null){maybeGpxProgress();return "Locating the nearest point on the GPX track…";}
  if(!routingAvailable(fix.getLatitude(),fix.getLongitude())||!routingAvailable(progress.nearestLat,progress.nearestLon)){float[] direction=new float[3];Location.distanceBetween(fix.getLatitude(),fix.getLongitude(),progress.nearestLat,progress.nearestLon,direction);String[] compass={"north","north-east","east","south-east","south","south-west","west","north-west"};int sector=(int)Math.round((direction[1]+360)%360/45)%8;return String.format(Locale.UK,"GPX track is %.0f m %s. Offline routing data is not installed for this area. The original GPX remains unchanged.",direction[0],compass[sector]);}
  navigateTo(new PlaceInfo("Rejoin "+gpxRoute.name,"gpx_rejoin",progress.nearestLat,progress.nearestLon));rejoiningGpx=true;return "Calculating a separate offline route back to the GPX track.";
 }
 public void onStart(){mapView.onStart();} public void onResume(){mapView.onResume();} public void onPause(){mapView.onPause();} public void onStop(){mapView.onStop();} public void onLowMemory(){mapView.onLowMemory();}
 public void onSaveInstanceState(Bundle b){mapView.onSaveInstanceState(b);}
 public void dispose(){disposed=true;routeGeneration++;styleGeneration++;gpxGeneration++;workers.shutdownNow();main.removeCallbacksAndMessages(null);index.close();mapView.onDestroy();}

 public void load(){
  if(map==null||disposed)return;
  final int generation=++styleGeneration;
  final Context context=getContext().getApplicationContext();
  final CameraPosition previous=cameraInitialised?map.getCameraPosition():null;
  workers.execute(()->{try{
   File apk=new File(context.getApplicationInfo().sourceDir),directory=new File(context.getFilesDir(),"bundled-vector-maps");
   MapPack pack=null;File imported=null;
   try{MapPackStore packs=new MapPackStore(context.getFilesDir());pack=packs.selected();imported=packs.selectedFile();}catch(IOException error){android.util.Log.w("ConvoyMap","Using bundled map because map catalogue could not be read",error);}
   File basemap=imported!=null?imported:LocalTiles.extractAsset(apk,directory,"shrewsbury.pmtiles");
   File places=LocalTiles.extractAsset(apk,directory,"overture-shrewsbury.pmtiles");
   String json=readAsset("convoy-style.json")
     .replace("__BASEMAP_URI__",escapeJson("pmtiles://"+android.net.Uri.fromFile(basemap)))
     .replace("__PLACES_URI__",escapeJson("pmtiles://"+android.net.Uri.fromFile(places)));
   final MapPack loadedPack=pack;
   main.post(()->{if(disposed||generation!=styleGeneration)return;selectedPack=loadedPack;mapName=loadedPack==null?"Shrewsbury development region":loadedPack.name;style=null;map.setStyle(new Style.Builder().fromJson(json),s->{if(disposed||generation!=styleGeneration)return;style=s;addCarImages(s);updateDynamic();updateGpxSources();if(previous!=null)map.setCameraPosition(previous);else{map.setCameraPosition(new CameraPosition.Builder().target(new LatLng(52.7078,-2.7541)).zoom(13.3).build());if(loadedPack!=null&&!loadedPack.bundled)focusBounds(loadedPack.bounds.south,loadedPack.bounds.west,loadedPack.bounds.north,loadedPack.bounds.east);}cameraInitialised=true;if(pendingGpxFocus)focusGpxRoute();refresh();});});
  }catch(Exception error){main.post(()->{if(disposed||generation!=styleGeneration)return;android.util.Log.e("ConvoyMap","Offline map preparation failed",error);Toast.makeText(getContext(),"Vector map: "+error.getMessage(),Toast.LENGTH_LONG).show();});}});
 }
 private String readAsset(String name)throws IOException{try(InputStream in=getContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8");}}
 private String escapeJson(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
 private void addCarImages(Style s){
  int[] res={R.drawable.car_nc,R.drawable.car_gt86,R.drawable.car_r34,R.drawable.car_supra,R.drawable.car_nsx,R.drawable.car_gc8,R.drawable.car_evo6,R.drawable.car_ae86,R.drawable.car_350z,R.drawable.car_na,R.drawable.car_generic};
  for(int i=0;i<CAR_KEYS.length;i++){
   Bitmap b=BitmapFactory.decodeResource(getResources(),res[i]);
   if(b!=null){
    // MapLibre derives the sprite pixel ratio from Bitmap density. These nodpi
    // cars use 128 logical pixels, independent of the phone's display density.
    b.setDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
    s.addImage("car-"+CAR_KEYS[i],b);
   }
  }
 }
 public void refresh(){if(disposed)return;updateDrivingBearing(locations.fix());updateDynamic();maybeRoute();maybeGpxProgress();refreshCamera(false);updateNavigationProgress();}
 private void updateDynamic(){if(style==null)return;try{
  ArrayList<Feature> wfs=new ArrayList<>();ArrayList<Point> tripPoints=new ArrayList<>();int n=1;for(TripPlan.Waypoint w:waypoints){Feature f=Feature.fromGeometry(Point.fromLngLat(w.lon,w.lat));f.addStringProperty("label",n+"  "+w.name);f.addStringProperty("type",w.type);wfs.add(f);if(!w.done)tripPoints.add(Point.fromLngLat(w.lon,w.lat));n++;}set("waypoints",FeatureCollection.fromFeatures(wfs));ArrayList<Feature> t=new ArrayList<>();if(tripPoints.size()>1)t.add(Feature.fromGeometry(LineString.fromLngLats(tripPoints)));set("trip",FeatureCollection.fromFeatures(t));
  ConvoyService s=ConvoyService.current;Location fix=locations.fix();ArrayList<Feature> own=new ArrayList<>(),crew=new ArrayList<>();if(fix!=null){Feature f=Feature.fromGeometry(Point.fromLngLat(fix.getLongitude(),fix.getLatitude()));f.addStringProperty("name",freshFix(fix)?"You":"You · GPS stale");f.addStringProperty("icon","car-"+safeCar(locations.selectedCar()));f.addNumberProperty("bearing",lastDrivingBearing);own.add(f);}if(s!=null)for(ConvoyService.Member m:s.members.values())if(m.located){Feature f=Feature.fromGeometry(Point.fromLngLat(m.lon,m.lat));f.addStringProperty("name",m.name+(m.age()>15000?" · stale":""));f.addStringProperty("icon","car-"+safeCar(m.car));crew.add(f);}set("own",FeatureCollection.fromFeatures(own));set("crew",FeatureCollection.fromFeatures(crew));
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
  PlaceInfo t=target();if(t==null){targetName="";return;}targetName=t.name;Location fix=locations.fix();if(!freshFix(fix)||routeBusy||!router.isReady()||disposed)return;
  if(!routingAvailable(fix.getLatitude(),fix.getLongitude())||!routingAvailable(t.lat,t.lon)){if(route!=null)invalidateRoute();instruction="Offline routing data is not installed for this area";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;return;}
  String key=targetKey();long now=SystemClock.elapsedRealtime();boolean need=route==null||!key.equals(routedTargetKey);
  if(!need&&now-lastRouteAt>10000){float threshold=Math.max(75,fix.hasAccuracy()?fix.getAccuracy()*2:75);need=route.progress(fix.getLatitude(),fix.getLongitude(),progressMeters).distanceToRoute>threshold;}
  if(!need||(lastRouteAt!=0&&now-lastRouteAt<5000))return;
  routeBusy=true;instruction="Calculating offline route…";double a=fix.getLatitude(),o=fix.getLongitude();int generation=routeGeneration;lastRouteAt=now;
  workers.execute(()->{try{OfflineRouter.Route result=router.route(a,o,t.lat,t.lon);main.post(()->{if(disposed)return;routeBusy=false;if(generation!=routeGeneration||!key.equals(targetKey())){maybeRoute();return;}route=result;routedTargetKey=key;progressMeters=Float.NaN;updateDynamic();updateNavigationProgress();});}catch(Exception e){main.post(()->{if(disposed)return;routeBusy=false;if(generation!=routeGeneration||!key.equals(targetKey())){maybeRoute();return;}instruction="Offline route unavailable: "+e.getMessage();distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;});}});
 }
 private boolean freshFix(Location fix){return fix!=null&&Double.isFinite(fix.getLatitude())&&Double.isFinite(fix.getLongitude())&&Math.abs(fix.getLatitude())<=90&&Math.abs(fix.getLongitude())<=180&&fix.getElapsedRealtimeNanos()>0&&Math.max(0,(SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos())/1000000)<=15000;}
 private void updateNavigationProgress(){
  PlaceInfo t=target();if(t==null){instruction=followingGpx?"Following GPX track":"";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;return;}
  Location f=locations.fix();if(f==null){instruction="Waiting for GPS location…";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;return;}
  if(!freshFix(f)){instruction="GPS signal lost · waiting for a fresh fix";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;return;}
  if(!OfflineCoverage.bundledDataContains(f.getLatitude(),f.getLongitude())||!OfflineCoverage.bundledDataContains(t.lat,t.lon)){instruction="Offline routing data is not installed for this area";distanceRemaining=-1;maneuverDistance=-1;etaSeconds=-1;return;}
  if(route==null){if(routerLoading)instruction="Preparing offline routing…";else if(!router.isReady())instruction="Offline routing data unavailable";return;}
  OfflineRouter.Progress progress=route.progress(f.getLatitude(),f.getLongitude(),progressMeters);progressMeters=progress.distanceFromStart;distanceRemaining=progress.remainingDistance;etaSeconds=progress.etaSeconds;
  float[] targetDistance=new float[1];Location.distanceBetween(f.getLatitude(),f.getLongitude(),t.lat,t.lon,targetDistance);
  if(distanceRemaining<=35&&targetDistance[0]<=35){instruction="You have arrived";distanceRemaining=0;etaSeconds=0;maneuverDistance=0;return;}
  if(routeBusy){instruction="Recalculating offline route…";maneuverDistance=-1;return;}
  float offRouteThreshold=Math.max(75,f.hasAccuracy()?f.getAccuracy()*2:75);if(progress.distanceToRoute>offRouteThreshold){instruction="Off route · recalculating…";maneuverDistance=-1;return;}
  OfflineRouter.Instruction chosen=route.nextInstruction(progressMeters);instruction=chosen==null?"Follow the highlighted route":chosen.text;maneuverDistance=chosen==null?-1:Math.max(0,chosen.distanceFromStart-progressMeters);
 }
 private void updateDrivingBearing(Location fix){
  if(!freshFix(fix)||fix.getElapsedRealtimeNanos()==lastBearingFixNanos||!fix.hasBearing()||!fix.hasSpeed()||fix.getSpeed()<2||!Float.isFinite(fix.getBearing())||(fix.hasAccuracy()&&fix.getAccuracy()>35)||(fix.hasBearingAccuracy()&&fix.getBearingAccuracyDegrees()>35))return;
  lastBearingFixNanos=fix.getElapsedRealtimeNanos();double bearing=(fix.getBearing()%360+360)%360;if(!bearingKnown){lastDrivingBearing=bearing;bearingKnown=true;}else{double delta=(bearing-lastDrivingBearing+540)%360-180;lastDrivingBearing=(lastDrivingBearing+delta*.45+360)%360;}
 }
 private void refreshCamera(boolean force){if(map==null||disposed||!isShown()||(!follow&&!force))return;Location f=locations.fix();if(!freshFix(f))return;updateDrivingBearing(f);double bearing=navigationMode?lastDrivingBearing:0;double tilt=navigationMode?50:0;CameraPosition p=new CameraPosition.Builder().target(new LatLng(f.getLatitude(),f.getLongitude())).zoom(zoom).bearing(bearing).tilt(tilt).padding(new double[]{0,0,0,navigationMode?getHeight()*0.28:0}).build();map.animateCamera(CameraUpdateFactory.newCameraPosition(p),force?500:700);}
 private boolean tap(LatLng ll){if(map==null||disposed)return false;PointF p=map.getProjection().toScreenLocation(ll);float radius=22*getResources().getDisplayMetrics().density;List<Feature> fs=map.queryRenderedFeatures(new RectF(p.x-radius,p.y-radius,p.x+radius,p.y+radius),new String[]{"gpx-waypoint-label","gpx-waypoint-dot","overture-place-label","overture-place-dot","poi-important-label","poi-label","poi-important-dot","poi-dot"});if(fs.isEmpty())return false;Feature f=fs.get(0);float closest=Float.MAX_VALUE;for(Feature candidate:fs)if(candidate.geometry() instanceof Point){Point point=(Point)candidate.geometry();PointF pixel=map.getProjection().toScreenLocation(new LatLng(point.latitude(),point.longitude()));float distance=(pixel.x-p.x)*(pixel.x-p.x)+(pixel.y-p.y)*(pixel.y-p.y);if(distance<closest){closest=distance;f=candidate;}}PlaceInfo info=featurePlace(f,ll);if(info==null)return false;if(info.source.equals("GPX waypoint")||!searchAvailable(info.lat,info.lon)){if(placeListener!=null)placeListener.onPlace(info);return true;}workers.execute(()->{try{PlaceInfo local=index.nearest(info.lat,info.lon,info.name);if(local!=null){if(info.address.isEmpty())info.address=local.address;if(info.phone.isEmpty())info.phone=local.phone;if(info.website.isEmpty())info.website=local.website;if(info.openingHours.isEmpty())info.openingHours=local.openingHours;}}catch(Exception ignored){}main.post(()->{if(!disposed&&placeListener!=null)placeListener.onPlace(info);});});return true;}
 private PlaceInfo featurePlace(Feature f,LatLng tap){
  try{double la=tap.getLatitude(),lo=tap.getLongitude();if(f.geometry() instanceof Point){Point q=(Point)f.geometry();la=q.latitude();lo=q.longitude();}String name=prop(f,"@name");if(name.isEmpty())name=prop(f,"name");if("true".equals(prop(f,"gpxWaypoint"))){String type=prop(f,"type");if(type.isEmpty())type=prop(f,"symbol");PlaceInfo waypoint=new PlaceInfo(name.isEmpty()?"GPX waypoint":name,type.isEmpty()?"waypoint":type,la,lo);waypoint.address=prop(f,"description");waypoint.source="GPX waypoint";return waypoint;}String cat=prop(f,"basic_category");if(cat.isEmpty())cat=prop(f,"kind");if(name.isEmpty()&&cat.isEmpty())return null;PlaceInfo p=new PlaceInfo(name.isEmpty()?cat.replace('_',' '):name,cat,la,lo);p.website=firstJson(prop(f,"websites"));p.phone=firstJson(prop(f,"phones"));p.address=addressJson(prop(f,"addresses"));p.source=f.hasProperty("@name")?"Overture Maps":"OpenStreetMap";return p;}catch(Exception e){return null;}
 }
 private String prop(Feature f,String k){try{return f.hasProperty(k)&&!f.getProperty(k).isJsonNull()?(f.getProperty(k).isJsonPrimitive()?f.getProperty(k).getAsString():f.getProperty(k).toString()):"";}catch(Exception e){return "";}}
 private String firstJson(String s){if(s==null||s.isEmpty())return "";try{Object value=new org.json.JSONTokener(s).nextValue();if(value instanceof org.json.JSONArray){org.json.JSONArray array=(org.json.JSONArray)value;for(int i=0;i<array.length();i++){Object item=array.opt(i);if(item instanceof String&&!((String)item).trim().isEmpty())return (String)item;}return "";}if(value instanceof String)return (String)value;}catch(Exception ignored){}return s;}
 private String addressJson(String s){if(s==null||s.isEmpty())return "";try{Object value=new org.json.JSONTokener(s).nextValue();if(value instanceof org.json.JSONArray)value=((org.json.JSONArray)value).opt(0);if(!(value instanceof org.json.JSONObject))return "";org.json.JSONObject address=(org.json.JSONObject)value;ArrayList<String> parts=new ArrayList<>();for(String key:new String[]{"freeform","locality","postcode"}){String part=address.optString(key,"");if(!part.isEmpty()&&!part.equals("null")&&!parts.contains(part))parts.add(part);}return android.text.TextUtils.join(", ",parts);}catch(Exception e){return "";}}
 public void search(String query,SearchCallback cb){
  if(disposed)return;
  Location candidate=locations.fix();
  final Location near=freshFix(candidate)&&searchAvailable(candidate.getLatitude(),candidate.getLongitude())?new Location(candidate):null;
  workers.execute(()->{try{
   ArrayList<PlaceInfo> results=index.search(query,
           near==null?Double.NaN:near.getLatitude(),near==null?Double.NaN:near.getLongitude());
   main.post(()->{if(!disposed)cb.onResults(results);});
  }catch(Exception error){main.post(()->{if(!disposed)cb.onResults(new ArrayList<PlaceInfo>());});}});
 }
 public void searchCategory(String category,SearchCallback cb){search(category,cb);}
 public interface SearchCallback{void onResults(ArrayList<PlaceInfo> places);}
}

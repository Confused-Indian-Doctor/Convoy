package com.convoy.offline;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
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
 private boolean navigationMode=true,follow=true,routerLoading=true,routeBusy=false;
 private double zoom=14.3; private long lastRouteAt=0; private double routedFromLat=Double.NaN,routedFromLon=Double.NaN;
 private OfflineRouter.Route route; private PlaceInfo manualTarget; private String routedTargetKey="";
 private OnPlaceSelectedListener placeListener;
 private volatile String instruction="",targetName=""; private volatile float distanceRemaining=-1; private volatile int etaSeconds=-1;
 private static final String[] CAR_KEYS={"nc","gt86","r34","supra","nsx","gc8","evo6","ae86","z350","na","generic"};

 public RichMap(Context c){
  super(c); index=new PlaceIndex(c); MapLibre.getInstance(c.getApplicationContext()); mapView=new MapView(c); addView(mapView,new FrameLayout.LayoutParams(-1,-1));
  mapView.getMapAsync(m->{map=m;m.getUiSettings().setLogoEnabled(false);m.getUiSettings().setAttributionEnabled(false);m.getUiSettings().setCompassEnabled(true);m.setMinZoomPreference(8);m.setMaxZoomPreference(18);m.addOnCameraMoveStartedListener(reason->{if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)follow=false;});m.addOnMapClickListener(this::tap);load();});
  workers.execute(()->{try{router.load(c.getApplicationContext());}catch(Exception ignored){}routerLoading=false;});
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
 public int getEtaSeconds(){return etaSeconds;}
 public boolean routingReady(){return router.isReady();}
 public void onStart(){mapView.onStart();} public void onResume(){mapView.onResume();} public void onPause(){mapView.onPause();} public void onStop(){mapView.onStop();} public void onLowMemory(){mapView.onLowMemory();}
 public void onSaveInstanceState(Bundle b){mapView.onSaveInstanceState(b);}
 public void dispose(){workers.shutdownNow();index.close();mapView.onDestroy();}

 public void load(){
  if(map==null)return;
  try{
   String json=readAsset("convoy-style.json");File imported=new File(getContext().getFilesDir(),"region.pmtiles");String base=imported.exists()?"pmtiles://file://"+imported.getAbsolutePath():"pmtiles://asset://shrewsbury.pmtiles";
   json=json.replace("__BASEMAP_URI__",escapeJson(base)).replace("__PLACES_URI__","pmtiles://asset://overture-shrewsbury.pmtiles");
   mapName=imported.exists()?getContext().getSharedPreferences("convoy",0).getString("mapName","Imported vector region"):"Shrewsbury rich offline vector map";
   map.setStyle(new Style.Builder().fromJson(json),s->{style=s;addCarImages(s);updateDynamic();map.setCameraPosition(new CameraPosition.Builder().target(new LatLng(52.7078,-2.7541)).zoom(13.3).build());refresh();});
  }catch(Exception e){Toast.makeText(getContext(),"Vector map: "+e.getMessage(),Toast.LENGTH_LONG).show();}
 }
 private String readAsset(String name)throws IOException{try(InputStream in=getContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8");}}
 private String escapeJson(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
 private void addCarImages(Style s){
  int[] res={R.drawable.car_nc,R.drawable.car_gt86,R.drawable.car_r34,R.drawable.car_supra,R.drawable.car_nsx,R.drawable.car_gc8,R.drawable.car_evo6,R.drawable.car_ae86,R.drawable.car_350z,R.drawable.car_na,R.drawable.car_generic};
  for(int i=0;i<CAR_KEYS.length;i++){Bitmap b=BitmapFactory.decodeResource(getResources(),res[i]);if(b!=null)s.addImage("car-"+CAR_KEYS[i],b);}
 }
 public void refresh(){updateDynamic();maybeRoute();refreshCamera(false);updateNavigationProgress();}
 private void updateDynamic(){if(style==null)return;try{
  ArrayList<Feature> wfs=new ArrayList<>();ArrayList<Point> tripPoints=new ArrayList<>();int n=1;for(TripPlan.Waypoint w:waypoints){Feature f=Feature.fromGeometry(Point.fromLngLat(w.lon,w.lat));f.addStringProperty("label",n+"  "+w.name);f.addStringProperty("type",w.type);wfs.add(f);if(!w.done)tripPoints.add(Point.fromLngLat(w.lon,w.lat));n++;}set("waypoints",FeatureCollection.fromFeatures(wfs));ArrayList<Feature> t=new ArrayList<>();if(tripPoints.size()>1)t.add(Feature.fromGeometry(LineString.fromLngLats(tripPoints)));set("trip",FeatureCollection.fromFeatures(t));
  ConvoyService s=ConvoyService.current;ArrayList<Feature> own=new ArrayList<>(),crew=new ArrayList<>();if(s!=null&&s.fix!=null){Feature f=Feature.fromGeometry(Point.fromLngLat(s.fix.getLongitude(),s.fix.getLatitude()));f.addStringProperty("name","You");f.addStringProperty("icon","car-"+safeCar(s.myCar));f.addNumberProperty("bearing",s.fix.hasBearing()?s.fix.getBearing():0);own.add(f);}if(s!=null)for(ConvoyService.Member m:s.members.values())if(m.located){Feature f=Feature.fromGeometry(Point.fromLngLat(m.lon,m.lat));f.addStringProperty("name",m.name+(m.age()>15000?" · stale":""));f.addStringProperty("icon","car-"+safeCar(m.car));crew.add(f);}set("own",FeatureCollection.fromFeatures(own));set("crew",FeatureCollection.fromFeatures(crew));
  ArrayList<Feature> rf=new ArrayList<>();if(route!=null&&route.points.size()>1){ArrayList<Point> pp=new ArrayList<>();for(double[] x:route.points)pp.add(Point.fromLngLat(x[1],x[0]));rf.add(Feature.fromGeometry(LineString.fromLngLats(pp)));}set("route",FeatureCollection.fromFeatures(rf));
 }catch(Exception ignored){}
 }
 private String safeCar(String key){if(key!=null)for(String k:CAR_KEYS)if(k.equals(key))return k;return "generic";}
 private void set(String id,FeatureCollection fc){GeoJsonSource src=style.getSourceAs(id);if(src!=null)src.setGeoJson(fc);}
 private PlaceInfo target(){if(manualTarget!=null)return manualTarget;for(TripPlan.Waypoint w:waypoints)if(!w.done)return new PlaceInfo(w.name,w.type,w.lat,w.lon);return null;}
 private String targetKey(){PlaceInfo p=target();return p==null?"":String.format(Locale.US,"%.6f,%.6f:%s",p.lat,p.lon,p.name);}
 private void invalidateRouteIfTargetChanged(){if(!targetKey().equals(routedTargetKey))invalidateRoute();}
 private void invalidateRoute(){route=null;routedTargetKey="";instruction="";distanceRemaining=-1;etaSeconds=-1;updateDynamic();}
 private void maybeRoute(){
  PlaceInfo t=target();ConvoyService s=ConvoyService.current;if(t==null){targetName="";return;}targetName=t.name;if(s==null||s.fix==null||routeBusy||!router.isReady())return;
  Location fix=s.fix;String key=targetKey();boolean need=route==null||!key.equals(routedTargetKey);if(!need&&SystemClock.elapsedRealtime()-lastRouteAt>15000&&Double.isFinite(routedFromLat)){float[] d=new float[1];Location.distanceBetween(fix.getLatitude(),fix.getLongitude(),routedFromLat,routedFromLon,d);need=d[0]>180&&distanceToRoute(fix.getLatitude(),fix.getLongitude())>100;}
  if(!need)return;routeBusy=true;double a=fix.getLatitude(),o=fix.getLongitude();lastRouteAt=SystemClock.elapsedRealtime();workers.execute(()->{try{OfflineRouter.Route result=router.route(a,o,t.lat,t.lon);main.post(()->{route=result;routedTargetKey=key;routedFromLat=a;routedFromLon=o;routeBusy=false;updateDynamic();updateNavigationProgress();});}catch(Exception e){main.post(()->{routeBusy=false;instruction="Offline route unavailable: "+e.getMessage();distanceRemaining=-1;etaSeconds=-1;});}});
 }
 private float distanceToRoute(double a,double o){if(route==null)return Float.MAX_VALUE;float best=Float.MAX_VALUE;for(double[] x:route.points){float[] d=new float[1];Location.distanceBetween(a,o,x[0],x[1],d);if(d[0]<best)best=d[0];}return best;}
 private void updateNavigationProgress(){
  if(route==null){if(routerLoading)instruction="Preparing offline routing…";else if(target()!=null&&!router.isReady())instruction="Offline routing data unavailable";return;}ConvoyService s=ConvoyService.current;if(s==null||s.fix==null)return;
  Location f=s.fix;int best=0;float bestD=Float.MAX_VALUE,progress=0,total=0;float[] seg=new float[Math.max(1,route.points.size())];for(int i=1;i<route.points.size();i++){double[] a=route.points.get(i-1),b=route.points.get(i);float[] d=new float[1];Location.distanceBetween(a[0],a[1],b[0],b[1],d);seg[i]=d[0];total+=d[0];}float cum=0;for(int i=0;i<route.points.size();i++){double[] x=route.points.get(i);float[] d=new float[1];Location.distanceBetween(f.getLatitude(),f.getLongitude(),x[0],x[1],d);if(d[0]<bestD){bestD=d[0];best=i;progress=cum;}if(i+1<route.points.size())cum+=seg[i+1];}distanceRemaining=Math.max(0,total-progress);etaSeconds=route.distanceMeters>1?(int)(route.etaSeconds*(distanceRemaining/route.distanceMeters)):0;
  OfflineRouter.Instruction chosen=route.instructions.get(route.instructions.size()-1);for(OfflineRouter.Instruction x:route.instructions)if(x.distanceFromStart>progress+25){chosen=x;break;}instruction=chosen.text;
 }
 private void refreshCamera(boolean force){if(map==null||(!follow&&!force))return;ConvoyService s=ConvoyService.current;if(s==null||s.fix==null)return;Location f=s.fix;double bearing=navigationMode&&f.hasBearing()&&f.getSpeed()>1.5?f.getBearing():0;double tilt=navigationMode?50:0;double z=navigationMode?Math.max(14.5,zoom):zoom;CameraPosition p=new CameraPosition.Builder().target(new LatLng(f.getLatitude(),f.getLongitude())).zoom(z).bearing(bearing).tilt(tilt).padding(new double[]{0,0,0,navigationMode?getHeight()*0.28:0}).build();map.animateCamera(CameraUpdateFactory.newCameraPosition(p),force?500:700);}
 private boolean tap(LatLng ll){if(map==null)return false;PointF p=map.getProjection().toScreenLocation(ll);List<Feature> fs=map.queryRenderedFeatures(p,new String[]{"overture-place-label","overture-place-dot","poi-important-label","poi-label","poi-important-dot","poi-dot"});if(fs.isEmpty())return false;Feature f=fs.get(0);PlaceInfo info=featurePlace(f,ll);if(info==null)return false;workers.execute(()->{try{PlaceInfo local=index.nearest(info.lat,info.lon,info.name);if(local!=null){if(info.address.isEmpty())info.address=local.address;if(info.phone.isEmpty())info.phone=local.phone;if(info.website.isEmpty())info.website=local.website;if(info.openingHours.isEmpty())info.openingHours=local.openingHours;}}catch(Exception ignored){}main.post(()->{if(placeListener!=null)placeListener.onPlace(info);});});return true;}
 private PlaceInfo featurePlace(Feature f,LatLng tap){
  try{double la=tap.getLatitude(),lo=tap.getLongitude();if(f.geometry() instanceof Point){Point q=(Point)f.geometry();la=q.latitude();lo=q.longitude();}String name=prop(f,"@name");if(name.isEmpty())name=prop(f,"name");String cat=prop(f,"basic_category");if(cat.isEmpty())cat=prop(f,"kind");if(name.isEmpty()&&cat.isEmpty())return null;PlaceInfo p=new PlaceInfo(name.isEmpty()?cat.replace('_',' '):name,cat,la,lo);p.website=firstJson(prop(f,"websites"));p.phone=firstJson(prop(f,"phones"));p.address=addressJson(prop(f,"addresses"));p.source=f.hasProperty("@name")?"Overture Maps":"OpenStreetMap";return p;}catch(Exception e){return null;}
 }
 private String prop(Feature f,String k){try{return f.hasProperty(k)&&!f.getProperty(k).isJsonNull()?f.getProperty(k).getAsString():"";}catch(Exception e){return "";}}
 private String firstJson(String s){if(s==null)return "";String x=s.trim();int q=x.indexOf('"'),q2=q<0?-1:x.indexOf('"',q+1);return q>=0&&q2>q?x.substring(q+1,q2):x.replace("[","").replace("]","").replace("\"","");}
 private String addressJson(String s){if(s==null||s.isEmpty())return "";String[] keys={"freeform","locality","postcode"};ArrayList<String> parts=new ArrayList<>();for(String k:keys){String needle="\""+k+"\"";int i=s.indexOf(needle);if(i>=0){int c=s.indexOf(':',i+needle.length()),q=c<0?-1:s.indexOf('"',c),e=q<0?-1:s.indexOf('"',q+1);if(q>=0&&e>q){String v=s.substring(q+1,e);if(!v.isEmpty()&&!parts.contains(v))parts.add(v);}}}return android.text.TextUtils.join(", ",parts);}
 public void search(String query,SearchCallback cb){ConvoyService s=ConvoyService.current;double a=Double.NaN,o=Double.NaN;if(s!=null&&s.fix!=null){a=s.fix.getLatitude();o=s.fix.getLongitude();}final double fa=a,fo=o;workers.execute(()->{try{ArrayList<PlaceInfo> r=index.search(query,fa,fo);main.post(()->cb.onResults(r));}catch(Exception e){main.post(()->cb.onResults(new ArrayList<PlaceInfo>()));}});}
 public void searchCategory(String category,SearchCallback cb){search(category,cb);}
 public interface SearchCallback{void onResults(ArrayList<PlaceInfo> places);}
}

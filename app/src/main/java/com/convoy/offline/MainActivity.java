package com.convoy.offline;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
 private static final int BG=0xFF0D1520, CARD=0xFF182433, GREEN=0xFF438AFF,
         TEXT=0xFFF1F5FA, MUTED=0xFFA6B5C7, AMBER=0xFFFFBC52;
 private LinearLayout toolbar;private TextView toolbarTitle;private Button toolbarBack;private ConvoyOnboarding onboarding;private OfflineMapsCoordinator offlineMaps;
 private LinearLayout root,body,mapPanel,crewPanel,mapControls,searchOverlay,routeCard,connectionBranding;
 private FrameLayout mapFrame;
 private RichMap map;
 private DrivingInstruments instruments;
 private View navigationBanner;
 private ImageView directionIcon;
 private AlertDialog instrumentDialog;
 private HorizontalScrollView categoryScroll;
 private TextView status,detail,gps,voice,crewSummary,navPrimary,navSecondary,routeSummary,routeTitle;
 private Button trip,ptt,vox,routeAction,instrumentButton;
 private LinearLayout voiceBar;
 private TextView homeGps,homeMap,homeConvoy,homeRoute,mapImportStatus,attributionLabel;private Button coverageDownload;
 private long mapImportBytes;
 private final NavigationState navigation=new NavigationState();
 private CurrentLocation location;
 private RouteLibrary routeLibrary;
 private MapPackStore mapPacks;
 private final ExecutorService storageWorker=Executors.newSingleThreadExecutor();
 private List<RouteLibrary.Entry> savedRoutes=new ArrayList<>();
 private List<MapPack> installedMaps=new ArrayList<>();
 private GpxRoute activeGpx,detailGpx;
 private String activeGpxId="",detailRouteId="",pendingMapRegion="custom",pendingMapReplacement=null;
 private boolean followingGpx=false,gpxImporting=false,resumed=false,storageReady=false;
 private long mapStorageBytes=0;
 private android.window.OnBackInvokedCallback backCallback;
 private final ArrayList<Button> tabButtons=new ArrayList<>();
 private ArrayList<String> crewSnapshot=new ArrayList<>();
 private ConvoyService crewSnapshotService;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private int tab=NavigationState.HOME,mapLayoutSignature=-1,directionResource=0;
 private Runnable permissionAction;
 private Runnable voicePermissionAction;
 private boolean homeActiveShown=false;private boolean importing=false,hadTrip=false,compactMap=false,showInstruments=false,pttTouchClick=false,pttPhysicalDown=false;

 private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
 private GradientDrawable shape(int color,int radius){
  GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
 }
 private TextView text(String value,int size,int color){
  TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);
  t.setPadding(0,dp(2),0,dp(2));return t;
 }
 private void updateText(TextView view,CharSequence value){
  if(!TextUtils.equals(view.getText(),value))view.setText(value);
 }
 private void updateDescription(View view,CharSequence value){
  if(!TextUtils.equals(view.getContentDescription(),value))view.setContentDescription(value);
 }
 private Button button(String label,boolean primary){
  Button b=new Button(this);b.setText(label);b.setContentDescription(label);b.setTextSize(13);
  b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:TEXT);b.setTypeface(null,Typeface.BOLD);
  b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x33FFFFFF:0x221764E8),shape(primary?GREEN:CARD,18),null));
  b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinimumWidth(0);b.setMinWidth(0);
  b.setPadding(dp(12),dp(4),dp(12),dp(4));b.setElevation(dp(2));return b;
 }
 private Button iconButton(int icon,String description){
  Button b=button("",false);android.graphics.drawable.Drawable d=getDrawable(icon);
  if(d!=null){d=d.mutate();d.setTint(TEXT);d.setBounds(0,0,dp(24),dp(24));b.setCompoundDrawables(d,null,null,null);}
  b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setContentDescription(description);return b;
 }
 private void spaced(LinearLayout parent,View child,int height){
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));
  p.bottomMargin=dp(12);parent.addView(child,p);
 }
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
          View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
  location=CurrentLocation.get(this);routeLibrary=new RouteLibrary(getFilesDir());mapPacks=new MapPackStore(getFilesDir());
  if(b!=null){navigation.restore(b.getInt("screen",NavigationState.HOME),b.getInt("detailParent",NavigationState.ROUTES));tab=navigation.screen();detailRouteId=b.getString("detailRouteId","");}
  onboarding=new ConvoyOnboarding(this,new ConvoyOnboarding.Host(){public void startHost(String name,String car){startConsumerHost(name,car);}public void join(ConvoyInvite invite,String name,String car){joinConsumer(invite,name,car);}public void advanced(){advancedSetup();}public void crew(){openScreen(NavigationState.CREW);}public void changed(){if(!isDestroyed()&&!isFinishing())renderTab();}});
  offlineMaps=new OfflineMapsCoordinator(this,mapPacks,location,new OfflineMapsCoordinator.Host(){public void importCustom(){pickMap("custom",null);}public void selectPack(MapPack pack){selectMap(pack);}public void removePack(MapPack pack){removeMap(pack);}public void replacePack(MapPack pack){pickMap(pack.regionId,pack.id);}public void developerMaps(){MainActivity.this.developerMaps();}public void installedChanged(){map.refreshInstalledMaps();refreshStoredData();}});
  build();refreshStoredData();consumeInvite(getIntent());
  if(Build.VERSION.SDK_INT>=33){backCallback=this::handleBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
 }
 private void build(){
  root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);setContentView(root);
  root.setOnApplyWindowInsetsListener((v,in)->{
   if(Build.VERSION.SDK_INT>=30){
    android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
    v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
   }else v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());
   return in;
  });
  root.requestApplyInsets();
  LinearLayout head=new LinearLayout(this);toolbar=head;head.setGravity(Gravity.CENTER_VERTICAL);
  head.setPadding(dp(16),dp(6),dp(12),dp(6));head.setBackgroundColor(CARD);
  toolbarBack=button("←",false);toolbarBack.setContentDescription("Back to Home");toolbarBack.setTextSize(22);toolbarBack.setElevation(0);head.addView(toolbarBack,new LinearLayout.LayoutParams(dp(48),dp(44)));toolbarBack.setOnClickListener(v->handleBack());
  LinearLayout branding=new LinearLayout(this);connectionBranding=branding;branding.setOrientation(LinearLayout.VERTICAL);
  TextView title=text("convoy",23,TEXT);toolbarTitle=title;title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
  branding.addView(title);status=text("Offline. Together.",11,MUTED);status.setSingleLine(true);
  status.setEllipsize(TextUtils.TruncateAt.END);branding.addView(status);
  head.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
  detail=text("No mobile signal needed. Set up together before driving.",12,MUTED);
  branding.setContentDescription("Convoy connection status. Tap for trip details");
  branding.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(status.getText()).setMessage(detail.getText())
          .setPositiveButton("Crew",(d,w)->openScreen(NavigationState.CREW)).setNegativeButton("Close",null).show());
  trip=button("Start Convoy",true);head.addView(trip,new LinearLayout.LayoutParams(dp(112),dp(48)));trip.setOnClickListener(v->onboarding.start());
  root.addView(head,new LinearLayout.LayoutParams(-1,dp(64)));
  body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
  root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
  map=new RichMap(this);map.setWaypoints(TripPlan.load(this));map.setNavigationMode(true);
  map.setOnPlaceSelectedListener(this::placeSheet);buildMapPanel();buildVoiceBar();buildTabs();renderTab();
 }
 private void updateToolbar(){boolean home=tab==NavigationState.HOME;ConvoyService s=ConvoyService.current;boolean active=s!=null&&s.active;toolbarBack.setVisibility(home?View.GONE:View.VISIBLE);String title=home?"convoy":tab==NavigationState.MAP?"Map":tab==NavigationState.ROUTES?"Routes":tab==NavigationState.CREW?"Crew":tab==NavigationState.OFFLINE_MAPS?"Offline Maps":tab==NavigationState.GPX_DETAIL?"GPX route":"Guide";updateText(toolbarTitle,title);toolbarTitle.setTextSize(home?25:18);toolbar.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(home?72:52)));status.setVisibility(home||active?View.VISIBLE:View.GONE);if(active)updateText(status,s.sessionTitle()+" • "+s.driverCount()+" drivers");trip.setVisibility(View.GONE);}
 private void startConsumerHost(String name,String car){if(ConvoyService.current!=null)return;String preference=getPreferences(0).getString("networkMode","auto");android.net.ConnectivityManager cm=getSystemService(android.net.ConnectivityManager.class);boolean wifi=false;for(android.net.Network n:cm.getAllNetworks()){android.net.NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c!=null&&c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)){wifi=true;break;}}boolean hotspot="hotspot".equals(preference)||("auto".equals(preference)&&!wifi);permissions(false,hotspot,()->{startForegroundService(new Intent(this,ConvoyService.class).putExtra("consumer",true).putExtra("host",true).putExtra("name",name).putExtra("car",car).putExtra("hotspot",hotspot));onboarding.showInvite();});}
 private void joinConsumer(ConvoyInvite invite,String name,String car){boolean bt=invite.network.mode==ConvoyInvite.Mode.BLUETOOTH,hotspot=invite.network.mode==ConvoyInvite.Mode.HOTSPOT;permissions(bt,hotspot,()->{startForegroundService(new Intent(this,ConvoyService.class).putExtra("consumer",true).putExtra("host",false).putExtra("name",name).putExtra("car",car).putExtra("bluetooth",bt).putExtra("invitation",invite.token()));openScreen(NavigationState.CREW);});}
 private void networkPreference(){String[] names={"Automatic • existing Wi-Fi, otherwise local hotspot","Existing Wi-Fi / travel router / vehicle mesh","Create a local hotspot"},keys={"auto","lan","hotspot"};String saved=getPreferences(0).getString("networkMode","auto");int checked=Arrays.asList(keys).indexOf(saved);new AlertDialog.Builder(this).setTitle("Convoy network preference").setSingleChoiceItems(names,Math.max(0,checked),(d,w)->{getPreferences(0).edit().putString("networkMode",keys[w]).apply();d.dismiss();}).setNegativeButton("Close",null).show();}
 private void diagnostics(){ConvoyService s=ConvoyService.current;new AlertDialog.Builder(this).setTitle("Connection diagnostics").setMessage(s==null?"No active convoy. Advanced manual Wi-Fi and paired Bluetooth remain available for compatibility.":s.connectionDiagnostics()).setPositiveButton("Close",null).setNeutralButton("Advanced",(d,w)->{if(ConvoyService.current==null)advancedSetup();else alert("End this convoy before changing connection mode.");}).show();}
 private void developerMaps(){try{for(MapPack pack:mapPacks.list())if(pack.bundled){MapPack selected=pack;new AlertDialog.Builder(this).setTitle("Developer / Test Maps").setMessage("Shrewsbury map, search and routing are retained for regression testing.").setPositiveButton("Open Shrewsbury",(d,w)->selectMap(selected)).setNegativeButton("Close",null).show();break;}}catch(IOException e){alert("Could not read development map.");}}
 private void consumeInvite(Intent intent){Uri link=intent==null?null:intent.getData();if(link==null)return;String value=link.toString();setIntent(new Intent(this,MainActivity.class));handler.post(()->onboarding.acceptLink(value));}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);consumeInvite(intent);}
 private void buildMapPanel(){
  mapPanel=new LinearLayout(this);mapPanel.setOrientation(LinearLayout.VERTICAL);
  mapFrame=new FrameLayout(this);mapFrame.setBackgroundColor(0xFFE2EDE7);
  mapFrame.addView(map,new FrameLayout.LayoutParams(-1,-1));
  mapPanel.addView(mapFrame,new LinearLayout.LayoutParams(-1,-1));

  searchOverlay=new LinearLayout(this);searchOverlay.setOrientation(LinearLayout.VERTICAL);
  LinearLayout searchRow=new LinearLayout(this);searchRow.setGravity(Gravity.CENTER_VERTICAL);
  Button search=button("Search offline places",false);search.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
  search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search,0,0,0);search.setCompoundDrawablePadding(dp(10));
  for(android.graphics.drawable.Drawable icon:search.getCompoundDrawables())if(icon!=null)icon.mutate().setTint(MUTED);
  search.setSingleLine(true);search.setEllipsize(TextUtils.TruncateAt.END);
  search.setContentDescription("Search places stored on this phone");search.setOnClickListener(v->searchPlaces());
  LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(0,dp(52),1);searchParams.rightMargin=dp(8);
  searchRow.addView(search,searchParams);
  Button importMap=iconButton(R.drawable.ic_map,"View or import an offline map pack");
  importMap.setOnClickListener(v->openScreen(NavigationState.OFFLINE_MAPS));searchRow.addView(importMap,new LinearLayout.LayoutParams(dp(48),dp(52)));
  searchOverlay.addView(searchRow);
  categoryScroll=new HorizontalScrollView(this);categoryScroll.setHorizontalScrollBarEnabled(false);
  categoryScroll.setClipToPadding(false);categoryScroll.setPadding(0,dp(8),0,dp(4));
  LinearLayout quick=new LinearLayout(this);quick.setGravity(Gravity.CENTER_VERTICAL);
  String[][] quickItems={{"Fuel","fuel"},{"Coffee","cafe"},{"Food","restaurant"},{"Parking","parking"},{"Shops","supermarket"},{"EV charging","charging_station"}};
  for(String[] q:quickItems){
   Button category=button(q[0],false);category.setTextSize(12);category.setContentDescription("Find nearby "+q[0]+" offline");
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(48));lp.rightMargin=dp(7);quick.addView(category,lp);
   category.setOnClickListener(v->map.searchCategory(q[1],this::showSearchResults));
  }
  categoryScroll.addView(quick);searchOverlay.addView(categoryScroll);
  FrameLayout.LayoutParams searchPlacement=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
  searchPlacement.setMargins(dp(12),dp(12),dp(12),0);mapFrame.addView(searchOverlay,searchPlacement);

  LinearLayout banner=new LinearLayout(this);banner.setGravity(Gravity.CENTER_VERTICAL);
  banner.setPadding(dp(14),dp(8),dp(14),dp(8));banner.setBackground(shape(GREEN,20));banner.setElevation(dp(4));
  ImageView direction=new ImageView(this);directionIcon=direction;direction.setImageResource(R.drawable.ic_direction);
  direction.setColorFilter(Color.WHITE);direction.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  LinearLayout.LayoutParams directionParams=new LinearLayout.LayoutParams(dp(32),dp(32));directionParams.rightMargin=dp(12);
  banner.addView(direction,directionParams);
  LinearLayout instructions=new LinearLayout(this);instructions.setOrientation(LinearLayout.VERTICAL);
  navPrimary=text("Offline routing",12,0xFFE4EDFF);navPrimary.setSingleLine(true);navPrimary.setEllipsize(TextUtils.TruncateAt.END);
  navSecondary=text("Choose a destination",18,Color.WHITE);navSecondary.setTypeface(null,Typeface.BOLD);
  navSecondary.setMaxLines(2);navSecondary.setEllipsize(TextUtils.TruncateAt.END);
  instructions.addView(navPrimary);instructions.addView(navSecondary);banner.addView(instructions,new LinearLayout.LayoutParams(0,-2,1));
  navigationBanner=banner;FrameLayout.LayoutParams bannerPlacement=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
  bannerPlacement.setMargins(dp(12),dp(126),dp(12),0);mapFrame.addView(banner,bannerPlacement);
  navigationBanner.setVisibility(View.GONE);

  mapControls=new LinearLayout(this);mapControls.setOrientation(LinearLayout.VERTICAL);mapControls.setGravity(Gravity.END);
  Button center=iconButton(R.drawable.ic_location,"Recenter map on your current location");
  center.setOnClickListener(v->{requestMapLocation(true);map.recenter();});addMapControl(center);
  Button mode=button("Drive",false);mode.setContentDescription("Heading-up driving view. Tap for north-up map");
  mode.setOnClickListener(v->{
   map.setNavigationMode(!map.isNavigationMode());mode.setText(map.isNavigationMode()?"Drive":"North");
   mode.setContentDescription(map.isNavigationMode()?"Heading-up driving view. Tap for north-up map":"North-up map. Tap for heading-up driving view");map.recenter();
  });addMapControl(mode);
  instrumentButton=button("Gauges",false);instrumentButton.setContentDescription("Show speedometer, G-force, pitch and roll gauges");
  instrumentButton.setOnClickListener(v->toggleInstruments());addMapControl(instrumentButton);
  LinearLayout zoom=new LinearLayout(this);
  Button minus=button("−",false),plus=button("+",false);minus.setTextSize(22);plus.setTextSize(22);
  minus.setContentDescription("Zoom out");plus.setContentDescription("Zoom in");
  minus.setOnClickListener(v->map.changeZoom(-1));plus.setOnClickListener(v->map.changeZoom(1));
  zoom.addView(minus,new LinearLayout.LayoutParams(dp(48),dp(48)));
  LinearLayout.LayoutParams plusParams=new LinearLayout.LayoutParams(dp(48),dp(48));plusParams.leftMargin=dp(6);zoom.addView(plus,plusParams);
  // The zoom pair needs its full 102 dp width; the vertical rail's default MATCH_PARENT
  // child width would otherwise constrain it to a single 72 dp control and clip zoom-in.
  mapControls.addView(zoom,new LinearLayout.LayoutParams(-2,dp(48)));
  FrameLayout.LayoutParams controlsPlacement=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
  controlsPlacement.setMargins(dp(12),0,dp(12),dp(108));mapFrame.addView(mapControls,controlsPlacement);

  instruments=new DrivingInstruments(this);instruments.setContentDescription("Driving instruments. GPS speed in miles per hour, G-force, pitch and roll. Tap the lower gauge to calibrate pitch and roll.");
  FrameLayout.LayoutParams instrumentPlacement=new FrameLayout.LayoutParams(dp(108),dp(250),Gravity.BOTTOM|Gravity.START);
  instrumentPlacement.setMargins(dp(6),0,0,dp(108));mapFrame.addView(instruments,instrumentPlacement);instruments.setVisibility(View.GONE);

  routeCard=new LinearLayout(this);routeCard.setOrientation(LinearLayout.VERTICAL);
  routeCard.setPadding(dp(14),dp(7),dp(10),dp(7));routeCard.setBackground(shape(CARD,22));routeCard.setElevation(dp(5));
  LinearLayout routeRow=new LinearLayout(this);routeRow.setGravity(Gravity.CENTER_VERTICAL);
  LinearLayout routeCopy=new LinearLayout(this);routeCopy.setOrientation(LinearLayout.VERTICAL);
  routeTitle=text("Ready for the road",20,TEXT);routeTitle.setTypeface(null,Typeface.BOLD);routeTitle.setSingleLine(true);
  routeTitle.setEllipsize(TextUtils.TruncateAt.END);routeCopy.addView(routeTitle);
  routeSummary=text("Choose a place or plan your next stop",12,MUTED);routeSummary.setMaxLines(3);
  routeSummary.setEllipsize(TextUtils.TruncateAt.END);routeCopy.addView(routeSummary);
  routeRow.addView(routeCopy,new LinearLayout.LayoutParams(0,-2,1));
  routeAction=button("Plan",false);routeAction.setTextColor(GREEN);routeAction.setElevation(0);routeAction.setSingleLine(true);
  routeRow.addView(routeAction,new LinearLayout.LayoutParams(dp(80),dp(48)));
  routeAction.setOnClickListener(v->{
   if(activeGpx!=null&&!map.hasManualTarget()){showRouteDetails(activeGpxId);return;}
   if(map.hasManualTarget())new AlertDialog.Builder(this).setTitle("Stop navigating here?")
           .setMessage("Your saved trip plan will stay available.").setNegativeButton("Continue",null)
           .setPositiveButton("Stop route",(d,w)->{map.clearManualTarget();updateNavigationBanner();}).show();
   else openScreen(NavigationState.ROUTES);
  });routeCard.addView(routeRow);
  LinearLayout mapStatus=new LinearLayout(this);mapStatus.setGravity(Gravity.CENTER_VERTICAL);
  gps=text("Location permission required",10,MUTED);gps.setSingleLine(true);gps.setEllipsize(TextUtils.TruncateAt.END);
  crewSummary=text("Offline map",10,GREEN);crewSummary.setGravity(Gravity.END);crewSummary.setSingleLine(true);
  crewSummary.setEllipsize(TextUtils.TruncateAt.END);mapStatus.addView(gps,new LinearLayout.LayoutParams(0,-2,1));
  mapStatus.addView(crewSummary,new LinearLayout.LayoutParams(0,-2,1));routeCard.addView(mapStatus);crewSummary.setOnClickListener(v->openScreen(NavigationState.OFFLINE_MAPS));
  attributionLabel=text("© OpenStreetMap · Protomaps",8,0xFF213344);attributionLabel.setBackgroundColor(0xCCFFFFFF);attributionLabel.setGravity(Gravity.CENTER);attributionLabel.setContentDescription("Open map attribution");attributionLabel.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Map sources").setMessage(map.attribution+"\n\nOpenStreetMap data: openstreetmap.org/copyright\nProtomaps: docs.protomaps.com/basemaps\nNatural Earth public domain\nShrewsbury additional places: Overture Maps").setPositiveButton("Close",null).show());FrameLayout.LayoutParams credit=new FrameLayout.LayoutParams(-1,dp(18),Gravity.BOTTOM);mapFrame.addView(attributionLabel,credit);
  coverageDownload=button("Download region map",false);coverageDownload.setTextSize(12);coverageDownload.setVisibility(View.GONE);coverageDownload.setOnClickListener(v->openScreen(NavigationState.OFFLINE_MAPS));FrameLayout.LayoutParams coveragePosition=new FrameLayout.LayoutParams(-2,dp(48),Gravity.BOTTOM|Gravity.START);coveragePosition.setMargins(dp(12),0,dp(96),dp(140));mapFrame.addView(coverageDownload,coveragePosition);
  FrameLayout.LayoutParams routePlacement=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
  routePlacement.setMargins(dp(12),0,dp(12),dp(24));mapFrame.addView(routeCard,routePlacement);
  mapFrame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot||r-l!=or-ol){compactMap=b-t<dp(410);mapLayoutSignature=-1;applyMapLayout();}});
 }
 private void addMapControl(Button b){
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(72),dp(48));p.bottomMargin=dp(6);mapControls.addView(b,p);
 }
 private void applyMapLayout(){
  boolean routing=map.getTargetName()!=null&&!map.getTargetName().isEmpty();
  int signature=(compactMap?1:0)|(routing?2:0)|(showInstruments?4:0);
  if(signature==mapLayoutSignature)return;mapLayoutSignature=signature;
  categoryScroll.setVisibility(compactMap?View.GONE:View.VISIBLE);
  searchOverlay.setVisibility((compactMap&&routing)||showInstruments?View.GONE:View.VISIBLE);
  FrameLayout.LayoutParams bannerParams=(FrameLayout.LayoutParams)navigationBanner.getLayoutParams();
  bannerParams.topMargin=dp(compactMap||showInstruments?12:126);navigationBanner.setLayoutParams(bannerParams);
  mapControls.setOrientation(compactMap?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
  for(int i=0;i<mapControls.getChildCount()-1;i++){
   LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)mapControls.getChildAt(i).getLayoutParams();
   p.width=dp(compactMap?56:72);p.bottomMargin=dp(compactMap?0:6);p.rightMargin=dp(compactMap?6:0);
   mapControls.getChildAt(i).setPadding(dp(compactMap?4:12),dp(4),dp(compactMap?4:12),dp(4));
   mapControls.getChildAt(i).setLayoutParams(p);
  }
  updateInstrumentVisibility();
 }
 private void toggleInstruments(){
  if(compactMap){
   if(instrumentDialog!=null&&instrumentDialog.isShowing()){instrumentDialog.dismiss();return;}
   mapFrame.removeView(instruments);
   FrameLayout dashboard=new FrameLayout(this);dashboard.setPadding(dp(24),dp(8),dp(24),dp(8));
   dashboard.setBackgroundColor(BG);instruments.setVisibility(View.VISIBLE);
   ScrollView dashboardScroll=new ScrollView(this);dashboardScroll.addView(dashboard);
   dashboard.addView(instruments,new FrameLayout.LayoutParams(dp(180),dp(340),Gravity.CENTER));
   instrumentDialog=new AlertDialog.Builder(this).setTitle("Driving instruments")
           .setMessage("GPS speed · G-force · pitch and roll. Tap the lower gauge to zero tilt while mounted and parked.")
           .setView(dashboardScroll).setPositiveButton("Back to map",null).create();
   instrumentDialog.setOnDismissListener(d->{
    dashboard.removeView(instruments);mapFrame.addView(instruments,new FrameLayout.LayoutParams(dp(108),dp(250),Gravity.BOTTOM|Gravity.START));
    showInstruments=false;mapLayoutSignature=-1;applyMapLayout();instrumentDialog=null;
   });instrumentDialog.show();return;
  }
  showInstruments=!showInstruments;applyMapLayout();
 }
 private void updateInstrumentVisibility(){
  if(instrumentDialog!=null&&instrumentDialog.isShowing())return;
  instruments.setVisibility(showInstruments?View.VISIBLE:View.GONE);
  instrumentButton.setText(showInstruments?"Hide":"Gauges");
  instrumentButton.setContentDescription(showInstruments?"Hide driving instruments":"Show speedometer, G-force, pitch and roll gauges");
  FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)instruments.getLayoutParams();
  boolean routing=map.getTargetName()!=null&&!map.getTargetName().isEmpty();
  p.height=Math.min(dp(250),Math.max(dp(190),mapFrame.getHeight()-dp(routing?204:120)));
  p.bottomMargin=dp(108);p.leftMargin=dp(6);instruments.setLayoutParams(p);
 }
 private void buildVoiceBar(){
  LinearLayout audio=new LinearLayout(this);voiceBar=audio;audio.setGravity(Gravity.CENTER_VERTICAL);audio.setBackgroundColor(CARD);
  audio.setPadding(dp(12),dp(6),dp(12),dp(6));
  LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);
  voice=text("Local voice · start a trip",10,MUTED);voice.setSingleLine(true);voice.setEllipsize(TextUtils.TruncateAt.END);
  ptt=button("Hold to talk",true);ptt.setContentDescription("Hold to transmit voice. Accessibility click toggles talking on or off.");
  copy.addView(voice);copy.addView(ptt,new LinearLayout.LayoutParams(-1,dp(48)));
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(8);audio.addView(copy,p);
  vox=button("Hands-free\noff",false);vox.setTextSize(12);vox.setTextColor(GREEN);
  audio.addView(vox,new LinearLayout.LayoutParams(dp(108),dp(60)));root.addView(audio,new LinearLayout.LayoutParams(-1,-2));
  ptt.setOnClickListener(v->{
   if(pttTouchClick){pttTouchClick=false;return;}
   ConvoyService s=ConvoyService.current;if(s==null||s.connectionCount()==0)return;if(!ensureVoice(s))return;
   s.ptt=!s.ptt;ptt.setText(s.ptt?"Tap to finish talking":"Hold to talk");
  });
  ptt.setOnTouchListener((v,event)->{
   ConvoyService s=ConvoyService.current;if(s==null||!ptt.isEnabled())return false;if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&!ensureVoice(s))return true;
   switch(event.getActionMasked()){
    case MotionEvent.ACTION_DOWN:pttPhysicalDown=true;s.ptt=true;ptt.setText("Release to listen");return true;
    case MotionEvent.ACTION_UP:pttPhysicalDown=false;s.ptt=false;ptt.setText("Hold to talk");pttTouchClick=true;v.performClick();return true;
    case MotionEvent.ACTION_CANCEL:pttPhysicalDown=false;s.ptt=false;ptt.setText("Hold to talk");return true;
    default:return true;
   }
  });
  vox.setOnClickListener(v->{
   ConvoyService s=ConvoyService.current;if(s==null){setup();return;}if(s.vox){s.vox=false;return;}if(!ensureVoice(s))return;
   new AlertDialog.Builder(this).setTitle("Hands-free voice")
           .setMessage("The microphone transmits when it detects loud speech. Road noise can trigger it. One person speaks at a time; received speech temporarily pauses your microphone transmission.\n\nUse the phone or car audio at a moderate volume. Set this up while parked, then test with a passenger. Headset microphones and car Bluetooth routing vary by device.")
           .setNegativeButton("Cancel",null).setPositiveButton("Enable",(d,w)->s.vox=true).show();
  });
 }
 private void buildTabs(){
  LinearLayout tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER_VERTICAL);tabs.setBackgroundColor(CARD);
  tabs.setPadding(dp(6),dp(4),dp(6),dp(4));String[] labels={"Home","Map","Routes","Crew","Guide"};
  int[] icons={R.drawable.ic_location,R.drawable.ic_map,R.drawable.ic_route,R.drawable.ic_crew,R.drawable.ic_info};
  int[] destinations={NavigationState.HOME,NavigationState.MAP,NavigationState.ROUTES,NavigationState.CREW,NavigationState.GUIDE};
  for(int i=0;i<labels.length;i++){
   final int selectedTab=destinations[i];Button b=button(labels[i],false);b.setTextSize(11);b.setElevation(0);b.setPadding(dp(4),dp(4),dp(4),dp(4));
   android.graphics.drawable.Drawable icon=getDrawable(icons[i]);if(icon!=null){icon.setBounds(0,0,dp(20),dp(20));b.setCompoundDrawables(null,icon,null,null);}
   b.setCompoundDrawablePadding(dp(2));b.setContentDescription(labels[i]+" tab");tabButtons.add(b);
   tabs.addView(b,new LinearLayout.LayoutParams(0,dp(56),1));b.setOnClickListener(v->openScreen(selectedTab));
  }
  root.addView(tabs,new LinearLayout.LayoutParams(-1,dp(64)));
 }
 private void renderTab(){
  if(instrumentDialog!=null&&instrumentDialog.isShowing())instrumentDialog.dismiss();
  for(int i=0;i<tabButtons.size();i++){
   Button b=tabButtons.get(i);boolean selected=i-1==tab||(i==2&&tab==NavigationState.GPX_DETAIL);b.setSelected(selected);
   b.setTextColor(selected?GREEN:MUTED);
   b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33438AFF),shape(selected?0xFF243D60:CARD,18),null));
   for(android.graphics.drawable.Drawable icon:b.getCompoundDrawables())if(icon!=null)icon.setTint(selected?GREEN:MUTED);
   b.setContentDescription(b.getText()+" tab"+(selected?", selected":""));
  }
  body.removeAllViews();mapImportStatus=null;crewPanel=null;homeGps=null;homeMap=null;homeRoute=null;homeConvoy=null;
  updateToolbar();updateLocationLease();
  updateVoiceVisibility();
  if(tab==NavigationState.MAP){body.addView(mapPanel,new LinearLayout.LayoutParams(-1,-1));requestMapLocation(false);return;}
  ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(18),dp(18),dp(16));scroll.addView(box);body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
  if(tab==NavigationState.HOME){renderHome(box);return;}
  if(tab==NavigationState.ROUTES){renderRoutes(box);renderPlan(box);return;}
  if(tab==NavigationState.OFFLINE_MAPS){offlineMaps.setImportProgress(importing,mapImportBytes);View maps=offlineMaps.build();if(maps.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)maps.getParent()).removeView(maps);box.setPadding(dp(12),dp(8),dp(12),dp(12));box.addView(maps);requestMapLocation(false);return;}
  if(tab==NavigationState.GPX_DETAIL){renderRouteDetails(box);return;}
  if(tab==NavigationState.CREW){crewPanel=box;crewSnapshot.clear();crewSnapshotService=null;updateCrew();return;}
  addSection(box,"Map without a convoy","Open Map for your own GPS position and selected car. Location stays on this phone. No hotspot, Bluetooth connection, microphone or crew sharing starts until you explicitly start a convoy.");
  Button profile=button("Profile",false);spaced(box,profile,52);profile.setOnClickListener(v->onboarding.editProfile());
  Button maps=button("Offline Maps",false);spaced(box,maps,52);maps.setOnClickListener(v->openScreen(NavigationState.OFFLINE_MAPS));
  Button diagnostics=button("Connection diagnostics",false);spaced(box,diagnostics,52);diagnostics.setOnClickListener(v->diagnostics());
  Button network=button("Convoy network preference",false);spaced(box,network,52);network.setOnClickListener(v->networkPreference());
  if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0){Button developer=button("Developer / Test Maps",false);spaced(box,developer,52);developer.setOnClickListener(v->developerMaps());}
  addSection(box,"Bring your crew together","Save your profile once. Start Convoy creates a fresh private session. Friends choose Join Convoy and scan the QR or open a shared invite. Android can ask to connect to the host's local Wi-Fi; keep the network even if it has no Internet. On an existing router or vehicle mesh, local discovery finds the host automatically.");
  addSection(box,"Trip plan & POIs","Use Plan to save fuel, food, rest, viewpoint, meet-up, hazard and custom stops. Planned stops are stored on this phone and appear on the map. The thin dashed line shows your stop sequence; a thick blue line shows the calculated offline driving route to your next unvisited stop. Share a plan before departure and paste/import it on the other phones.");
  addSection(box,"Wi-Fi • recommended","Share an invitation only with your crew. A valid QR/link authorises a join; Nearby Convoys only finds hosts and never grants access. Stop accepting new drivers pauses admission while existing drivers remain connected. Ending the convoy invalidates its invitations.");
  addSection(box,"Bluetooth • alternative","Pair each friend’s phone with the host in Android Bluetooth settings first. Host starts Bluetooth mode; friends choose that paired host and enter the group key. Wi-Fi and Bluetooth modes are separate; there is no automatic radio switching.");
  addSection(box,"UK offline map packs","MapLibre reads local PMTiles. Offline Maps downloads verified Scotland, Northern England, Southern England, Wales and Northern Ireland regional packs. The small UK overview supplies low-zoom context. Advanced users can still import custom UK maps. Map tiles, search data and routing data are separate: importing a basemap does not install a road graph or search index.");
  addSection(box,"GPX routes","Import a GPX through Android Files from local storage or a connected document provider. Saved tracks retain their original geometry and segment breaks. Follow route shows approximate progress, remaining distance and distance off the track. GPX itself has no reliable turn instructions or travel-time estimates. Convoy stops remain a separate plan.");
  addSection(box,"Map controls","Search and the category chips work fully offline. Drive follows your heading; switch to North for a north-up map. Tap the location control to resume following after moving the map. Gauges opens the GPS speedometer and calibrated G-force, pitch and roll display; tap the lower gauge to zero tilt while mounted and parked.");
  addSection(box,"While moving","Let a passenger handle the screen. Enable hands-free before departure; hold-to-talk is for passengers or when parked. Voice is walkie-talkie style, one speaker at a time. Adjust media volume for received voice. Radio range can be short inside moving cars; stay within the connection’s tested range.");
  addSection(box,"Know what is live","Markers become stale after 15 seconds without a fresh GPS fix. Crew cards show age, accuracy and heartbeat. Convoy now calculates car routes fully offline inside the installed routing region; outside that region it will clearly say routing is unavailable. This is not emergency equipment.");
  addSection(box,"Privacy & stopping","No account, server, analytics or location history. Group traffic is encrypted with a shared key: only give it to your friends. Every group member can hear and see the group. Voice is not recorded to files. End trip in the app or notification to stop the service. Android may still stop it under power restrictions; test screen-off behaviour on your phones.");
  addSection(box,"Map information",map.mapName+"\n"+map.attribution+"\n\nConvoy "+appVersion()+" • offline navigation");
 }
 private void addSection(LinearLayout box,String title,String value){TextView h=text(title,17,TEXT);h.setTypeface(null,Typeface.BOLD);box.addView(h);TextView t=text(value,14,MUTED);t.setLineSpacing(dp(3),1);spaced(box,t,-2);}
 private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){return "";}}
 private void openScreen(int destination){navigation.open(destination);tab=destination;renderTab();}
 private void handleBack(){
  if(navigation.back()){tab=navigation.screen();renderTab();return;}
  // Keep an explicitly started convoy service running; normal Home Back backgrounds the task.
  if(!moveTaskToBack(true))finish();
 }
 @Override public void onBackPressed(){handleBack();}
 private void updateLocationLease(){
  if(location!=null)location.setForegroundClient(this,resumed&&(tab==NavigationState.MAP||tab==NavigationState.ROUTES||tab==NavigationState.GPX_DETAIL||tab==NavigationState.OFFLINE_MAPS));
 }
 private boolean hasLocationPermission(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
 private void requestMapLocation(boolean explicit){
  location.permissionChanged();
  if(hasLocationPermission())return;
  boolean asked=getPreferences(0).getBoolean("locationAsked",false);
  if(!explicit&&asked)return;
  if(explicit&&asked&&!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)&&!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)){
   new AlertDialog.Builder(this).setTitle("Location permission required")
    .setMessage("The offline map works without location. To show your own car and recenter, allow Location in Android app settings. No location is shared without a convoy.")
    .setNegativeButton("Keep browsing",null).setPositiveButton("App settings",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();return;
  }
  getPreferences(0).edit().putBoolean("locationAsked",true).apply();
  requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},6);
 }
 private void updateVoiceVisibility(){
  ConvoyService s=ConvoyService.current;
  if(voiceBar!=null)voiceBar.setVisibility(s!=null&&s.active&&tab==NavigationState.MAP?View.VISIBLE:View.GONE);
 }
 private LinearLayout panel(){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(shape(CARD,22));return card;}
 private void homeDestination(LinearLayout box,String title,String description,int destination,boolean primary){
  Button button=button(title,primary);button.setTextSize(18);button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
  button.setContentDescription("Open "+(destination==NavigationState.MAP?"map":destination==NavigationState.ROUTES?"routes":title));
  spaced(box,button,64);button.setOnClickListener(v->openScreen(destination));
  if(description!=null&&!description.isEmpty()){TextView subtitle=text(description,12,MUTED);subtitle.setPadding(dp(4),0,0,dp(10));box.addView(subtitle);}
 }
 private void renderHome(LinearLayout box){

  homeGps=text(location.snapshot().statusText(),13,GREEN);spaced(box,homeGps,-2);
  homeDestination(box,"Map","Your location and offline navigation",NavigationState.MAP,true);
  LinearLayout quick=new LinearLayout(this);Button routes=button("Routes",false),importGpx=button("Import GPX",false);
  routes.setContentDescription("Open routes");importGpx.setContentDescription("Import GPX");
  LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(56),1);half.rightMargin=dp(8);quick.addView(routes,half);quick.addView(importGpx,new LinearLayout.LayoutParams(0,dp(56),1));spaced(box,quick,56);
  routes.setOnClickListener(v->openScreen(NavigationState.ROUTES));importGpx.setOnClickListener(v->chooseGpx());
  if(activeGpx!=null){
   LinearLayout active=panel();active.addView(text("ACTIVE GPX",11,AMBER));homeRoute=text("",16,TEXT);homeRoute.setTypeface(null,Typeface.BOLD);active.addView(homeRoute);
   Button resume=button(followingGpx?"Resume route":"View route",false);active.addView(resume,new LinearLayout.LayoutParams(-1,dp(48)));
   resume.setOnClickListener(v->{openScreen(NavigationState.MAP);if(!followingGpx)map.focusGpxRoute();});spaced(box,active,-2);
  }else if(!TripPlan.load(this).isEmpty()){Button resume=button("Resume planned stops",false);spaced(box,resume,52);resume.setOnClickListener(v->openScreen(NavigationState.MAP));}
  ConvoyService s=ConvoyService.current;homeActiveShown=s!=null&&s.active;
  if(s==null||!s.active){LinearLayout convoyActions=new LinearLayout(this);Button start=button("Start Convoy",true),join=button("Join Convoy",false);LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(0,dp(56),1);c.rightMargin=dp(8);convoyActions.addView(start,c);convoyActions.addView(join,new LinearLayout.LayoutParams(0,dp(56),1));spaced(box,convoyActions,56);start.setOnClickListener(v->onboarding.start());join.setOnClickListener(v->onboarding.joinMenu());}
  else{Button crewActive=button("Open Crew",true);spaced(box,crewActive,52);crewActive.setOnClickListener(v->openScreen(NavigationState.CREW));if(s.host&&s.secureSession()){Button invite=button("Show Invite",false);spaced(box,invite,52);invite.setOnClickListener(v->onboarding.showInvite());}Button end=button("End Convoy",false);spaced(box,end,48);end.setOnClickListener(v->onboarding.end());}
  homeConvoy=text("",12,MUTED);spaced(box,homeConvoy,-2);
  LinearLayout more=new LinearLayout(this);Button crew=button("Crew",false),maps=button("Offline Maps",false);
  LinearLayout.LayoutParams moreHalf=new LinearLayout.LayoutParams(0,dp(52),1);moreHalf.rightMargin=dp(8);more.addView(crew,moreHalf);more.addView(maps,new LinearLayout.LayoutParams(0,dp(52),1));spaced(box,more,52);
  crew.setOnClickListener(v->openScreen(NavigationState.CREW));maps.setOnClickListener(v->openScreen(NavigationState.OFFLINE_MAPS));
  homeMap=text("",12,MUTED);spaced(box,homeMap,-2);
  Button guide=button("Guide / Settings",false);spaced(box,guide,52);guide.setOnClickListener(v->openScreen(NavigationState.GUIDE));updateHomeStatus();
 }
 private void updateHomeStatus(){
  if(homeGps!=null)updateText(homeGps,location.snapshot().statusText());
  if(homeMap!=null)updateText(homeMap,"Offline map • "+selectedMapName());
  ConvoyService s=ConvoyService.current;
  if(homeConvoy!=null)updateText(homeConvoy,s==null||!s.active?"Local mode • your location stays on this phone":s.sessionTitle()+" • "+(!s.host&&s.connectionCount()==0?"Connecting on local Wi-Fi…":s.driverCount()+" driver(s) connected"));
  if(homeRoute!=null&&activeGpx!=null){GpxRoute.Progress p=map.getGpxProgress();updateText(homeRoute,activeGpx.name+"\n"+(followingGpx?"Following GPX track":"Saved route ready")+(p==null?" • "+formatDistance((float)activeGpx.totalMeters):" • "+formatDistance((float)p.remainingMeters)+" remaining"));}
 }
 private String selectedMapName(){android.location.Location fix=location.fix();if(fix!=null)for(MapPack pack:installedMaps)if(!pack.bundled&&pack.covers(fix.getLatitude(),fix.getLongitude()))return pack.name;for(MapPack pack:installedMaps)if(pack.selected&&!pack.bundled)return pack.name;ArrayList<String> names=new ArrayList<>();for(MapPack pack:installedMaps)if(pack.installed&&!pack.bundled)names.add(pack.name);return names.isEmpty()?"UK region not downloaded":names.size()==1?names.get(0):names.size()+" installed map packs";}

 private void refreshStoredData(){
  storageWorker.execute(()->{try{
   mapPacks.migrateLegacy(getSharedPreferences("convoy",0).getString("mapName","Imported vector region"));
   List<RouteLibrary.Entry> routes=routeLibrary.list();String id=routeLibrary.activeId();GpxRoute active=routeLibrary.activeRoute();boolean follow=routeLibrary.isFollowing();
   List<MapPack> packs=mapPacks.list();long bytes=mapPacks.storageBytes();
   runOnUiThread(()->{if(isDestroyed()||isFinishing())return;savedRoutes=routes;activeGpxId=id==null?"":id;activeGpx=active;followingGpx=follow;installedMaps=packs;mapStorageBytes=bytes;storageReady=true;offlineMaps.refresh();map.setGpxRoute(active,follow);if(tab!=NavigationState.MAP)renderTab();else updateNavigationBanner();});
  }catch(Exception e){runOnUiThread(()->alert("Could not read saved offline data: "+e.getMessage()));}});
 }
 private String routeMetadata(RouteLibrary.Entry entry){return formatDistance((float)entry.totalMeters)+" • GPX • "+entry.segmentCount+" segment(s)";}
 private RouteLibrary.Entry routeEntry(String id){for(RouteLibrary.Entry entry:savedRoutes)if(entry.id.equals(id))return entry;return null;}
 private void renderRoutes(LinearLayout box){
  addSection(box,"My routes","Saved GPX tracks stay on this phone. Follow the exact imported line offline; your convoy stops remain separate below.");
  Button importRoute=button(gpxImporting?"Importing GPX…":"Import GPX",true);importRoute.setEnabled(!gpxImporting);spaced(box,importRoute,56);importRoute.setOnClickListener(v->chooseGpx());
  if(!storageReady)spaced(box,text("Loading saved routes…",13,MUTED),-2);
  else if(savedRoutes.isEmpty())spaced(box,text("No GPX routes yet. Import one from Files or a connected document provider.",14,MUTED),-2);
  for(RouteLibrary.Entry entry:savedRoutes){
   LinearLayout card=panel();TextView title=text(entry.name,18,entry.id.equals(activeGpxId)?AMBER:TEXT);title.setTypeface(null,Typeface.BOLD);card.addView(title);
   card.addView(text(routeMetadata(entry)+(entry.id.equals(activeGpxId)?followingGpx?" • Following":" • Active":""),12,MUTED));
   Button view=button("Open route",false);card.addView(view,new LinearLayout.LayoutParams(-1,dp(48)));view.setContentDescription("Open GPX route "+entry.name);view.setOnClickListener(v->showRouteDetails(entry.id));spaced(box,card,-2);
  }
 }
 private void showRouteDetails(String id){
  if(id==null||id.isEmpty())return;detailRouteId=id;detailGpx=null;openScreen(NavigationState.GPX_DETAIL);
  storageWorker.execute(()->{try{GpxRoute route=routeLibrary.load(id);runOnUiThread(()->{if(isDestroyed()||!id.equals(detailRouteId))return;detailGpx=route;if(tab==NavigationState.GPX_DETAIL)renderTab();});}catch(Exception e){runOnUiThread(()->alert("Could not open this route: "+e.getMessage()));}});
 }
 private void renderRouteDetails(LinearLayout box){
  RouteLibrary.Entry entry=routeEntry(detailRouteId);
  if(entry==null){addSection(box,"GPX route","This route is no longer saved.");return;}
  addSection(box,entry.name,routeMetadata(entry)+"\n"+entry.pointCount+" original points • "+entry.waypointCount+" waypoint(s)\nTrack geometry stays unchanged. No GPX turn directions are invented.");
  Button view=button("View on map",false),follow=button("Follow route",true);spaced(box,view,56);spaced(box,follow,56);
  view.setOnClickListener(v->activateRoute(entry.id,false,true));follow.setOnClickListener(v->activateRoute(entry.id,true,true));
  if(entry.id.equals(activeGpxId)&&followingGpx){
   GpxRoute.Progress p=map.getGpxProgress();addSection(box,"Following GPX track",p==null?"Waiting for a current location fix":String.format(Locale.UK,"%.0f%% approximate progress • %s travelled\n%s remaining • %s off GPX route",p.percent,formatDistance((float)p.alongMeters),formatDistance((float)p.remainingMeters),formatDistance((float)p.offRouteMeters)));
   Button rejoin=button("Rejoin route",false);spaced(box,rejoin,52);rejoin.setOnClickListener(v->{openScreen(NavigationState.MAP);String message=map.rejoinGpx();if(message!=null&&!message.isEmpty())alert(message);});
   Button stop=button("Stop following GPX",false);spaced(box,stop,52);stop.setOnClickListener(v->activateRoute(entry.id,false,false));
  }
  Button rename=button("Rename",false),share=button("Share GPX",false),delete=button("Delete route",false);spaced(box,rename,52);spaced(box,share,52);spaced(box,delete,52);
  rename.setOnClickListener(v->renameRoute(entry));share.setOnClickListener(v->shareGpx(entry));delete.setOnClickListener(v->deleteRoute(entry));
  if(detailGpx!=null&&!detailGpx.waypoints.isEmpty()){
   addSection(box,"GPX waypoints","Named points are separate from your convoy stops. Tap a waypoint for details or save it as a planned stop.");
   int count=0;for(GpxRoute.Waypoint waypoint:detailGpx.waypoints){if(count++>=100){box.addView(text("More waypoints remain visible on the map.",12,MUTED));break;}Button row=button(waypoint.name==null||waypoint.name.isEmpty()?"GPX waypoint":waypoint.name,false);spaced(box,row,52);row.setOnClickListener(v->{PlaceInfo place=new PlaceInfo(row.getText().toString(),"GPX waypoint",waypoint.lat,waypoint.lon);showPlaceDetails(place,(waypoint.description==null?"":waypoint.description)+"\n"+String.format(Locale.UK,"%.5f, %.5f",waypoint.lat,waypoint.lon));});}
  }
 }
 private void activateRoute(String id,boolean follow,boolean viewMap){
  storageWorker.execute(()->{try{routeLibrary.setActive(id,follow);GpxRoute route=routeLibrary.load(id);runOnUiThread(()->{if(isDestroyed())return;activeGpxId=id;activeGpx=route;followingGpx=follow;map.setGpxRoute(route,follow);if(viewMap){openScreen(NavigationState.MAP);if(follow)map.recenter();else map.focusGpxRoute();}else renderTab();updateNavigationBanner();});}catch(Exception e){runOnUiThread(()->alert("Could not activate this route: "+e.getMessage()));}});
 }
 private void renameRoute(RouteLibrary.Entry entry){
  EditText input=new EditText(this);input.setTextColor(TEXT);input.setSingleLine(true);input.setText(entry.name);input.setSelectAllOnFocus(true);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rename GPX route").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
  dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String name=input.getText().toString().trim();if(name.isEmpty()||name.length()>120){input.setError("Enter a name of 1–120 characters");return;}dialog.dismiss();storageWorker.execute(()->{try{routeLibrary.rename(entry.id,name);refreshStoredData();}catch(Exception e){runOnUiThread(()->alert("Could not rename this route: "+e.getMessage()));}});}));dialog.show();
 }
 private void deleteRoute(RouteLibrary.Entry entry){new AlertDialog.Builder(this).setTitle("Delete "+entry.name+"?").setMessage("This removes the saved GPX from this phone. Convoy stops are kept.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->storageWorker.execute(()->{try{routeLibrary.delete(entry.id);runOnUiThread(()->openScreen(NavigationState.ROUTES));refreshStoredData();}catch(Exception e){runOnUiThread(()->alert("Could not delete this route: "+e.getMessage()));}})).show();}
 private void shareGpx(RouteLibrary.Entry entry){
  Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".routes").appendPath("route").appendPath(entry.id).build();
  Intent send=new Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_SUBJECT,entry.name).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  send.setClipData(ClipData.newUri(getContentResolver(),entry.name,uri));try{startActivity(Intent.createChooser(send,"Share GPX route"));}catch(Exception e){alert("No app is available to share this GPX.");}
 }
 private void chooseGpx(){
  if(gpxImporting){alert("A GPX file is already being imported.");return;}
  Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
  intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));
  try{startActivityForResult(intent,10);}catch(Exception e){alert("No document picker is available on this device.");}
 }
 private void importGpx(Uri uri){
  gpxImporting=true;Toast.makeText(this,"Importing GPX…",Toast.LENGTH_SHORT).show();
  storageWorker.execute(()->{try{
   String name=documentName(uri,"Imported GPX route");RouteLibrary.Entry entry;
   try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("Could not read that document");entry=routeLibrary.importRoute(input,name);}
   List<RouteLibrary.Entry> routes=routeLibrary.list();GpxRoute route=routeLibrary.load(entry.id);
   runOnUiThread(()->{gpxImporting=false;if(isDestroyed())return;savedRoutes=routes;detailRouteId=entry.id;detailGpx=route;storageReady=true;openScreen(NavigationState.ROUTES);navigation.open(NavigationState.GPX_DETAIL);tab=NavigationState.GPX_DETAIL;renderTab();Toast.makeText(this,"GPX saved offline",Toast.LENGTH_LONG).show();});
  }catch(Exception e){runOnUiThread(()->{gpxImporting=false;if(isDestroyed()||isFinishing())return;alert("Could not import this GPX file. "+e.getMessage());if(tab==NavigationState.ROUTES)renderTab();});}});
 }
 private String documentName(Uri uri,String fallback){try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){String name=cursor.getString(0);if(name!=null&&!name.trim().isEmpty())return name;}}catch(Exception ignored){}return fallback;}
 private String formatBytes(long bytes){return bytes>=1024L*1024*1024?String.format(Locale.UK,"%.2f GB",bytes/(1024.0*1024*1024)):String.format(Locale.UK,"%.1f MB",bytes/(1024.0*1024));}
 private void renderOfflineMaps(LinearLayout box){
  addSection(box,"Offline Maps","United Kingdom\nInstall compatible PMTiles before travelling. Online downloads are not configured yet; import real files through Android Files.");
  if(importing){mapImportStatus=text("Importing map • "+formatBytes(mapImportBytes)+" copied",13,AMBER);spaced(box,mapImportStatus,-2);}
  addSection(box,"Storage",formatBytes(mapStorageBytes)+" of imported map packs. The bundled Shrewsbury map is included with the app.");
  android.location.Location fix=location.fix();
  addSection(box,"Data coverage",fix==null?"Waiting for location to check map coverage. Search and routing currently cover the Shrewsbury development region only.":map.getCoverageStatus());
  Button custom=button(importing?"Importing map…":"Import custom UK PMTiles",true);custom.setEnabled(!importing);spaced(box,custom,56);custom.setOnClickListener(v->pickMap("custom",null));
  if(!storageReady)box.addView(text("Loading map packs…",13,MUTED));
  for(MapPack pack:installedMaps){
   LinearLayout card=panel();TextView title=text((pack.selected?"✓ ":"")+pack.name,18,pack.selected?GREEN:TEXT);title.setTypeface(null,Typeface.BOLD);card.addView(title);card.addView(text(pack.availabilityText(),12,MUTED));
   if(fix!=null&&pack.installed&&pack.bounds.contains(fix.getLatitude(),fix.getLongitude()))card.addView(text("Covers your current position",12,GREEN));
   if(pack.installed){
    Button use=button(pack.selected?"Open this map":"Use map",false);spaced(card,use,52);use.setOnClickListener(v->selectMap(pack));
    if(!pack.bundled){Button replace=button("Update / Replace",false),remove=button("Remove map",false);spaced(card,replace,52);spaced(card,remove,52);replace.setOnClickListener(v->pickMap(pack.regionId,pack.id));remove.setOnClickListener(v->removeMap(pack));}
   }else{Button install=button("Import region PMTiles",false);spaced(card,install,52);install.setEnabled(!importing);install.setOnClickListener(v->pickMap(pack.regionId,null));}
   spaced(box,card,-2);
  }
  addSection(box,"Map • Search • Routing","PMTiles supplies the visible basemap. Imported packs do not add the matching offline search database or routing graph. Shrewsbury search and routing remain available in their own test-region coverage. Future regional data packs can add those independently.");
 }
 private void pickMap(String region,String replacement){
  if(importing){alert("A map is already being imported.");return;}pendingMapRegion=region;pendingMapReplacement=replacement;
  Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
  intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));
  try{startActivityForResult(intent,9);}catch(Exception e){alert("No document picker is available on this device.");}
 }
 private void selectMap(MapPack pack){storageWorker.execute(()->{try{mapPacks.select(pack.id);runOnUiThread(()->{map.reloadSelectedMap();openScreen(NavigationState.MAP);});refreshStoredData();}catch(Exception e){runOnUiThread(()->alert("Could not select this map: "+e.getMessage()));}});}
 private void removeMap(MapPack pack){MapDownloadWorker.cancel(this,pack.regionId);new AlertDialog.Builder(this).setTitle("Remove "+pack.name+"?").setMessage("The imported map will be removed from this phone. GPX routes and convoy stops are kept.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->storageWorker.execute(()->{try{mapPacks.remove(pack.id);runOnUiThread(()->map.refreshInstalledMaps());refreshStoredData();}catch(Exception e){runOnUiThread(()->alert("Could not remove this map: "+e.getMessage()));}})).show();}
 private void chooseCar(){
  String[] names={"Mazda MX-5 NC PRHT","Toyota GT86","Skyline R34","Supra Mk4","Honda NSX","Impreza GC8","Lancer Evo VI","AE86 Trueno","350Z","MX-5 NA","Generic car"};
  String[] keys={"nc","gt86","r34","supra","nsx","gc8","evo6","ae86","z350","na","generic"};String saved=getPreferences(0).getString("car","nc");int selected=0;for(int i=0;i<keys.length;i++)if(keys[i].equals(saved))selected=i;
  new AlertDialog.Builder(this).setTitle("Your car icon").setSingleChoiceItems(names,selected,(d,w)->{getPreferences(0).edit().putString("car",keys[w]).apply();ConvoyService s=ConvoyService.current;if(s!=null)s.myCar=keys[w];map.refresh();d.dismiss();}).setNegativeButton("Cancel",null).show();
 }
 private void updateCrew(){
  if(tab!=2||crewPanel==null)return;
  ConvoyService s=ConvoyService.current;ArrayList<String> rows=new ArrayList<>();String muteLabel=null;
  if(s==null){
   rows.add("Your crew appears here");rows.add("Start or join a trip to share positions.");
  }else{
   rows.add(s.myName+" • you");rows.add((s.host?"Host":"Member")+" • "+s.status+"\n"+s.driverCount()+" driver(s) • "+(s.connectionCount()>0||s.host?"Encrypted local convoy":"Waiting for an authenticated host connection"));
   ArrayList<ConvoyService.Member> list=new ArrayList<>(s.members.values());list.sort(Comparator.comparing(m->m.name));
   for(ConvoyService.Member m:list){
    long seen=Math.max(0,(SystemClock.elapsedRealtime()-m.received)/1000);
    String line=(seen<=10?"Link recently heard":"Link not heard")+" · "+(seen/5*5)+"s+ ago\n";
    if(m.located){
     long age=m.age();line+=(age>15000?"STALE GPS":"Fresh GPS")+" · "+(age/5000*5)+"s+ old · ±"+Math.round(m.accuracy)+" m\n"+String.format(Locale.US,"%.5f, %.5f",m.lat,m.lon);
     if(s.fix!=null){float[] distance=new float[1];android.location.Location.distanceBetween(s.fix.getLatitude(),s.fix.getLongitude(),m.lat,m.lon,distance);line+="\nLast-position distance: "+(distance[0]<1000?Math.round(distance[0])+" m":String.format(Locale.US,"%.1f km",distance[0]/1000));}
    }else line+="Waiting for this phone's GPS fix";
    rows.add(m.name+" • "+m.car);rows.add(line);
   }
   if(list.isEmpty()){rows.add("Waiting for friends");rows.add("Keep the app open on each phone while connecting.");}
   muteLabel=s.muted?"Unmute received voice":"Mute received voice";
  }
  ArrayList<String> next=new ArrayList<>(rows);next.add(muteLabel);
  if(crewSnapshotService==s&&crewSnapshot.equals(next))return;
  crewSnapshotService=s;crewSnapshot=next;crewPanel.removeAllViews();
  for(int i=0;i<rows.size();i+=2)addSection(crewPanel,rows.get(i),rows.get(i+1));
  if(s!=null&&s.host&&s.secureSession()){Button invite=button("Invite drivers",true);spaced(crewPanel,invite,52);invite.setOnClickListener(v->onboarding.showInvite());}
  if(s!=null){Button mute=button(muteLabel,false);crewPanel.addView(mute);mute.setOnClickListener(v->s.muted=!s.muted);}
 }
 private final Runnable tick=new Runnable(){public void run(){
  // Dialogs have their own focused window. Do not invalidate the covered activity
  // or emit duplicate accessibility changes while the user is completing a form.
  if(!hasWindowFocus()){handler.postDelayed(this,1000);return;}
  ConvoyService s=ConvoyService.current;boolean running=s!=null&&s.active;if(tab==NavigationState.HOME&&homeActiveShown!=running)renderTab();
  if(running){
   hadTrip=true;if((getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)==0)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateText(trip,"Open Crew");
   updateText(status,s.status);updateText(detail,s.detail.isEmpty()?"Encrypted local convoy":s.detail);
   updateText(voice,!s.ptt&&s.connectionCount()==0?"Local voice · waiting for crew":s.muted?"Received voice muted":s.voiceStatus());updateText(vox,s.vox?"Hands-free\non":"Hands-free\noff");
   updateDescription(vox,s.vox?"Hands-free voice enabled. Tap to disable":"Enable hands-free voice");
   updateText(gps,location.snapshot().statusText());
   long fresh=s.members.values().stream().filter(m->m.age()<=15000).count();
   updateText(crewSummary,fresh+" crew · "+s.connectionCount()+" link(s)");
  }else{
   boolean localNavigation=tab==NavigationState.MAP&&(followingGpx||(map.getTargetName()!=null&&!map.getTargetName().isEmpty()));
   if(localNavigation)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else if((getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateText(trip,"Start Convoy");
   updateText(status,hadTrip?"Trip ended · map stays offline":"Offline. Together.");
   updateText(detail,"No mobile signal needed. Set up together before driving.");
   updateText(voice,"Local voice · start a trip");updateText(gps,location.snapshot().statusText());
   updateText(crewSummary,"Offline map");updateText(vox,"Hands-free\noff");updateDescription(vox,"Set up a trip to enable hands-free voice");
  }
  updateDescription(trip,running?"End this convoy trip":"Start or join a convoy trip");
  updateDescription(connectionBranding,"Convoy. "+status.getText()+". Tap for trip and connection details.");
  ptt.setEnabled(running&&s.connectionCount()>0);
  if(!ptt.isEnabled()&&s!=null)s.ptt=false;
  updateText(ptt,running&&s.ptt?(pttPhysicalDown?"Release to listen":"Tap to finish talking"):"Hold to talk");
  updateDescription(ptt,running&&s.ptt?"Talking. Release or click to stop transmitting":"Hold to transmit voice. Accessibility click toggles talking on or off.");
  map.refresh();String coverage=map.mapCoverageNotice();coverageDownload.setVisibility(coverage.isEmpty()||showInstruments?View.GONE:View.VISIBLE);if(!coverage.isEmpty()){updateText(coverageDownload,coverage);updateText(crewSummary,"Download map");}updateText(attributionLabel,"© OpenStreetMap · Protomaps"+(map.attribution.contains("Overture")?" · Overture":""));updateDescription(attributionLabel,"Offline map source: "+map.mapName);updateToolbar();offlineMaps.tickLocation();updateNavigationBanner();updateHomeStatus();updateVoiceVisibility();updateCrew();handler.postDelayed(this,1000);
 }};
 private void updateNavigationBanner(){
  if(navPrimary==null||navSecondary==null||routeSummary==null)return;
  String target=map.getTargetName(),instruction=map.getInstruction();float metres=map.getDistanceRemaining();int eta=map.getEtaSeconds();
  boolean routing=target!=null&&!target.isEmpty();
  navigationBanner.setVisibility(routing?View.VISIBLE:View.GONE);
  updateText(routeAction,map.hasManualTarget()?"Stop":activeGpx!=null?"Routes":"Plan");
  updateDescription(routeAction,map.hasManualTarget()?"Stop navigating to this place":activeGpx!=null?"Open GPX route details":"Open your trip plan");
  if(!routing&&activeGpx!=null){
   GpxRoute.Progress progress=map.getGpxProgress();
   updateText(routeTitle,activeGpx.name);updateText(routeAction,"Routes");
   String summary=followingGpx?"Following GPX track":"GPX track loaded";
   if(progress!=null)summary+=" • "+formatDistance((float)progress.remainingMeters)+" remaining • "+formatDistance((float)progress.offRouteMeters)+" off route";
   else summary+=" • "+formatDistance((float)activeGpx.totalMeters);
   if(progress!=null&&progress.nextWaypoint!=null)summary+="\nNext: "+progress.nextWaypoint.name+" • "+formatDistance((float)progress.nextWaypointMeters)+" straight line";
   updateText(routeSummary,summary);applyMapLayout();return;
  }
  if(!routing){
   updateText(routeTitle,"Ready for the road");updateText(routeSummary,"Choose a place or plan your next stop");
   applyMapLayout();return;
  }
  if(instruction==null||instruction.isEmpty())instruction=map.routingReady()?"Calculating offline route…":"Preparing offline routing…";
  float turnDistance=map.getManeuverDistance();
  String cue=(turnDistance>0?formatMiles(turnDistance)+" · ":"")+"to "+target;
  updateText(navPrimary,cue);updateDescription(navPrimary,cue);
  String turn=instruction.toLowerCase(Locale.ROOT);
  int nextDirection=turn.startsWith("turn left")?R.drawable.ic_turn_left:
          turn.startsWith("turn right")?R.drawable.ic_turn_right:
          turn.contains("u-turn")?R.drawable.ic_u_turn:
          turn.contains("arriv")?R.drawable.ic_location:R.drawable.ic_direction;
  if(directionResource!=nextDirection){directionResource=nextDirection;directionIcon.setImageResource(nextDirection);}
  updateText(navSecondary,instruction);updateDescription(navSecondary,instruction);
  String summary;
  if(metres>=0){
   updateText(routeTitle,metres==0?"Arrived":eta>0?formatEta(eta):formatMiles(metres));
   summary=formatMiles(metres)+(eta>0?" · arrive "+clockEta(eta):"")+" · "+target;
  }else{updateText(routeTitle,target);summary=instruction;}
  if(activeGpx!=null){GpxRoute.Progress gpx=map.getGpxProgress();summary+="\n"+(followingGpx?"Following GPX: ":"GPX: ")+activeGpx.name+(gpx==null?"":" • "+formatDistance((float)gpx.remainingMeters)+" remaining");}
  updateText(routeSummary,summary);
  updateDescription(routeSummary,routeSummary.getText());applyMapLayout();
 }
 private String formatMiles(float metres){if(metres<=0)return "0 m";if(metres<161)return Math.max(10,Math.round(metres/10f)*10)+" m";if(metres<1609)return Math.round(metres*1.09361f)+" yd";return String.format(Locale.UK,"%.1f mi",metres/1609.344f);}
 private String formatEta(int seconds){int m=Math.max(1,(int)Math.ceil(seconds/60.0));return m<60?m+" min":(m/60)+" h "+(m%60)+" min";}
 private String clockEta(int seconds){Calendar c=Calendar.getInstance();c.add(Calendar.SECOND,seconds);return String.format(Locale.UK,"%02d:%02d",c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE));}
 @Override protected void onStart(){super.onStart();map.onStart();}
 @Override protected void onResume(){super.onResume();resumed=true;location.permissionChanged();updateLocationLease();map.onResume();handler.post(tick);}
 @Override protected void onPause(){resumed=false;updateLocationLease();handler.removeCallbacks(tick);ConvoyService s=ConvoyService.current;if(s!=null)s.ptt=false;pttPhysicalDown=false;map.onPause();super.onPause();}
 @Override protected void onStop(){map.onStop();super.onStop();}
 @Override public void onLowMemory(){super.onLowMemory();map.onLowMemory();}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("screen",navigation.screen());out.putInt("detailParent",navigation.detailParent());out.putString("detailRouteId",detailRouteId);map.onSaveInstanceState(out);}
 @Override protected void onDestroy(){handler.removeCallbacks(tick);if(onboarding!=null)onboarding.close();if(offlineMaps!=null)offlineMaps.close();if(instrumentDialog!=null)instrumentDialog.dismiss();location.setForegroundClient(this,false);storageWorker.shutdownNow();if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);map.dispose();super.onDestroy();}
 private EditText field(LinearLayout box,String label,String value,boolean secret){box.addView(text(label,12,MUTED));EditText e=new EditText(this);e.setTextColor(TEXT);e.setSingleLine(true);e.setText(value);e.setTextSize(16);if(secret)e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);box.addView(e,new LinearLayout.LayoutParams(-1,dp(50)));return e;}
 private String randomKey(){byte[] b=new byte[8];new SecureRandom().nextBytes(b);StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x));return s.toString();}
 private void setup(){onboarding.start();}
 private void advancedSetup(){ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(12),dp(22),dp(8));scroll.addView(box);EditText name=field(box,"Your name (max 32 characters)",getPreferences(0).getString("name",""),false);box.addView(text("Your car icon",12,MUTED));Spinner car=new Spinner(this);String[] carNames={"MX-5 NC PRHT","Toyota GT86","Skyline R34","Supra Mk4","Honda NSX","Impreza GC8","Lancer Evo VI","AE86 Trueno","350Z","MX-5 NA","Generic car"};String[] carKeys={"nc","gt86","r34","supra","nsx","gc8","evo6","ae86","z350","na","generic"};car.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,carNames));String savedCar=getPreferences(0).getString("car","nc");for(int ci=0;ci<carKeys.length;ci++)if(carKeys[ci].equals(savedCar))car.setSelection(ci);box.addView(car);Spinner role=new Spinner(this);role.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Host a convoy","Join a convoy"}));box.addView(role);Spinner transport=new Spinner(this);transport.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Wi-Fi hotspot / local network","Paired Bluetooth"}));box.addView(transport);EditText key=field(box,"Group key • everyone must enter exactly the same key",randomKey(),true);box.addView(text("Keep the generated key when hosting. Replace it with your host’s key when joining.",12,MUTED));EditText ip=field(box,"Host IPv4 address • needed only to join Wi-Fi",getPreferences(0).getString("ip",""),false);ip.setHint("e.g. 192.168.43.1");ip.setHintTextColor(MUTED);CheckBox hotspot=new CheckBox(this);hotspot.setText("Create a local Wi-Fi hotspot when hosting");hotspot.setTextColor(TEXT);hotspot.setChecked(true);box.addView(hotspot);box.addView(text("Set up while parked. This build needs real-device range and audio testing before a remote trip.",12,MUTED));
 AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Advanced connection").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Continue",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String n=name.getText().toString().trim(),k=key.getText().toString(),addr=ip.getText().toString().trim();boolean hosting=role.getSelectedItemPosition()==0,bt=transport.getSelectedItemPosition()==1;String chosenCar=carKeys[car.getSelectedItemPosition()];if(n.isEmpty()||n.length()>32){name.setError("Enter 1–32 characters");return;}if(k.length()<12||k.length()>128){key.setError("Use a key of 12–128 characters");return;}if(!hosting&&!bt&&!validIp(addr)){ip.setError("Enter the host’s IPv4 address from its Crew tab");return;}getPreferences(0).edit().putString("name",n).putString("ip",addr).putString("car",chosenCar).apply();Runnable begin=()->{if(bt&&!hosting)pickBluetooth(n,k,chosenCar);else launch(n,k,hosting,bt,addr,hotspot.isChecked(),chosenCar);};dialog.dismiss();permissions(bt,hosting&&!bt&&hotspot.isChecked(),begin);}));dialog.show();}
 private boolean validIp(String value){String[] a=value.split("\\.");if(a.length!=4)return false;try{for(String x:a){int n=Integer.parseInt(x);if(n<0||n>255)return false;}return true;}catch(Exception e){return false;}}
 private boolean ensureVoice(ConvoyService service){
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);return false;}
  return service.enableVoice();
 }
 private void permissions(boolean bt,boolean hotspot,Runnable action){
  ArrayList<String> needed=new ArrayList<>();
  if(!hasLocationPermission()||(hotspot&&Build.VERSION.SDK_INT<33&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)){needed.add(Manifest.permission.ACCESS_FINE_LOCATION);needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);}
  if(Build.VERSION.SDK_INT>=31&&bt)needed.add(Manifest.permission.BLUETOOTH_CONNECT);
  if(Build.VERSION.SDK_INT>=33){if(hotspot)needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);needed.add(Manifest.permission.POST_NOTIFICATIONS);}
  ArrayList<String> missing=new ArrayList<>();for(String p:needed)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
  if(missing.isEmpty())action.run();else{permissionAction=action;requestPermissions(missing.toArray(new String[0]),5);}
 }
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
  super.onRequestPermissionsResult(code,permissions,grants);location.permissionChanged();
  if(code==6){updateLocationLease();updateNavigationBanner();return;}
  if(code==7){ConvoyService service=ConvoyService.current;if(service!=null&&service.active&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)service.enableVoice();return;}
  if(code!=5)return;
  boolean permitted=hasLocationPermission();
  for(int i=0;i<permissions.length&&i<grants.length;i++)if(!Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])&&!Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])&&!Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i])&&grants[i]!=PackageManager.PERMISSION_GRANTED)permitted=false;
  Runnable action=permissionAction;permissionAction=null;if(permitted&&action!=null){action.run();return;}
  new AlertDialog.Builder(this).setTitle("Permissions needed").setMessage("Allow Location and the nearby-device permissions for your selected convoy connection. Microphone is optional and requested when you use voice.").setPositiveButton("App settings",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton("Close",null).show();
 }
 private void pickBluetooth(String name,String key,String car){
  if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
   alert("Allow Nearby devices permission in Android app settings, then choose your paired host again.");return;
  }
  try{
   BluetoothManager manager=getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
   if(adapter==null||!adapter.isEnabled())throw new IOException("Turn on Bluetooth and pair with the host in Android settings first.");
   ArrayList<BluetoothDevice> devices=new ArrayList<>(adapter.getBondedDevices());
   if(devices.isEmpty())throw new IOException("No paired devices. Pair with the host in Android Bluetooth settings first.");
   String[] labels=new String[devices.size()],addresses=new String[devices.size()];
   for(int i=0;i<devices.size();i++){addresses[i]=devices.get(i).getAddress();labels[i]=devices.get(i).getName()+"\n"+addresses[i];}
   new AlertDialog.Builder(this).setTitle("Choose your paired host phone")
           .setItems(labels,(d,w)->launch(name,key,false,true,addresses[w],false,car)).setNegativeButton("Cancel",null).show();
  }catch(Exception e){alert(e.getMessage());}
 }
 private void launch(String name,String key,boolean host,boolean bt,String address,boolean hotspot,String car){try{Intent i=new Intent(this,ConvoyService.class).putExtra("name",name).putExtra("key",key).putExtra("host",host).putExtra("bluetooth",bt).putExtra("address",address).putExtra("hotspot",hotspot).putExtra("car",car);startForegroundService(i);openScreen(host&&!bt?NavigationState.CREW:NavigationState.MAP);}catch(Exception e){alert(e.getMessage());}}
 private void renderPlan(LinearLayout box){
  ArrayList<TripPlan.Waypoint> points=TripPlan.load(this);map.setWaypoints(points);
  addSection(box,"Plan the day","Save the places your convoy may need even when mobile data disappears. The order below is the stop order shown on the map. Share the plan before leaving so each phone has a local copy.");
  LinearLayout actions=new LinearLayout(this);Button add=button("+ Add stop",true),paste=button("Paste plan",false);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(48),1);ap.setMargins(0,0,dp(7),0);actions.addView(add,ap);actions.addView(paste,new LinearLayout.LayoutParams(0,dp(48),1));spaced(box,actions,48);
  LinearLayout actions2=new LinearLayout(this);Button share=button("Share plan",false),clear=button("Clear",false);LinearLayout.LayoutParams ap2=new LinearLayout.LayoutParams(0,dp(48),1);ap2.setMargins(0,0,dp(7),0);actions2.addView(share,ap2);actions2.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));spaced(box,actions2,48);
  add.setOnClickListener(v->waypointDialog(null));paste.setOnClickListener(v->pastePlan());share.setOnClickListener(v->sharePlan());clear.setOnClickListener(v->{if(points.isEmpty())return;new AlertDialog.Builder(this).setTitle("Clear trip plan?").setMessage("This removes all saved stops from this phone.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{TripPlan.save(this,new ArrayList<TripPlan.Waypoint>());refreshPlan();renderTab();}).show();});
  if(points.isEmpty()){addSection(box,"No stops yet","Add a fuel stop, food/rest break, viewpoint, meet-up/rejoin point, hazard or any custom POI. If a live GPS fix is available it will be pre-filled when you add a stop.");return;}
  int index=1;for(TripPlan.Waypoint w:points){final TripPlan.Waypoint point=w;LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(13),dp(10),dp(13),dp(10));card.setBackground(shape(CARD,14));TextView h=text(index+"  "+TripPlan.symbol(w.type)+" "+w.name,16,w.done?MUTED:TEXT);h.setTypeface(null,Typeface.BOLD);card.addView(h);String meta=w.type+(w.done?" • visited":" • planned")+"\n"+String.format(Locale.US,"%.5f, %.5f",w.lat,w.lon);android.location.Location planFix=location.fix();if(planFix!=null){float[] out=new float[1];android.location.Location.distanceBetween(planFix.getLatitude(),planFix.getLongitude(),w.lat,w.lon,out);meta+="\nFrom your last fix: "+formatDistance(out[0]);}card.addView(text(meta,12,MUTED));LinearLayout row=new LinearLayout(this);Button show=button("Show",false),done=button(w.done?"Undo":"Done",false),edit=button("Edit",false),del=button("×",false);for(Button b:new Button[]{show,done,edit,del}){LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(48),1);bp.setMargins(0,dp(7),dp(5),0);row.addView(b,bp);}card.addView(row);show.setOnClickListener(v->{map.focus(point.lat,point.lon);openScreen(NavigationState.MAP);});done.setOnClickListener(v->{point.done=!point.done;TripPlan.upsert(this,point);refreshPlan();renderTab();});edit.setOnClickListener(v->waypointDialog(point));del.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Delete "+point.name+"?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,x)->{TripPlan.remove(this,point.id);refreshPlan();renderTab();}).show());spaced(box,card,-2);index++;}
  addSection(box,"Map behaviour","Convoy routes to the next unvisited stop over the bundled offline road graph. The thin dashed line still shows the overall stop sequence; the thick blue line is the calculated driving route.");
 }
 private String formatDistance(float metres){return metres<1000?Math.round(metres)+" m":String.format(Locale.US,"%.1f km",metres/1000f);}
 private void refreshPlan(){map.setWaypoints(TripPlan.load(this));map.refresh();}
 private void waypointDialog(TripPlan.Waypoint existing){
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(8),dp(22),0);String[] types={"Fuel","Food","Rest","Viewpoint","Meet-up","Hazard","Other"};Spinner type=new Spinner(this);type.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,types));box.addView(text("Type",12,MUTED));box.addView(type);if(existing!=null){for(int i=0;i<types.length;i++)if(types[i].equals(existing.type))type.setSelection(i);}
  EditText name=field(box,"Name",existing==null?"":existing.name,false);double lat0=Double.NaN,lon0=Double.NaN;if(existing!=null){lat0=existing.lat;lon0=existing.lon;}else{android.location.Location planFix=location.fix();if(planFix!=null){lat0=planFix.getLatitude();lon0=planFix.getLongitude();}}
  EditText lat=field(box,"Latitude",Double.isNaN(lat0)?"":String.format(Locale.US,"%.6f",lat0),false),lon=field(box,"Longitude",Double.isNaN(lon0)?"":String.format(Locale.US,"%.6f",lon0),false);int numberType=android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED;lat.setInputType(numberType);lon.setInputType(numberType);box.addView(text("Current GPS coordinates are pre-filled when Convoy has a fix. You can also enter coordinates manually while planning at home.",12,MUTED));
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Add planned stop":"Edit planned stop").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError("Enter a name");return;}try{double la=Double.parseDouble(lat.getText().toString().trim()),lo=Double.parseDouble(lon.getText().toString().trim());if(!Double.isFinite(la)||!Double.isFinite(lo)||la< -90||la>90||lo< -180||lo>180)throw new NumberFormatException();String t=(String)type.getSelectedItem();TripPlan.Waypoint w=existing==null?TripPlan.create(n,t,la,lo):new TripPlan.Waypoint(existing.id,n,t,la,lo,existing.created,existing.done);TripPlan.upsert(this,w);refreshPlan();dialog.dismiss();renderTab();}catch(Exception e){lat.setError("Check latitude / longitude");lon.setError("Check latitude / longitude");}}));dialog.show();
 }
 private void sharePlan(){ArrayList<TripPlan.Waypoint> points=TripPlan.load(this);if(points.isEmpty()){alert("Add at least one planned stop first.");return;}Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_SUBJECT,"Convoy trip plan");send.putExtra(Intent.EXTRA_TEXT,TripPlan.encode(points));startActivity(Intent.createChooser(send,"Share Convoy plan"));}
 private void pastePlan(){
  EditText input=new EditText(this);input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setHint("Paste CONVOY_PLAN_V1 text here");input.setMinLines(8);input.setGravity(Gravity.TOP);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setPadding(dp(14),dp(10),dp(14),dp(10));try{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm!=null&&cm.hasPrimaryClip()&&cm.getPrimaryClip()!=null&&cm.getPrimaryClip().getItemCount()>0){CharSequence c=cm.getPrimaryClip().getItemAt(0).coerceToText(this);if(c!=null&&c.toString().contains(TripPlan.HEADER))input.setText(c);}}catch(Exception ignored){}
  FrameLayout wrap=new FrameLayout(this);wrap.setPadding(dp(18),0,dp(18),0);wrap.addView(input,new FrameLayout.LayoutParams(-1,dp(260)));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Import shared trip plan").setMessage("This replaces the plan saved on this phone.").setView(wrap).setNegativeButton("Cancel",null).setPositiveButton("Import",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{ArrayList<TripPlan.Waypoint> points=TripPlan.decode(input.getText().toString());if(points.isEmpty()){input.setError("No valid Convoy stops found");return;}TripPlan.save(this,points);refreshPlan();dialog.dismiss();renderTab();Toast.makeText(this,points.size()+" stops imported",Toast.LENGTH_LONG).show();}));dialog.show();
 }
 private void chooseMap(){openScreen(NavigationState.OFFLINE_MAPS);}
 @Override protected void onActivityResult(int req,int result,Intent data){
  super.onActivityResult(req,result,data);
  com.google.zxing.integration.android.IntentResult scan=com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(req,result,data);if(scan!=null){if(scan.getContents()!=null)onboarding.acceptLink(scan.getContents());return;}
  if(result!=RESULT_OK||data==null||data.getData()==null)return;
  Uri uri=data.getData();
  if(req==10){if(!gpxImporting)importGpx(uri);return;}
  if(req!=9||importing)return;
  final String region=pendingMapRegion,replacement=pendingMapReplacement;
  importing=true;mapImportBytes=0;if(tab==NavigationState.OFFLINE_MAPS)renderTab();
  Toast.makeText(this,"Importing vector map…",Toast.LENGTH_LONG).show();
  storageWorker.execute(()->{
   try{
    String name=documentName(uri,"Imported vector map");final long[] lastProgress={0};
    MapPackStore.Progress progress=bytes->{long now=SystemClock.elapsedRealtime();if(now-lastProgress[0]<500)return;lastProgress[0]=now;runOnUiThread(()->{mapImportBytes=bytes;offlineMaps.setImportProgress(importing,bytes);if(mapImportStatus!=null)updateText(mapImportStatus,"Importing map • "+formatBytes(bytes)+" copied");});};
    try(InputStream input=getContentResolver().openInputStream(uri)){
     if(input==null)throw new IOException("Could not read that document");
     if(replacement==null)mapPacks.importPack(input,name,region,progress);else mapPacks.replacePack(input,name,replacement,progress);
    }
    runOnUiThread(()->{importing=false;if(isDestroyed()||isFinishing())return;map.reloadSelectedMap();Toast.makeText(this,"Offline map installed",Toast.LENGTH_LONG).show();});
    refreshStoredData();
   }catch(Exception error){runOnUiThread(()->{importing=false;if(isDestroyed()||isFinishing())return;alert("Map import failed. "+error.getMessage());if(tab==NavigationState.OFFLINE_MAPS)renderTab();});}
  });
 }
 private void validatePmtiles(File file)throws IOException{com.convoy.offline.PmtilesArchive.validate(file);}
 private void searchPlaces(){final EditText q=new EditText(this);q.setSingleLine(true);q.setTextColor(TEXT);q.setHintTextColor(MUTED);q.setHint("fuel, cafe, supermarket, castle…");q.setPadding(dp(14),0,dp(14),0);AlertDialog d=new AlertDialog.Builder(this).setTitle("Search offline places").setMessage("Search the Shrewsbury place index stored on this phone.").setView(q).setNegativeButton("Cancel",null).setPositiveButton("Search",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{String s=q.getText().toString().trim();if(s.length()<2){q.setError("Type at least two characters");return;}d.dismiss();map.search(s,this::showSearchResults);}));d.show();}
 private void showSearchResults(ArrayList<PlaceInfo> places){if(places==null||places.isEmpty()){alert("No matching places were found in the offline Shrewsbury index.");return;}String[] labels=new String[places.size()];for(int i=0;i<places.size();i++){PlaceInfo p=places.get(i);String sub=p.subtitle();labels[i]=p.name+(sub.isEmpty()?"":"\n"+sub);}new AlertDialog.Builder(this).setTitle("Offline places").setItems(labels,(d,w)->{PlaceInfo p=places.get(w);map.focus(p.lat,p.lon);placeSheet(p);}).setNegativeButton("Close",null).show();}
 private void placeSheet(PlaceInfo p){if(p==null)return;StringBuilder b=new StringBuilder();if(p.category!=null&&!p.category.isEmpty())b.append(p.category.replace('_',' '));android.location.Location localFix=location.fix();if(localFix!=null){float[] d=new float[1];android.location.Location.distanceBetween(localFix.getLatitude(),localFix.getLongitude(),p.lat,p.lon,d);b.append(b.length()>0?" • ":"").append(formatDistance(d[0])).append(" away");}if(p.address!=null&&!p.address.isEmpty())b.append(b.length()>0?"\n":"").append(p.address);if(p.brand!=null&&!p.brand.isEmpty()&&!p.brand.equalsIgnoreCase(p.name))b.append("\nBrand: ").append(p.brand);if(p.cuisine!=null&&!p.cuisine.isEmpty())b.append("\nCuisine: ").append(p.cuisine.replace(';',','));if(p.operator!=null&&!p.operator.isEmpty())b.append("\nOperator: ").append(p.operator);if(p.openingHours!=null&&!p.openingHours.isEmpty())b.append("\nHours: ").append(p.openingHours);if(p.wheelchair!=null&&!p.wheelchair.isEmpty())b.append("\nWheelchair: ").append(p.wheelchair);if(p.internetAccess!=null&&!p.internetAccess.isEmpty())b.append("\nInternet: ").append(p.internetAccess);if(p.phone!=null&&!p.phone.isEmpty())b.append("\nPhone: ").append(p.phone);if(p.website!=null&&!p.website.isEmpty())b.append("\nWeb: ").append(p.website);if(p.source!=null&&!p.source.isEmpty())b.append("\n\nData: ").append(p.source);showPlaceDetails(p,b.length()==0?String.format(Locale.UK,"%.5f, %.5f",p.lat,p.lon):b.toString());}
 private void showPlaceDetails(PlaceInfo p,String information){
  ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);
  content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(6),dp(22),dp(8));
  TextView info=text(information,14,MUTED);info.setLineSpacing(dp(3),1);info.setTextIsSelectable(true);
  spaced(content,info,-2);scroll.addView(content);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle(p.name).setView(scroll).setNegativeButton("Close",null).create();
  Button navigate=button("Navigate here",true);spaced(content,navigate,48);
  navigate.setOnClickListener(v->{dialog.dismiss();map.navigateTo(p);openScreen(NavigationState.MAP);updateNavigationBanner();});
  LinearLayout saveRow=new LinearLayout(this);Button save=button("Save stop",false),meet=button("Meet here",false);
  LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(0,dp(48),1);saveParams.rightMargin=dp(8);
  saveRow.addView(save,saveParams);saveRow.addView(meet,new LinearLayout.LayoutParams(0,dp(48),1));spaced(content,saveRow,48);
  save.setOnClickListener(v->{savePlace(p,false);dialog.dismiss();});meet.setOnClickListener(v->{savePlace(p,true);dialog.dismiss();});
  Button show=button("Show on map",false);spaced(content,show,48);
  show.setOnClickListener(v->{dialog.dismiss();map.focus(p.lat,p.lon);openScreen(NavigationState.MAP);});dialog.show();
 }
 private void savePlace(PlaceInfo p,boolean meeting){
  TripPlan.Waypoint stop=TripPlan.create(p.name,meeting?"Meet-up":placeType(p.category),p.lat,p.lon);
  TripPlan.upsert(this,stop);if(meeting)map.clearManualTarget();refreshPlan();
  Toast.makeText(this,"Added to trip plan",Toast.LENGTH_SHORT).show();
 }
 private String placeType(String category){String c=category==null?"":category.toLowerCase(Locale.ROOT);if(c.contains("fuel")||c.contains("charging"))return "Fuel";if(c.contains("restaurant")||c.contains("cafe")||c.contains("food")||c.contains("pub"))return "Food";if(c.contains("view")||c.contains("attraction")||c.contains("museum")||c.contains("castle"))return "Viewpoint";if(c.contains("parking")||c.contains("toilet")||c.contains("hotel")||c.contains("camp"))return "Rest";return "Other";}
 private void alert(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Convoy").setMessage(message).setPositiveButton("OK",null).show();}
}

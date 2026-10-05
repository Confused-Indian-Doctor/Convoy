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

public class MainActivity extends Activity {
 private static final int BG=0xFFF4F7FB, CARD=0xFFFFFFFF, GREEN=0xFF1764E8,
         TEXT=0xFF172B3F, MUTED=0xFF52677A;
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
 private final ArrayList<Button> tabButtons=new ArrayList<>();
 private final Handler handler=new Handler(Looper.getMainLooper());
 private int tab=0,mapLayoutSignature=-1;
 private Runnable permissionAction;
 private boolean importing=false,hadTrip=false,compactMap=false,showInstruments=false,pttTouchClick=false,pttPhysicalDown=false;

 private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
 private GradientDrawable shape(int color,int radius){
  GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
 }
 private TextView text(String value,int size,int color){
  TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);
  t.setPadding(0,dp(2),0,dp(2));return t;
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
  if(d!=null){d.setBounds(0,0,dp(24),dp(24));b.setCompoundDrawables(d,null,null,null);}
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
          View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|
          View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
  build();
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
  LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
  head.setPadding(dp(16),dp(6),dp(12),dp(6));head.setBackgroundColor(CARD);
  LinearLayout branding=new LinearLayout(this);connectionBranding=branding;branding.setOrientation(LinearLayout.VERTICAL);
  TextView title=text("convoy",23,TEXT);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
  branding.addView(title);status=text("Offline. Together.",11,MUTED);status.setSingleLine(true);
  status.setEllipsize(TextUtils.TruncateAt.END);branding.addView(status);
  head.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
  detail=text("No mobile signal needed. Set up together before driving.",12,MUTED);
  branding.setContentDescription("Convoy connection status. Tap for trip details");
  branding.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(status.getText()).setMessage(detail.getText())
          .setPositiveButton("Crew",(d,w)->{tab=2;renderTab();}).setNegativeButton("Close",null).show());
  trip=button("Start trip",true);head.addView(trip,new LinearLayout.LayoutParams(dp(112),dp(48)));
  trip.setOnClickListener(v->{
   if(ConvoyService.current!=null)new AlertDialog.Builder(this).setTitle("End this trip?")
           .setMessage("Location sharing and voice will stop on this phone.").setNegativeButton("Keep going",null)
           .setPositiveButton("End trip",(d,w)->stopService(new Intent(this,ConvoyService.class))).show();
   else setup();
  });
  root.addView(head,new LinearLayout.LayoutParams(-1,dp(64)));
  body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
  root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
  map=new RichMap(this);map.setWaypoints(TripPlan.load(this));map.setNavigationMode(true);
  map.setOnPlaceSelectedListener(this::placeSheet);buildMapPanel();buildVoiceBar();buildTabs();renderTab();
 }
 private void buildMapPanel(){
  mapPanel=new LinearLayout(this);mapPanel.setOrientation(LinearLayout.VERTICAL);
  mapFrame=new FrameLayout(this);mapFrame.setBackgroundColor(0xFFE2EDE7);
  mapFrame.addView(map,new FrameLayout.LayoutParams(-1,-1));
  mapPanel.addView(mapFrame,new LinearLayout.LayoutParams(-1,-1));

  searchOverlay=new LinearLayout(this);searchOverlay.setOrientation(LinearLayout.VERTICAL);
  LinearLayout searchRow=new LinearLayout(this);searchRow.setGravity(Gravity.CENTER_VERTICAL);
  Button search=button("Search offline places",false);search.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
  search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search,0,0,0);search.setCompoundDrawablePadding(dp(10));
  search.setSingleLine(true);search.setEllipsize(TextUtils.TruncateAt.END);
  search.setContentDescription("Search places stored on this phone");search.setOnClickListener(v->searchPlaces());
  LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(0,dp(52),1);searchParams.rightMargin=dp(8);
  searchRow.addView(search,searchParams);
  Button importMap=iconButton(R.drawable.ic_map,"View or import an offline map pack");
  importMap.setOnClickListener(v->chooseMap());searchRow.addView(importMap,new LinearLayout.LayoutParams(dp(48),dp(52)));
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
  center.setOnClickListener(v->map.recenter());addMapControl(center);
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
  routeSummary=text("Choose a place or plan your next stop",12,MUTED);routeSummary.setSingleLine(true);
  routeSummary.setEllipsize(TextUtils.TruncateAt.END);routeCopy.addView(routeSummary);
  routeRow.addView(routeCopy,new LinearLayout.LayoutParams(0,-2,1));
  routeAction=button("Plan",false);routeAction.setTextColor(GREEN);routeAction.setElevation(0);
  routeRow.addView(routeAction,new LinearLayout.LayoutParams(dp(64),dp(48)));
  routeAction.setOnClickListener(v->{
   if(map.hasManualTarget())new AlertDialog.Builder(this).setTitle("Stop navigating here?")
           .setMessage("Your saved trip plan will stay available.").setNegativeButton("Continue",null)
           .setPositiveButton("Stop route",(d,w)->{map.clearManualTarget();updateNavigationBanner();}).show();
   else{tab=1;renderTab();}
  });routeCard.addView(routeRow);
  LinearLayout mapStatus=new LinearLayout(this);mapStatus.setGravity(Gravity.CENTER_VERTICAL);
  gps=text("GPS starts with a trip",10,MUTED);gps.setSingleLine(true);gps.setEllipsize(TextUtils.TruncateAt.END);
  crewSummary=text("Offline map",10,GREEN);crewSummary.setGravity(Gravity.END);crewSummary.setSingleLine(true);
  crewSummary.setEllipsize(TextUtils.TruncateAt.END);mapStatus.addView(gps,new LinearLayout.LayoutParams(0,-2,1));
  mapStatus.addView(crewSummary,new LinearLayout.LayoutParams(0,-2,1));routeCard.addView(mapStatus);
  FrameLayout.LayoutParams routePlacement=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
  routePlacement.setMargins(dp(12),0,dp(12),dp(12));mapFrame.addView(routeCard,routePlacement);
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
  LinearLayout audio=new LinearLayout(this);audio.setGravity(Gravity.CENTER_VERTICAL);audio.setBackgroundColor(CARD);
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
   ConvoyService s=ConvoyService.current;if(s==null||s.connectionCount()==0)return;
   s.ptt=!s.ptt;ptt.setText(s.ptt?"Tap to finish talking":"Hold to talk");
  });
  ptt.setOnTouchListener((v,event)->{
   ConvoyService s=ConvoyService.current;if(s==null||!ptt.isEnabled())return false;
   switch(event.getActionMasked()){
    case MotionEvent.ACTION_DOWN:pttPhysicalDown=true;s.ptt=true;ptt.setText("Release to listen");return true;
    case MotionEvent.ACTION_UP:pttPhysicalDown=false;s.ptt=false;ptt.setText("Hold to talk");pttTouchClick=true;v.performClick();return true;
    case MotionEvent.ACTION_CANCEL:pttPhysicalDown=false;s.ptt=false;ptt.setText("Hold to talk");return true;
    default:return true;
   }
  });
  vox.setOnClickListener(v->{
   ConvoyService s=ConvoyService.current;if(s==null){setup();return;}if(s.vox){s.vox=false;return;}
   new AlertDialog.Builder(this).setTitle("Hands-free voice")
           .setMessage("The microphone transmits when it detects loud speech. Road noise can trigger it. One person speaks at a time; received speech temporarily pauses your microphone transmission.\n\nUse the phone or car audio at a moderate volume. Set this up while parked, then test with a passenger. Headset microphones and car Bluetooth routing vary by device.")
           .setNegativeButton("Cancel",null).setPositiveButton("Enable",(d,w)->s.vox=true).show();
  });
 }
 private void buildTabs(){
  LinearLayout tabs=new LinearLayout(this);tabs.setGravity(Gravity.CENTER_VERTICAL);tabs.setBackgroundColor(CARD);
  tabs.setPadding(dp(6),dp(4),dp(6),dp(4));String[] labels={"Map","Plan","Crew","Guide"};
  int[] icons={R.drawable.ic_map,R.drawable.ic_route,R.drawable.ic_crew,R.drawable.ic_info};
  for(int i=0;i<labels.length;i++){
   final int selectedTab=i;Button b=button(labels[i],false);b.setTextSize(11);b.setElevation(0);b.setPadding(dp(4),dp(4),dp(4),dp(4));
   android.graphics.drawable.Drawable icon=getDrawable(icons[i]);if(icon!=null){icon.setBounds(0,0,dp(20),dp(20));b.setCompoundDrawables(null,icon,null,null);}
   b.setCompoundDrawablePadding(dp(2));b.setContentDescription(labels[i]+" tab");tabButtons.add(b);
   tabs.addView(b,new LinearLayout.LayoutParams(0,dp(56),1));b.setOnClickListener(v->{tab=selectedTab;renderTab();});
  }
  root.addView(tabs,new LinearLayout.LayoutParams(-1,dp(64)));
 }
 private void renderTab(){
  if(instrumentDialog!=null&&instrumentDialog.isShowing())instrumentDialog.dismiss();
  for(int i=0;i<tabButtons.size();i++){
   Button b=tabButtons.get(i);boolean selected=i==tab;b.setSelected(selected);
   b.setTextColor(selected?GREEN:MUTED);
   b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x221764E8),shape(selected?0xFFE8F0FF:CARD,18),null));
   for(android.graphics.drawable.Drawable icon:b.getCompoundDrawables())if(icon!=null)icon.setTint(selected?GREEN:MUTED);
   b.setContentDescription(b.getText()+" tab"+(selected?", selected":""));
  }
  body.removeAllViews();
  if(tab==0){body.addView(mapPanel,new LinearLayout.LayoutParams(-1,-1));return;}
  ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(18),dp(18),dp(16));scroll.addView(box);body.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
  if(tab==1){crewPanel=null;renderPlan(box);return;}
  if(tab==2){crewPanel=box;updateCrew();return;}
  crewPanel=null;
  addSection(box,"Before you leave","Install the same APK on every phone. Allow precise Location, Microphone and Nearby devices. Turn Location and your selected radio on. Everyone uses the same group key.");
  addSection(box,"Trip plan & POIs","Use Plan to save fuel, food, rest, viewpoint, meet-up, hazard and custom stops. Planned stops are stored on this phone and appear on the map. The thin dashed line shows your stop sequence; a thick blue line shows the calculated offline driving route to your next unvisited stop. Share a plan before departure and paste/import it on the other phones.");
  addSection(box,"Wi-Fi • recommended","Choose one host. It can create a local hotspot with no internet. Friends join the displayed Wi-Fi in Android settings, choose Join in Convoy, and enter the host’s local IP. Keep the no-internet Wi-Fi connection when Android asks. A passenger can also use a portable router or a manually enabled hotspot.");
  addSection(box,"Bluetooth • alternative","Pair each friend’s phone with the host in Android Bluetooth settings first. Host starts Bluetooth mode; friends choose that paired host and enter the group key. Wi-Fi and Bluetooth modes are separate; there is no automatic radio switching.");
  addSection(box,"Offline vector maps","Convoy 0.4.1 uses MapLibre with local PMTiles. The bundled Shrewsbury pack includes vector roads, buildings, land use, place labels and POIs. You can import another compatible .pmtiles region without mobile data. Tap mapped places for details, or use Search for the local place index.");
  addSection(box,"Map controls","Search and the category chips work fully offline. Drive follows your heading; switch to North for a north-up map. Tap the location control to resume following after moving the map. Gauges opens the GPS speedometer and calibrated G-force, pitch and roll display; tap the lower gauge to zero tilt while mounted and parked.");
  addSection(box,"While moving","Let a passenger handle the screen. Enable hands-free before departure; hold-to-talk is for passengers or when parked. Voice is walkie-talkie style, one speaker at a time. Adjust media volume for received voice. Radio range can be short inside moving cars; stay within the connection’s tested range.");
  addSection(box,"Know what is live","Markers become stale after 15 seconds without a fresh GPS fix. Crew cards show age, accuracy and heartbeat. Convoy now calculates car routes fully offline inside the installed routing region; outside that region it will clearly say routing is unavailable. This is not emergency equipment.");
  addSection(box,"Privacy & stopping","No account, server, analytics or location history. Group traffic is encrypted with a shared key: only give it to your friends. Every group member can hear and see the group. Voice is not recorded to files. End trip in the app or notification to stop the service. Android may still stop it under power restrictions; test screen-off behaviour on your phones.");
  addSection(box,"Map information",map.mapName+"\n"+map.attribution+"\n\nConvoy 0.4.1 • offline navigation");
 }
 private void addSection(LinearLayout box,String title,String value){TextView h=text(title,17,TEXT);h.setTypeface(null,Typeface.BOLD);box.addView(h);TextView t=text(value,14,MUTED);t.setLineSpacing(dp(3),1);spaced(box,t,-2);}
 private void updateCrew(){if(tab!=2||crewPanel==null)return;crewPanel.removeAllViews();ConvoyService s=ConvoyService.current;if(s==null){addSection(crewPanel,"Your crew appears here","Start or join a trip to share positions.");return;}addSection(crewPanel,s.myName+" • you",(s.host?"Host":"Member")+" · "+(s.bluetooth?"Bluetooth":"Wi-Fi")+" · "+s.connectionCount()+" direct link(s)");
 if(s.host&&!s.bluetooth){addSection(crewPanel,"Wi-Fi details",(s.hotspotInfo.isEmpty()?"Using existing Wi-Fi / hotspot":s.hotspotInfo)+"\n\nHost IP addresses:\n"+s.addresses()+"\n\nUse the address belonging to the hotspot / Wi-Fi interface.");}
 ArrayList<ConvoyService.Member> list=new ArrayList<>(s.members.values());list.sort(Comparator.comparing(m->m.name));for(ConvoyService.Member m:list){long seen=Math.max(0,(SystemClock.elapsedRealtime()-m.received)/1000);String line=(seen<=10?"Link recently heard":"Link not heard")+" · "+seen+"s ago\n";if(m.located){line+=(m.age()>15000?"STALE GPS":"Fresh GPS")+" · "+m.age()/1000+"s old · ±"+Math.round(m.accuracy)+" m\n"+String.format(Locale.US,"%.5f, %.5f",m.lat,m.lon);if(s.fix!=null){float[] distance=new float[1];android.location.Location.distanceBetween(s.fix.getLatitude(),s.fix.getLongitude(),m.lat,m.lon,distance);line+="\nLast-position distance: "+(distance[0]<1000?Math.round(distance[0])+" m":String.format(Locale.US,"%.1f km",distance[0]/1000));}}else line+="Waiting for this phone's GPS fix";addSection(crewPanel,m.name,line);}
 if(list.isEmpty())addSection(crewPanel,"Waiting for friends","Keep the app open on each phone while connecting.");Button mute=button(s.muted?"Unmute received voice":"Mute received voice",false);crewPanel.addView(mute);mute.setOnClickListener(v->s.muted=!s.muted);}
 private final Runnable tick=new Runnable(){public void run(){
  ConvoyService s=ConvoyService.current;boolean running=s!=null&&s.active;
  if(running){
   hadTrip=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);trip.setText("End trip");
   status.setText(s.status);detail.setText(s.detail.isEmpty()?"Group ready · share your key privately":s.detail);
   voice.setText(s.muted?"Received voice muted":s.voiceStatus());vox.setText(s.vox?"Hands-free\non":"Hands-free\noff");
   vox.setContentDescription(s.vox?"Hands-free voice enabled. Tap to disable":"Enable hands-free voice");
   if(s.fix==null)gps.setText("Waiting for GPS");
   else{
    long age=(SystemClock.elapsedRealtimeNanos()-s.fix.getElapsedRealtimeNanos())/1000000;
    gps.setText((age>15000?"Stale GPS":"GPS")+" · ±"+Math.round(s.fix.getAccuracy())+" m · "+age/1000+"s");
   }
   long fresh=s.members.values().stream().filter(m->m.age()<=15000).count();
   crewSummary.setText(fresh+" crew · "+s.connectionCount()+" link(s)");
  }else{
   getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);trip.setText("Start trip");
   status.setText(hadTrip?"Trip ended · map stays offline":"Offline. Together.");
   detail.setText("No mobile signal needed. Set up together before driving.");
   voice.setText("Local voice · start a trip");gps.setText("GPS starts with a trip");
   crewSummary.setText("Offline map");vox.setText("Hands-free\noff");vox.setContentDescription("Set up a trip to enable hands-free voice");
  }
  trip.setContentDescription(running?"End this convoy trip":"Start or join a convoy trip");
  connectionBranding.setContentDescription("Convoy. "+status.getText()+". Tap for trip and connection details.");
  ptt.setEnabled(running&&s.connectionCount()>0);
  if(!ptt.isEnabled()&&s!=null)s.ptt=false;
  ptt.setText(running&&s.ptt?(pttPhysicalDown?"Release to listen":"Tap to finish talking"):"Hold to talk");
  ptt.setContentDescription(running&&s.ptt?"Talking. Release or click to stop transmitting":"Hold to transmit voice. Accessibility click toggles talking on or off.");
  if(running&&!s.ptt&&s.connectionCount()==0)voice.setText("Local voice · waiting for crew");
  map.refresh();updateNavigationBanner();updateCrew();handler.postDelayed(this,1000);
 }};
 private void updateNavigationBanner(){
  if(navPrimary==null||navSecondary==null||routeSummary==null)return;
  String target=map.getTargetName(),instruction=map.getInstruction();float metres=map.getDistanceRemaining();int eta=map.getEtaSeconds();
  boolean routing=target!=null&&!target.isEmpty();
  navigationBanner.setVisibility(routing?View.VISIBLE:View.GONE);
  routeAction.setText(map.hasManualTarget()?"Stop":"Plan");
  routeAction.setContentDescription(map.hasManualTarget()?"Stop navigating to this place":"Open your trip plan");
  if(!routing){
   routeTitle.setText("Ready for the road");routeSummary.setText("Choose a place or plan your next stop");
   applyMapLayout();return;
  }
  if(instruction==null||instruction.isEmpty())instruction=map.routingReady()?"Calculating offline route…":"Preparing offline routing…";
  float turnDistance=map.getManeuverDistance();
  String cue=(turnDistance>0?formatMiles(turnDistance)+" · ":"")+"to "+target;
  navPrimary.setText(cue);navPrimary.setContentDescription(cue);
  String turn=instruction.toLowerCase(Locale.ROOT);
  directionIcon.setImageResource(turn.startsWith("turn left")?R.drawable.ic_turn_left:
          turn.startsWith("turn right")?R.drawable.ic_turn_right:
          turn.contains("u-turn")?R.drawable.ic_u_turn:
          turn.contains("arriv")?R.drawable.ic_location:R.drawable.ic_direction);
  navSecondary.setText(instruction);navSecondary.setContentDescription(instruction);
  if(metres>=0){
   routeTitle.setText(metres==0?"Arrived":eta>0?formatEta(eta):formatMiles(metres));
   routeSummary.setText(formatMiles(metres)+(eta>0?" · arrive "+clockEta(eta):"")+" · "+target);
  }else{
   routeTitle.setText(target);routeSummary.setText(instruction);
  }
  routeSummary.setContentDescription(routeSummary.getText());applyMapLayout();
 }
 private String formatMiles(float metres){if(metres<=0)return "0 m";if(metres<161)return Math.max(10,Math.round(metres/10f)*10)+" m";if(metres<1609)return Math.round(metres*1.09361f)+" yd";return String.format(Locale.UK,"%.1f mi",metres/1609.344f);}
 private String formatEta(int seconds){int m=Math.max(1,(int)Math.ceil(seconds/60.0));return m<60?m+" min":(m/60)+" h "+(m%60)+" min";}
 private String clockEta(int seconds){Calendar c=Calendar.getInstance();c.add(Calendar.SECOND,seconds);return String.format(Locale.UK,"%02d:%02d",c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE));}
 @Override protected void onStart(){super.onStart();map.onStart();}
 @Override protected void onResume(){super.onResume();map.onResume();handler.post(tick);}
 @Override protected void onPause(){handler.removeCallbacks(tick);ConvoyService s=ConvoyService.current;if(s!=null)s.ptt=false;pttPhysicalDown=false;map.onPause();super.onPause();}
 @Override protected void onStop(){map.onStop();super.onStop();}
 @Override public void onLowMemory(){super.onLowMemory();map.onLowMemory();}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);map.onSaveInstanceState(out);}
 @Override protected void onDestroy(){handler.removeCallbacks(tick);if(instrumentDialog!=null)instrumentDialog.dismiss();map.dispose();super.onDestroy();}
 private EditText field(LinearLayout box,String label,String value,boolean secret){box.addView(text(label,12,MUTED));EditText e=new EditText(this);e.setTextColor(TEXT);e.setSingleLine(true);e.setText(value);e.setTextSize(16);if(secret)e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);box.addView(e,new LinearLayout.LayoutParams(-1,dp(50)));return e;}
 private String randomKey(){byte[] b=new byte[8];new SecureRandom().nextBytes(b);StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x));return s.toString();}
 private void setup(){ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(12),dp(22),dp(8));scroll.addView(box);EditText name=field(box,"Your name (max 32 characters)",getPreferences(0).getString("name",""),false);box.addView(text("Your car icon",12,MUTED));Spinner car=new Spinner(this);String[] carNames={"MX-5 NC PRHT","Toyota GT86","Skyline R34","Supra Mk4","Honda NSX","Impreza GC8","Lancer Evo VI","AE86 Trueno","350Z","MX-5 NA","Generic car"};String[] carKeys={"nc","gt86","r34","supra","nsx","gc8","evo6","ae86","z350","na","generic"};car.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,carNames));String savedCar=getPreferences(0).getString("car","nc");for(int ci=0;ci<carKeys.length;ci++)if(carKeys[ci].equals(savedCar))car.setSelection(ci);box.addView(car);Spinner role=new Spinner(this);role.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Host a convoy","Join a convoy"}));box.addView(role);Spinner transport=new Spinner(this);transport.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Wi-Fi hotspot / local network","Paired Bluetooth"}));box.addView(transport);EditText key=field(box,"Group key • everyone must enter exactly the same key",randomKey(),true);box.addView(text("Keep the generated key when hosting. Replace it with your host’s key when joining.",12,MUTED));EditText ip=field(box,"Host IPv4 address • needed only to join Wi-Fi",getPreferences(0).getString("ip",""),false);ip.setHint("e.g. 192.168.43.1");ip.setHintTextColor(MUTED);CheckBox hotspot=new CheckBox(this);hotspot.setText("Create a local Wi-Fi hotspot when hosting");hotspot.setTextColor(TEXT);hotspot.setChecked(true);box.addView(hotspot);box.addView(text("Set up while parked. This build needs real-device range and audio testing before a remote trip.",12,MUTED));
 AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Bring your crew together").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Continue",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String n=name.getText().toString().trim(),k=key.getText().toString(),addr=ip.getText().toString().trim();boolean hosting=role.getSelectedItemPosition()==0,bt=transport.getSelectedItemPosition()==1;String chosenCar=carKeys[car.getSelectedItemPosition()];if(n.isEmpty()||n.length()>32){name.setError("Enter 1–32 characters");return;}if(k.length()<12||k.length()>128){key.setError("Use a key of 12–128 characters");return;}if(!hosting&&!bt&&!validIp(addr)){ip.setError("Enter the host’s IPv4 address from its Crew tab");return;}getPreferences(0).edit().putString("name",n).putString("ip",addr).putString("car",chosenCar).apply();Runnable begin=()->{if(bt&&!hosting)pickBluetooth(n,k,chosenCar);else launch(n,k,hosting,bt,addr,hotspot.isChecked(),chosenCar);};dialog.dismiss();permissions(bt,hosting&&!bt&&hotspot.isChecked(),begin);}));dialog.show();}
 private boolean validIp(String value){String[] a=value.split("\\.");if(a.length!=4)return false;try{for(String x:a){int n=Integer.parseInt(x);if(n<0||n>255)return false;}return true;}catch(Exception e){return false;}}
 private void permissions(boolean bt,boolean hotspot,Runnable action){ArrayList<String> needed=new ArrayList<>(Arrays.asList(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.RECORD_AUDIO));if(Build.VERSION.SDK_INT>=31&&bt)needed.add(Manifest.permission.BLUETOOTH_CONNECT);if(Build.VERSION.SDK_INT>=33){if(hotspot)needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);needed.add(Manifest.permission.POST_NOTIFICATIONS);}ArrayList<String> missing=new ArrayList<>();for(String p:needed)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);if(missing.isEmpty())action.run();else{permissionAction=action;requestPermissions(missing.toArray(new String[0]),5);}}
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
  super.onRequestPermissionsResult(code,permissions,grants);
  if(code!=5)return;
  boolean permitted=permissions.length>0&&grants.length==permissions.length;
  for(int i=0;i<permissions.length&&i<grants.length;i++){
   if(!Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])&&grants[i]!=PackageManager.PERMISSION_GRANTED)permitted=false;
  }
  // Approximate-only location and cancelled permission requests cannot start a driving/voice trip.
  permitted=permitted&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED&&
          checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
  Runnable action=permissionAction;permissionAction=null;
  if(permitted&&action!=null){action.run();return;}
  new AlertDialog.Builder(this).setTitle("Permissions needed")
          .setMessage("Convoy needs precise Location, Microphone, and the permissions for your selected connection. Enable them in Android app settings, then start the trip again.")
          .setPositiveButton("App settings",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))))
          .setNegativeButton("Close",null).show();
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
 private void launch(String name,String key,boolean host,boolean bt,String address,boolean hotspot,String car){try{Intent i=new Intent(this,ConvoyService.class).putExtra("name",name).putExtra("key",key).putExtra("host",host).putExtra("bluetooth",bt).putExtra("address",address).putExtra("hotspot",hotspot).putExtra("car",car);startForegroundService(i);tab=host&&!bt?2:0;renderTab();}catch(Exception e){alert(e.getMessage());}}
 private void renderPlan(LinearLayout box){
  ArrayList<TripPlan.Waypoint> points=TripPlan.load(this);map.setWaypoints(points);
  addSection(box,"Plan the day","Save the places your convoy may need even when mobile data disappears. The order below is the stop order shown on the map. Share the plan before leaving so each phone has a local copy.");
  LinearLayout actions=new LinearLayout(this);Button add=button("+ Add stop",true),paste=button("Paste plan",false);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(48),1);ap.setMargins(0,0,dp(7),0);actions.addView(add,ap);actions.addView(paste,new LinearLayout.LayoutParams(0,dp(48),1));spaced(box,actions,48);
  LinearLayout actions2=new LinearLayout(this);Button share=button("Share plan",false),clear=button("Clear",false);LinearLayout.LayoutParams ap2=new LinearLayout.LayoutParams(0,dp(48),1);ap2.setMargins(0,0,dp(7),0);actions2.addView(share,ap2);actions2.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));spaced(box,actions2,48);
  add.setOnClickListener(v->waypointDialog(null));paste.setOnClickListener(v->pastePlan());share.setOnClickListener(v->sharePlan());clear.setOnClickListener(v->{if(points.isEmpty())return;new AlertDialog.Builder(this).setTitle("Clear trip plan?").setMessage("This removes all saved stops from this phone.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{TripPlan.save(this,new ArrayList<TripPlan.Waypoint>());refreshPlan();renderTab();}).show();});
  if(points.isEmpty()){addSection(box,"No stops yet","Add a fuel stop, food/rest break, viewpoint, meet-up/rejoin point, hazard or any custom POI. If a live GPS fix is available it will be pre-filled when you add a stop.");return;}
  int index=1;for(TripPlan.Waypoint w:points){final TripPlan.Waypoint point=w;LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(13),dp(10),dp(13),dp(10));card.setBackground(shape(CARD,14));TextView h=text(index+"  "+TripPlan.symbol(w.type)+" "+w.name,16,w.done?MUTED:TEXT);h.setTypeface(null,Typeface.BOLD);card.addView(h);String meta=w.type+(w.done?" • visited":" • planned")+"\n"+String.format(Locale.US,"%.5f, %.5f",w.lat,w.lon);ConvoyService service=ConvoyService.current;if(service!=null&&service.fix!=null){float[] out=new float[1];android.location.Location.distanceBetween(service.fix.getLatitude(),service.fix.getLongitude(),w.lat,w.lon,out);meta+="\nFrom your last fix: "+formatDistance(out[0]);}card.addView(text(meta,12,MUTED));LinearLayout row=new LinearLayout(this);Button show=button("Show",false),done=button(w.done?"Undo":"Done",false),edit=button("Edit",false),del=button("×",false);for(Button b:new Button[]{show,done,edit,del}){LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(48),1);bp.setMargins(0,dp(7),dp(5),0);row.addView(b,bp);}card.addView(row);show.setOnClickListener(v->{map.focus(point.lat,point.lon);tab=0;renderTab();});done.setOnClickListener(v->{point.done=!point.done;TripPlan.upsert(this,point);refreshPlan();renderTab();});edit.setOnClickListener(v->waypointDialog(point));del.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Delete "+point.name+"?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,x)->{TripPlan.remove(this,point.id);refreshPlan();renderTab();}).show());spaced(box,card,-2);index++;}
  addSection(box,"Map behaviour","Convoy routes to the next unvisited stop over the bundled offline road graph. The thin dashed line still shows the overall stop sequence; the thick blue line is the calculated driving route.");
 }
 private String formatDistance(float metres){return metres<1000?Math.round(metres)+" m":String.format(Locale.US,"%.1f km",metres/1000f);}
 private void refreshPlan(){map.setWaypoints(TripPlan.load(this));map.refresh();}
 private void waypointDialog(TripPlan.Waypoint existing){
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(8),dp(22),0);String[] types={"Fuel","Food","Rest","Viewpoint","Meet-up","Hazard","Other"};Spinner type=new Spinner(this);type.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,types));box.addView(text("Type",12,MUTED));box.addView(type);if(existing!=null){for(int i=0;i<types.length;i++)if(types[i].equals(existing.type))type.setSelection(i);}
  EditText name=field(box,"Name",existing==null?"":existing.name,false);double lat0=Double.NaN,lon0=Double.NaN;if(existing!=null){lat0=existing.lat;lon0=existing.lon;}else{ConvoyService service=ConvoyService.current;if(service!=null&&service.fix!=null){lat0=service.fix.getLatitude();lon0=service.fix.getLongitude();}}
  EditText lat=field(box,"Latitude",Double.isNaN(lat0)?"":String.format(Locale.US,"%.6f",lat0),false),lon=field(box,"Longitude",Double.isNaN(lon0)?"":String.format(Locale.US,"%.6f",lon0),false);int numberType=android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED;lat.setInputType(numberType);lon.setInputType(numberType);box.addView(text("Current GPS coordinates are pre-filled when Convoy has a fix. You can also enter coordinates manually while planning at home.",12,MUTED));
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Add planned stop":"Edit planned stop").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError("Enter a name");return;}try{double la=Double.parseDouble(lat.getText().toString().trim()),lo=Double.parseDouble(lon.getText().toString().trim());if(!Double.isFinite(la)||!Double.isFinite(lo)||la< -90||la>90||lo< -180||lo>180)throw new NumberFormatException();String t=(String)type.getSelectedItem();TripPlan.Waypoint w=existing==null?TripPlan.create(n,t,la,lo):new TripPlan.Waypoint(existing.id,n,t,la,lo,existing.created,existing.done);TripPlan.upsert(this,w);refreshPlan();dialog.dismiss();renderTab();}catch(Exception e){lat.setError("Check latitude / longitude");lon.setError("Check latitude / longitude");}}));dialog.show();
 }
 private void sharePlan(){ArrayList<TripPlan.Waypoint> points=TripPlan.load(this);if(points.isEmpty()){alert("Add at least one planned stop first.");return;}Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_SUBJECT,"Convoy trip plan");send.putExtra(Intent.EXTRA_TEXT,TripPlan.encode(points));startActivity(Intent.createChooser(send,"Share Convoy plan"));}
 private void pastePlan(){
  EditText input=new EditText(this);input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setHint("Paste CONVOY_PLAN_V1 text here");input.setMinLines(8);input.setGravity(Gravity.TOP);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setPadding(dp(14),dp(10),dp(14),dp(10));try{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm!=null&&cm.hasPrimaryClip()&&cm.getPrimaryClip()!=null&&cm.getPrimaryClip().getItemCount()>0){CharSequence c=cm.getPrimaryClip().getItemAt(0).coerceToText(this);if(c!=null&&c.toString().contains(TripPlan.HEADER))input.setText(c);}}catch(Exception ignored){}
  FrameLayout wrap=new FrameLayout(this);wrap.setPadding(dp(18),0,dp(18),0);wrap.addView(input,new FrameLayout.LayoutParams(-1,dp(260)));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Import shared trip plan").setMessage("This replaces the plan saved on this phone.").setView(wrap).setNegativeButton("Cancel",null).setPositiveButton("Import",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{ArrayList<TripPlan.Waypoint> points=TripPlan.decode(input.getText().toString());if(points.isEmpty()){input.setError("No valid Convoy stops found");return;}TripPlan.save(this,points);refreshPlan();dialog.dismiss();renderTab();Toast.makeText(this,points.size()+" stops imported",Toast.LENGTH_LONG).show();}));dialog.show();
 }
 private void chooseMap(){if(importing){alert("A map is already being imported.");return;}new AlertDialog.Builder(this).setTitle("Offline vector map pack").setMessage("Convoy already includes a rich Shrewsbury vector map with roads, buildings, place names and POIs.\n\nYou can replace the basemap with another Protomaps-compatible PMTiles (.pmtiles) file. The bundled Shrewsbury place-search and routing data remain tied to the Shrewsbury region in this build.").setNegativeButton("Cancel",null).setPositiveButton("Choose PMTiles",(d,w)->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,9);}).show();}
 @Override protected void onActivityResult(int req,int result,Intent data){
  super.onActivityResult(req,result,data);
  if(req!=9||result!=RESULT_OK||data==null||importing)return;
  Uri uri=data.getData();if(uri==null)return;
  importing=true;Toast.makeText(this,"Importing vector map…",Toast.LENGTH_LONG).show();
  new Thread(()->{
   File temporary=new File(getFilesDir(),"region-import.pmtiles");
   try{
    String title="Imported vector region";
    try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
     if(cursor!=null&&cursor.moveToFirst()){
      String name=cursor.getString(0);if(name!=null&&!name.trim().isEmpty())title=name;
     }
    }
    try(InputStream input=getContentResolver().openInputStream(uri);FileOutputStream output=new FileOutputStream(temporary)){
     if(input==null)throw new IOException("Could not read that file");
     byte[] buffer=new byte[65536];long total=0;int count;
     while((count=input.read(buffer))!=-1){
      total+=count;if(total>2L*1024*1024*1024)throw new IOException("Use a map pack no larger than 2 GB");
      output.write(buffer,0,count);
     }
     output.getFD().sync();
    }
    validatePmtiles(temporary);
    File target=new File(getFilesDir(),"region.pmtiles");
    // Both files live in the same private directory. Atomic replacement keeps the old pack intact on failure.
    try{
     java.nio.file.Files.move(temporary.toPath(),target.toPath(),
             java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }catch(java.nio.file.AtomicMoveNotSupportedException e){
     throw new IOException("This device could not safely replace the map pack. Your existing map is still available.",e);
    }
    String finalTitle=title;
    runOnUiThread(()->{
     importing=false;getSharedPreferences("convoy",0).edit().putString("mapName",finalTitle).apply();
     if(!isFinishing()&&!isDestroyed()){
      map.load();map.recenter();Toast.makeText(this,"Vector map ready",Toast.LENGTH_LONG).show();
     }
    });
   }catch(Exception e){
    temporary.delete();
    runOnUiThread(()->{importing=false;alert("Map import failed: "+e.getMessage());});
   }
  },"map-import").start();
 }
 private void validatePmtiles(File file)throws IOException{
  final int headerSize=127;long fileSize=file.length();
  if(fileSize<headerSize)throw new IOException("The PMTiles header is incomplete");
  byte[] header=new byte[headerSize];
  try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
   input.readFully(header);
  }
  if(!"PMTiles".equals(new String(header,0,7,java.nio.charset.StandardCharsets.US_ASCII)))
   throw new IOException("That file is not a PMTiles archive");
  if((header[7]&255)!=3)throw new IOException("Use a PMTiles version 3 archive");
  java.nio.ByteBuffer fields=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
  String[] sectionNames={"root directory","metadata","leaf directories","tile data"};
  long[][] sections=new long[4][2];
  for(int i=0;i<sections.length;i++){
   long offset=fields.getLong(8+i*16),length=fields.getLong(16+i*16);
   // Subtraction keeps malformed unsigned 64-bit offsets/lengths from wrapping past the file limit.
   if(offset<0||length<0||offset>fileSize||length>fileSize-offset||
           (length>0&&offset<headerSize))throw new IOException("Invalid PMTiles "+sectionNames[i]+" range");
   sections[i][0]=offset;sections[i][1]=length;
  }
  if(sections[0][1]==0||sections[3][1]==0)throw new IOException("The PMTiles archive has no map tiles");
  for(int i=0;i<sections.length;i++)for(int j=i+1;j<sections.length;j++){
   if(sections[i][1]>0&&sections[j][1]>0&&
           sections[i][0]<sections[j][0]+sections[j][1]&&sections[j][0]<sections[i][0]+sections[i][1])
    throw new IOException("PMTiles data sections overlap");
  }
  long addressedTiles=fields.getLong(72),tileEntries=fields.getLong(80),tileContents=fields.getLong(88);
  if(addressedTiles<=0||tileEntries<=0||tileContents<=0||tileEntries>addressedTiles||tileContents>tileEntries)
   throw new IOException("Invalid PMTiles tile counts");
  int clustered=header[96]&255,internalCompression=header[97]&255,tileCompression=header[98]&255;
  if(clustered>1||internalCompression<1||internalCompression>4||tileCompression<1||tileCompression>4)
   throw new IOException("Invalid PMTiles compression or clustering flags");
  if((header[99]&255)!=1)throw new IOException("Choose a vector MVT PMTiles pack for Convoy’s basemap");
  int minZoom=header[100]&255,maxZoom=header[101]&255,centerZoom=header[118]&255;
  if(minZoom>maxZoom||maxZoom>31||centerZoom>31)throw new IOException("Invalid PMTiles zoom levels");
  int minLon=fields.getInt(102),minLat=fields.getInt(106),maxLon=fields.getInt(110),maxLat=fields.getInt(114);
  int centerLon=fields.getInt(119),centerLat=fields.getInt(123);
  if(minLon< -1800000000||maxLon>1800000000||minLat< -900000000||maxLat>900000000||
          minLon>maxLon||minLat>maxLat||centerLon< -1800000000||centerLon>1800000000||
          centerLat< -900000000||centerLat>900000000)throw new IOException("Invalid PMTiles geographic bounds");
 }
 private void searchPlaces(){final EditText q=new EditText(this);q.setSingleLine(true);q.setTextColor(TEXT);q.setHintTextColor(MUTED);q.setHint("fuel, cafe, supermarket, castle…");q.setPadding(dp(14),0,dp(14),0);AlertDialog d=new AlertDialog.Builder(this).setTitle("Search offline places").setMessage("Search the Shrewsbury place index stored on this phone.").setView(q).setNegativeButton("Cancel",null).setPositiveButton("Search",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{String s=q.getText().toString().trim();if(s.length()<2){q.setError("Type at least two characters");return;}d.dismiss();map.search(s,this::showSearchResults);}));d.show();}
 private void showSearchResults(ArrayList<PlaceInfo> places){if(places==null||places.isEmpty()){alert("No matching places were found in the offline Shrewsbury index.");return;}String[] labels=new String[places.size()];for(int i=0;i<places.size();i++){PlaceInfo p=places.get(i);String sub=p.subtitle();labels[i]=p.name+(sub.isEmpty()?"":"\n"+sub);}new AlertDialog.Builder(this).setTitle("Offline places").setItems(labels,(d,w)->{PlaceInfo p=places.get(w);map.focus(p.lat,p.lon);placeSheet(p);}).setNegativeButton("Close",null).show();}
 private void placeSheet(PlaceInfo p){if(p==null)return;StringBuilder b=new StringBuilder();if(p.category!=null&&!p.category.isEmpty())b.append(p.category.replace('_',' '));ConvoyService svc=ConvoyService.current;if(svc!=null&&svc.fix!=null){float[] d=new float[1];android.location.Location.distanceBetween(svc.fix.getLatitude(),svc.fix.getLongitude(),p.lat,p.lon,d);b.append(b.length()>0?" • ":"").append(formatDistance(d[0])).append(" away");}if(p.address!=null&&!p.address.isEmpty())b.append(b.length()>0?"\n":"").append(p.address);if(p.brand!=null&&!p.brand.isEmpty()&&!p.brand.equalsIgnoreCase(p.name))b.append("\nBrand: ").append(p.brand);if(p.cuisine!=null&&!p.cuisine.isEmpty())b.append("\nCuisine: ").append(p.cuisine.replace(';',','));if(p.operator!=null&&!p.operator.isEmpty())b.append("\nOperator: ").append(p.operator);if(p.openingHours!=null&&!p.openingHours.isEmpty())b.append("\nHours: ").append(p.openingHours);if(p.wheelchair!=null&&!p.wheelchair.isEmpty())b.append("\nWheelchair: ").append(p.wheelchair);if(p.internetAccess!=null&&!p.internetAccess.isEmpty())b.append("\nInternet: ").append(p.internetAccess);if(p.phone!=null&&!p.phone.isEmpty())b.append("\nPhone: ").append(p.phone);if(p.website!=null&&!p.website.isEmpty())b.append("\nWeb: ").append(p.website);if(p.source!=null&&!p.source.isEmpty())b.append("\n\nData: ").append(p.source);showPlaceDetails(p,b.length()==0?String.format(Locale.UK,"%.5f, %.5f",p.lat,p.lon):b.toString());}
 private void showPlaceDetails(PlaceInfo p,String information){
  ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);
  content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(6),dp(22),dp(8));
  TextView info=text(information,14,MUTED);info.setLineSpacing(dp(3),1);info.setTextIsSelectable(true);
  spaced(content,info,-2);scroll.addView(content);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle(p.name).setView(scroll).setNegativeButton("Close",null).create();
  Button navigate=button("Navigate here",true);spaced(content,navigate,48);
  navigate.setOnClickListener(v->{dialog.dismiss();map.navigateTo(p);tab=0;renderTab();updateNavigationBanner();});
  LinearLayout saveRow=new LinearLayout(this);Button save=button("Save stop",false),meet=button("Meet here",false);
  LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(0,dp(48),1);saveParams.rightMargin=dp(8);
  saveRow.addView(save,saveParams);saveRow.addView(meet,new LinearLayout.LayoutParams(0,dp(48),1));spaced(content,saveRow,48);
  save.setOnClickListener(v->{savePlace(p,false);dialog.dismiss();});meet.setOnClickListener(v->{savePlace(p,true);dialog.dismiss();});
  Button show=button("Show on map",false);spaced(content,show,48);
  show.setOnClickListener(v->{dialog.dismiss();map.focus(p.lat,p.lon);tab=0;renderTab();});dialog.show();
 }
 private void savePlace(PlaceInfo p,boolean meeting){
  TripPlan.Waypoint stop=TripPlan.create(p.name,meeting?"Meet-up":placeType(p.category),p.lat,p.lon);
  TripPlan.upsert(this,stop);if(meeting)map.clearManualTarget();refreshPlan();
  Toast.makeText(this,"Added to trip plan",Toast.LENGTH_SHORT).show();
 }
 private String placeType(String category){String c=category==null?"":category.toLowerCase(Locale.ROOT);if(c.contains("fuel")||c.contains("charging"))return "Fuel";if(c.contains("restaurant")||c.contains("cafe")||c.contains("food")||c.contains("pub"))return "Food";if(c.contains("view")||c.contains("attraction")||c.contains("museum")||c.contains("castle"))return "Viewpoint";if(c.contains("parking")||c.contains("toilet")||c.contains("hotel")||c.contains("camp"))return "Rest";return "Other";}
 private void alert(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("Convoy").setMessage(message).setPositiveButton("OK",null).show();}
}

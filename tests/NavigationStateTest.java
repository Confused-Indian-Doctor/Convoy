package com.convoy.offline;

/** Exercises the same Back controller used by the Android activity. */
public final class NavigationStateTest {
 private static int checks;
 private static void check(boolean pass,String label){if(!pass)throw new AssertionError(label);checks++;}
 public static void main(String[] args){
  NavigationState state=new NavigationState();check(state.screen()==NavigationState.HOME,"cold launch Home");
  check(!state.back(),"Home delegates normal Back");
  for(int screen:new int[]{NavigationState.MAP,NavigationState.ROUTES,NavigationState.CREW,NavigationState.OFFLINE_MAPS,NavigationState.GUIDE}){
   state.open(screen);check(state.back()&&state.screen()==NavigationState.HOME,"main destination Back Home");
   check(!state.back(),"no Back loop");
  }
  state.open(NavigationState.ROUTES);state.open(NavigationState.GPX_DETAIL);
  check(state.back()&&state.screen()==NavigationState.ROUTES,"GPX detail Back route library");
  check(state.back()&&state.screen()==NavigationState.HOME,"library Back Home");
  state.open(NavigationState.MAP);state.open(NavigationState.GPX_DETAIL);
  check(state.back()&&state.screen()==NavigationState.HOME,"map GPX detail Back Home");
  state.restore(NavigationState.GPX_DETAIL,NavigationState.ROUTES);
  check(state.back()&&state.screen()==NavigationState.ROUTES,"restored detail retains parent");
  state.open(NavigationState.MAP);state.open(NavigationState.CREW);check(state.back()&&state.screen()==NavigationState.HOME,"cross-tab Back Home");
  System.out.println("PASS "+checks+" navigation checks");
 }
}

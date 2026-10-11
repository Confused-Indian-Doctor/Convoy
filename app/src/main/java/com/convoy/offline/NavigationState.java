package com.convoy.offline;

/** Small, loop-free navigation model. Dialog windows handle Back before this model. */
public final class NavigationState {
 public static final int HOME=-1, MAP=0, ROUTES=1, CREW=2, GUIDE=3, OFFLINE_MAPS=4, GPX_DETAIL=5;
 private int screen=HOME, detailParent=ROUTES;
 public int screen(){return screen;}
 public void open(int destination){
  if(destination<HOME||destination>GPX_DETAIL)throw new IllegalArgumentException("Unknown screen");
  if(destination==GPX_DETAIL&&screen!=GPX_DETAIL)detailParent=screen==ROUTES?ROUTES:HOME;
  screen=destination;
 }
 public boolean back(){
  if(screen==HOME)return false;
  screen=screen==GPX_DETAIL?detailParent:HOME;
  return true;
 }
 public int detailParent(){return detailParent;}
 public void restore(int screen,int parent){
  open(screen);detailParent=parent==ROUTES?ROUTES:HOME;
 }
}

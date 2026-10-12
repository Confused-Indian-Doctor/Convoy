package com.convoy.offline;

import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;

/** Standard QR rendering, preflighted against the camera decoder before showing a bearer invite. */
public final class ConvoyQr {
 private ConvoyQr(){}
 public static BitMatrix encode(String invitation,int size)throws WriterException{
  EnumMap<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);
  hints.put(EncodeHintType.MARGIN,4);hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);
  // Dense random signed data can confuse finder selection. Every candidate is an ISO QR mask;
  // the payload never changes. Keep one that this same scanner locates in all four orientations.
  for(int mask=-1;mask<8;mask++){
   if(Thread.currentThread().isInterrupted())throw new WriterException("Invitation rendering cancelled");
   if(mask>=0)hints.put(EncodeHintType.QR_MASK_PATTERN,mask);
   BitMatrix modules=new MultiFormatWriter().encode(invitation,BarcodeFormat.QR_CODE,0,0,hints);
   int scale=Math.max(1,size/modules.getWidth()),width=modules.getWidth()*scale;
   BitMatrix image=new BitMatrix(width,width);
   for(int y=0;y<modules.getHeight();y++)for(int x=0;x<modules.getWidth();x++)if(modules.get(x,y))image.setRegion(x*scale,y*scale,scale,scale);
   if(scannable(image,invitation))return image;
  }
  throw new WriterException("Could not render a reliably scannable invitation");
 }
 private static boolean scannable(BitMatrix image,String payload){
  int size=image.getWidth();EnumMap<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,true);
  for(int rotation=0;rotation<4;rotation++){
   int[] pixels=new int[size*size];
   for(int y=0;y<size;y++)for(int x=0;x<size;x++){
    int sx=x,sy=y;for(int turn=0;turn<rotation;turn++){int old=sx;sx=sy;sy=size-1-old;}
    pixels[y*size+x]=image.get(sx,sy)?0xff000000:0xffffffff;
   }
   try{Result result=new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size,size,pixels))),hints);if(!payload.equals(result.getText()))return false;}
   catch(ReaderException failure){return false;}
  }
  return true;
 }
}

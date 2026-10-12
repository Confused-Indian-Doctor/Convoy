package com.convoy.offline;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;

/** Shared screen/test encoding with a standard four-module quiet zone and resilient correction. */
public final class ConvoyQr {
 private ConvoyQr(){}
 public static BitMatrix encode(String invitation,int size)throws WriterException{
  EnumMap<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);
  hints.put(EncodeHintType.MARGIN,4);
  hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);
  BitMatrix modules=new MultiFormatWriter().encode(invitation,BarcodeFormat.QR_CODE,0,0,hints);
  int scale=Math.max(1,size/modules.getWidth());int width=modules.getWidth()*scale;
  BitMatrix image=new BitMatrix(width,width);
  for(int y=0;y<modules.getHeight();y++)for(int x=0;x<modules.getWidth();x++)if(modules.get(x,y))image.setRegion(x*scale,y*scale,scale,scale);
  return image;
 }
}

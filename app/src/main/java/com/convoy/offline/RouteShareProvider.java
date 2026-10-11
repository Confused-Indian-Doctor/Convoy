package com.convoy.offline;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only, explicitly URI-granted sharing of original saved GPX files. */
public final class RouteShareProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 private File routeFile(Uri uri)throws FileNotFoundException{
  List<String> parts=uri.getPathSegments();
  if(parts.size()!=2||!"route".equals(parts.get(0))||!parts.get(1).matches("[0-9a-fA-F-]{36}"))
   throw new FileNotFoundException("Unknown route");
  try{
   File directory=new File(getContext().getFilesDir(),"routes").getCanonicalFile();
   File file=new File(directory,parts.get(1)+".gpx").getCanonicalFile();
   if(!directory.equals(file.getParentFile())||!file.isFile())throw new FileNotFoundException("Route no longer exists");
   return file;
  }catch(IOException e){throw new FileNotFoundException("Route is unavailable");}
 }
 @Override public String getType(Uri uri){return "application/gpx+xml";}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String sortOrder){
  try{
   File file=routeFile(uri);String name="Convoy route.gpx";
   for(RouteLibrary.Entry entry:new RouteLibrary(getContext().getFilesDir()).list())
    if(entry.id.equals(uri.getLastPathSegment())){name=entry.name.replaceAll("[\\\\/:*?\"<>|]","_")+".gpx";break;}
   String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
   MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];
   for(int i=0;i<columns.length;i++){
    if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]=name;
    else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=file.length();
   }
   cursor.addRow(row);return cursor;
  }catch(IOException e){return new MatrixCursor(projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection);}
 }
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  if(!"r".equals(mode))throw new FileNotFoundException("Saved routes are read-only");
  return ParcelFileDescriptor.open(routeFile(uri),ParcelFileDescriptor.MODE_READ_ONLY);
 }
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read-only routes");}
 @Override public int delete(Uri uri,String selection,String[] arguments){throw new UnsupportedOperationException("Read-only routes");}
 @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments){throw new UnsupportedOperationException("Read-only routes");}
}

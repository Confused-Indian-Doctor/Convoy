package com.convoy.offline;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.StatFs;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** WorkManager owns downloads beyond Activity/process lifetime and handles bounded transient retries. */
public final class MapDownloadWorker extends Worker {
    public static final String BYTES="bytes",TOTAL="total",PHASE="phase",ERROR="error",REGION="region";
    private static final String CHANNEL="convoy-map-downloads";
    private volatile MapPackSource source;
    public MapDownloadWorker(@NonNull Context context,@NonNull WorkerParameters parameters){super(context,parameters);}
    public static String workName(String region){return "convoy-map-"+region;}
    public static void enqueue(Context context,MapPackSource source){
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(MapDownloadWorker.class).setInputData(encode(source))
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("convoy-maps").build();
        WorkManager.getInstance(context).enqueueUniqueWork(workName(source.regionId),ExistingWorkPolicy.KEEP,request);
    }
    public static void cancel(Context context,String region){WorkManager.getInstance(context).cancelUniqueWork(workName(region));}
    @NonNull @Override public Result doWork(){
        try{
            source=decode(getInputData());
            setForegroundAsync(foreground(0,"Preparing download")).get();
            File directory=MapDownloads.downloadDirectory(getApplicationContext());
            File complete=new MapDownloadEngine().download(source,directory,
                    ()->new StatFs(getApplicationContext().getFilesDir().getPath()).getAvailableBytes(),this::isStopped,
                    (bytes,total,phase)->{setProgressAsync(new Data.Builder().putLong(BYTES,bytes).putLong(TOTAL,total).putString(PHASE,phase).build());setForegroundAsync(foreground(bytes,phase));});
            if(isStopped())return Result.failure();
            setForegroundAsync(foreground(source.bytes,"Installing map")).get();
            if(isStopped())return Result.failure();
            new MapPackStore(getApplicationContext().getFilesDir()).installDownloaded(complete,source,this::isStopped);
            setProgressAsync(new Data.Builder().putLong(BYTES,source.bytes).putLong(TOTAL,source.bytes).putString(PHASE,"Installed").build());
            return Result.success(new Data.Builder().putString(REGION,source.regionId).build());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Result.retry();}
        catch(MapDownloadEngine.PermanentFailure invalid){return failure(invalid.getMessage());}
        catch(IOException temporary){if(isStopped()||Thread.currentThread().isInterrupted())return Result.failure();if(getRunAttemptCount()<5){setProgressAsync(new Data.Builder().putString(PHASE,"Connection interrupted • retrying").putString(ERROR,safeError(temporary)).build());return Result.retry();}return failure(safeError(temporary));}
        catch(Exception failure){return failure("Could not start the map download. Open Convoy and try again.");}
    }
    private Result failure(String message){return Result.failure(new Data.Builder().putString(ERROR,message==null?"Map download failed":message).build());}
    private static String safeError(IOException error){String message=error.getMessage();if(message==null)return "Map download failed";return message.length()>240?message.substring(0,240):message;}
    private ForegroundInfo foreground(long bytes,String phase){
        Context context=getApplicationContext();NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Offline map downloads",NotificationManager.IMPORTANCE_LOW));
        int percent=source.bytes>0?(int)Math.min(100,bytes*100/source.bytes):0;
        PendingIntent open=PendingIntent.getActivity(context,41,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel=WorkManager.getInstance(context).createCancelPendingIntent(getId());
        Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(source.name+" offline map").setContentText(phase+" • "+MapPack.formatBytes(bytes)+" / "+MapPack.formatBytes(source.bytes))
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,percent,false)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Cancel",cancel).build()).build();
        int id=source.regionId.hashCode()&0x7fffffff;
        return Build.VERSION.SDK_INT>=29?new ForegroundInfo(id,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC):new ForegroundInfo(id,notification);
    }
    static Data encode(MapPackSource s){return new Data.Builder().putString("id",s.id).putString(REGION,s.regionId).putString("country",s.countryId)
            .putString("name",s.name).putString("version",s.version).putString("date",s.dataDate).putString("url",s.downloadUrl).putLong(TOTAL,s.bytes)
            .putString("sha",s.sha256).putInt("min",s.minZoom).putInt("max",s.maxZoom).putDoubleArray("bounds",new double[]{s.bounds.south,s.bounds.west,s.bounds.north,s.bounds.east})
            .putString("attribution",s.attribution).putBoolean("pois",s.poisAvailable).build();}
    static MapPackSource decode(Data d)throws IOException{
        double[] b=d.getDoubleArray("bounds");if(b==null||b.length!=4)throw new MapDownloadEngine.PermanentFailure("Invalid download request");
        return new MapPackSource(d.getString("id"),d.getString(REGION),d.getString("country"),d.getString("name"),d.getString("version"),d.getString("date"),d.getString("url"),d.getLong(TOTAL,0),
                d.getString("sha"),d.getInt("min",-1),d.getInt("max",-1),new MapPackCatalog.Bounds(b[0],b[1],b[2],b[3]),d.getString("attribution"),true,d.getBoolean("pois",false),false,false);
    }
}

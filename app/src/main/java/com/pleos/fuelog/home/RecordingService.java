package com.pleos.fuelog.home;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.diagnostics.AaosVehicleDiagnosticService;
import org.json.JSONObject;

/** Started from a visible activity, never from boot or a background activity launch. */
public final class RecordingService extends Service {
    public static final String STOP="com.pleos.fuelog.STOP_RECORDING";
    private static final int RUNNING=4101, CHARGED=4102;
    private HomeRecorder recorder;
    private AaosVehicleDiagnosticService source;
    private NotificationManager notifications;
    private boolean safe;
    private String noticeOwner="";
    @Override public void onCreate() {
        super.onCreate();
        notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("recording","차량 기록 수집",NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel("charge_complete","충전 기록 확인",NotificationManager.IMPORTANCE_DEFAULT));
        Intent stop=new Intent(this,RecordingService.class).setAction(STOP);
        Notification notification=new Notification.Builder(this,"recording").setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("차량 기록 수집 중").setContentText("운행·충전 관측을 저장합니다")
                .setContentIntent(open("", "")).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(null,"수집 중지",PendingIntent.getService(this,0,stop,PendingIntent.FLAG_IMMUTABLE)).build()).build();
        try {startForeground(RUNNING,notification);}
        catch(RuntimeException blocked) {
            HomeRecorder.get(this).storageError="수집 서비스 실행이 허용되지 않았습니다 · 앱 설정을 확인해 주세요";
            stopSelf();return;
        }
        recorder=HomeRecorder.get(this);
        source=new AaosVehicleDiagnosticService(this);
        recorder.setEvSource(source);
        source.start((allowed,detail)-> {safe=allowed;if(!allowed) notifications.cancel(CHARGED);});
        recorder.resume(this::refreshNotice);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(recorder==null) {stopSelf();return START_NOT_STICKY;}
        if(intent!=null && STOP.equals(intent.getAction())) {
            getSharedPreferences("recording_service",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();
            stopSelf();return START_NOT_STICKY;
        }
        if(!getSharedPreferences("recording_service",MODE_PRIVATE).getBoolean("enabled",true)
                || !ConnectionSession.get(this).linked) {stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    private PendingIntent open(String interval,String owner) {
        Intent intent=new Intent(this,CarHomeActivity.class).setAction("com.pleos.fuelog.CHARGE_RECORD")
                .setData(android.net.Uri.parse("fuelog://charge/"+interval))
                .putExtra("chargeInterval",interval).putExtra("chargeOwner",owner);
        return PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private void refreshNotice() {
        String owner=recorder.verifiedOwner();
        if(!safe || !recorder.parked() || owner.isEmpty() || !noticeOwner.isEmpty() && !noticeOwner.equals(owner)) notifications.cancel(CHARGED);
        if(!safe || !recorder.parked() || owner.isEmpty()) return;
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED) return;
        JSONObject pending=recorder.pendingChargeNotice();
        if(pending==null) return;
        try {
            notifications.notify(CHARGED,new Notification.Builder(this,"charge_complete").setSmallIcon(android.R.drawable.ic_menu_save)
                    .setContentTitle("충전 기록을 확인해 주세요").setContentText("충전 중 배터리 증가 기록이 저장됐습니다")
                    .setContentIntent(open(pending.getString("intervalId"),owner)).setAutoCancel(true)
                    .setOnlyAlertOnce(true).setTimeoutAfter(60000).setVisibility(Notification.VISIBILITY_SECRET).build());
            noticeOwner=owner;
            recorder.markChargeNotified(pending);
        } catch(Exception error) {recorder.storageError="충전 알림 저장 실패";}
    }
    @Override public void onDestroy() {
        if(recorder!=null) recorder.pause();
        if(source!=null) source.close();
        if(notifications!=null) notifications.cancel(CHARGED);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}

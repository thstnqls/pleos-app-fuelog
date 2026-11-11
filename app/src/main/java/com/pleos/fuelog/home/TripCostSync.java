package com.pleos.fuelog.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.location.PleosLocationClient;
import java.io.IOException;
import org.json.JSONObject;

/** Cost request coordinates stay in memory for at most 30 seconds. */
final class TripCostSync {
    private final Context context;
    private final HomeRecorder recorder;
    private final ConnectionSession connection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PleosLocationClient location;
    private okhttp3.Call call;
    private JSONObject evidence;
    private String id = "", owner = "";
    private int generation;
    private boolean pending;
    TripCostSync(Context context, HomeRecorder recorder, ConnectionSession connection) {
        this.context=context; this.recorder=recorder; this.connection=connection;
    }
    void refresh(JSONObject snapshot) {
        if(pending || call!=null || snapshot.optLong("endedAt")<=0 || snapshot.has("savedEvCost")) return;
        String subject=recorder.verifiedOwner();
        if(subject.isEmpty() || !subject.equals(snapshot.optString("owner")) || recorder.serverSummary(snapshot.optString("intervalId"))==null) return;
        id=snapshot.optString("intervalId"); owner=subject;
        int version=generation;
        try {
            call=connection.request("GET","recording-intervals/"+id+"/ev-cost",null,"",new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) {main.post(() -> {
                    if(!current(version)) return;
                    try {
                        JSONObject result=new JSONObject(response.getBody());
                        if("SAVED".equals(result.optString("status"))) recorder.applySavedTripCost(id,result);
                    } catch(Exception invalid) { }
                    close();
                });}
                public void onHttpError(ApiClient.ApiResponse response) {done();}
                public void onFailure(IOException error) {done();}
                private void done() {main.post(() -> {if(current(version)) close();});}
            });
        } catch(Exception unavailable) {close();}
    }
    void prepare(JSONObject snapshot, boolean automatic) {
        close();
        try {
            if (snapshot.optBoolean("chargingOnly") || snapshot.optLong("endedAt") <= 0
                    || !Double.isFinite(new TripSummary(snapshot).consumedKwh()) || snapshot.has("savedEvCost")) return;
            String subject=recorder.verifiedOwner();
            if (subject.isEmpty() || !subject.equals(snapshot.optString("owner"))) return;
            id=snapshot.getString("intervalId"); owner=subject;
            if (context.checkSelfPermission(PleosLocationClient.PERMISSION)!=PackageManager.PERMISSION_GRANTED) { status("LOCATION_REQUIRED"); return; }
            if (!automatic && recorder.serverSummary(id)==null) { status("SYNC_REQUIRED"); return; }
            int version=generation;
            pending=true;
            status("CALCULATING");
            location=new PleosLocationClient(context);
            long requested=System.currentTimeMillis();
            main.postDelayed(() -> { if (version==generation) { status("LOCATION_REQUIRED"); close(); } },30000);
            location.getCurrentLocation(new PleosLocationClient.Callback() {
                public void onSuccess(PleosLocationClient.Position position) {
                    if (!current(version)) return;
                    try {
                        long received=System.currentTimeMillis();
                        if (!position.isFromUpdate() || position.getTimestampMillis()<requested
                                || received-requested>10000) { status("LOCATION_REQUIRED"); close(); return; }
                        recorder.rememberLocation(id,owner,automatic?"TRIP_END":"CURRENT_REFERENCE",position.getLatitude(),position.getLongitude(),position.getTimestampMillis());
                        evidence=new JSONObject().put("latitude",position.getLatitude()).put("longitude",position.getLongitude())
                                .put("measuredAt",RecordingPayloads.iso(position.getTimestampMillis())).put("receivedAt",RecordingPayloads.iso(received))
                                .put("source","PLEOS_CURRENT_UPDATE").put("locationBasis",automatic?"TRIP_END":"CONFIRMATION");
                        sendIfReady();
                    } catch (Exception invalid) { status("LOCATION_REQUIRED"); close(); }
                }
                public void onError(PleosLocationClient.ErrorCode code,Exception cause) { if(current(version)) { status("LOCATION_REQUIRED"); close(); } }
            });
        } catch (Exception invalid) { status("CALCULATION_UNAVAILABLE"); close(); }
    }
    void summaryReady(String localId, JSONObject summary) {
        if (summary.optJSONObject("savedEvCost")!=null) {
            try { recorder.applySavedTripCost(localId,new JSONObject().put("tripId",localId).put("owner",recorder.verifiedOwner())
                    .put("status","SAVED").put("cost",summary.getJSONObject("savedEvCost"))); }
            catch (Exception invalid) { }
            if (id.equals(localId)) close();
        } else if (id.equals(localId)) sendIfReady();
    }
    private boolean current(int version) {return version==generation && owner.equals(recorder.verifiedOwner()) && !owner.isEmpty();}
    private void sendIfReady() {
        if (evidence==null || call!=null || recorder.serverSummary(id)==null || !current(generation)) return;
        int version=generation;
        try {
            call=connection.request("POST","recording-intervals/"+id+"/ev-cost",evidence,"",new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) {main.post(() -> {
                    if(!current(version)) return;
                    try {recorder.applySavedTripCost(id,new JSONObject(response.getBody()));}
                    catch(Exception invalid) {status("CALCULATION_UNAVAILABLE");}
                    close();
                });}
                public void onHttpError(ApiClient.ApiResponse response) {failed();}
                public void onFailure(IOException error) {failed();}
                private void failed() {main.post(() -> {if(current(version)) {status("CALCULATION_UNAVAILABLE");close();}});}
            });
        } catch(Exception unavailable) {status("CALCULATION_UNAVAILABLE");close();}
    }
    private void status(String value) {if(!id.isEmpty() && owner.equals(recorder.verifiedOwner())) recorder.tripCostStatus(id,value);}
    boolean busy(String tripId) {return pending && id.equals(tripId) && owner.equals(recorder.verifiedOwner());}
    void close() {
        pending=false;
        generation++; main.removeCallbacksAndMessages(null);
        if(location!=null) location.close(); if(call!=null) call.cancel();
        location=null; call=null; evidence=null; id=""; owner="";
    }
}

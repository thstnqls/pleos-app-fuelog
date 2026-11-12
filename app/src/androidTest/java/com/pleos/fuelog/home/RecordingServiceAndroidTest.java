package com.pleos.fuelog.home;

import android.content.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingServiceAndroidTest {
    @Test public void closingScreenKeepsCollectorUntilExplicitServiceStop() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Context target=instrumentation.getTargetContext();
        String prefix="service-life-"+UUID.randomUUID();
        File directory=new File(target.getCacheDir(),prefix);assertTrue(directory.mkdirs());
        Context isolated=new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() {return directory;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {return target.getSharedPreferences(prefix+name,mode);}
        };
        var sessions=ConnectionSession.class.getDeclaredField("instance");sessions.setAccessible(true);
        var recorders=HomeRecorder.class.getDeclaredField("instance");recorders.setAccessible(true);
        Object oldSession=sessions.get(null),oldRecorder=recorders.get(null);
        var enabled=target.getSharedPreferences("recording_service",0);
        boolean prior=enabled.getBoolean("enabled",true);
        HomeRecorder[] recorder={null};
        try(MockWebServer server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {return new MockResponse().setResponseCode(503);}
            });server.start();
            var constructor=ConnectionSession.class.getDeclaredConstructor(Context.class,ApiClient.class);constructor.setAccessible(true);
            ConnectionSession connection=constructor.newInstance(isolated,new ApiClient(server.url("/").toString()));
            connection.linked=true;connection.authenticatedSubject="service-owner";
            isolated.getSharedPreferences("home_recording",0).edit().putString("owner","service-owner").commit();
            sessions.set(null,connection);enabled.edit().putBoolean("enabled",true).commit();
            instrumentation.runOnMainSync(()-> {try {recorder[0]=new HomeRecorder(isolated);recorders.set(null,recorder[0]);}catch(Exception error){throw new AssertionError(error);}});
            try(ActivityScenario<CarHomeActivity> screen=ActivityScenario.launch(CarHomeActivity.class)) {
                assertRunning(instrumentation,recorder[0],true);
                screen.moveToState(Lifecycle.State.CREATED);
                assertRunning(instrumentation,recorder[0],true);
                instrumentation.runOnMainSync(()->target.stopService(new Intent(target,RecordingService.class)));
                assertRunning(instrumentation,recorder[0],false);
            }
        } finally {
            instrumentation.runOnMainSync(()->target.stopService(new Intent(target,RecordingService.class)));
            sessions.set(null,oldSession);recorders.set(null,oldRecorder);
            enabled.edit().putBoolean("enabled",prior).commit();
            target.deleteSharedPreferences(prefix+"home_recording");
            File[] files=directory.listFiles();if(files!=null) for(File file:files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
    private void assertRunning(android.app.Instrumentation instrumentation,HomeRecorder recorder,boolean expected) throws Exception {
        var visible=HomeRecorder.class.getDeclaredField("visible");visible.setAccessible(true);
        long until=android.os.SystemClock.elapsedRealtime()+5000;
        boolean[] value={!expected};
        while(android.os.SystemClock.elapsedRealtime()<until) {
            instrumentation.runOnMainSync(()-> {try {value[0]=visible.getBoolean(recorder);}catch(Exception error){throw new AssertionError(error);}});
            if(value[0]==expected) return;
            android.os.SystemClock.sleep(25);
        }
        assertEquals("Collector lifetime must follow the service",expected,value[0]);
    }
}

package com.pleos.fuelog.diagnostics;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.EvBattery;
import ai.pleos.playground.vehicle.api.Fuel;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real read-only SDK/property probe. No synthetic sensor values or vehicle write calls. */
public class EnergyAvailabilityAndroidTest {
    private final JSONObject sdk = new JSONObject();
    private synchronized void sdkResult(String method,String stage,Object value) {
        try {
            JSONObject row=sdk.optJSONObject(method);if(row==null) {row=new JSONObject();sdk.put(method,row);}
            row.put(stage,value==null?JSONObject.NULL:value);
        } catch(Exception invalid) {throw new AssertionError(invalid);}
    }
    private String error(Throwable error) {
        if(error instanceof InvocationTargetException && error.getCause()!=null) error=error.getCause();
        return error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());
    }
    private void invoke(Object domain,Class<?> api,String getter,String capability,CountDownLatch pending) {
        Function1<Object,Unit> success=value->{sdkResult(getter,"value",value);pending.countDown();return Unit.INSTANCE;};
        Function1<Exception,Unit> failure=ex->{sdkResult(getter,"error",error(ex));pending.countDown();return Unit.INSTANCE;};
        try {api.getMethod(getter,Function1.class,Function1.class).invoke(domain,success,failure);}
        catch(Exception ex) {sdkResult(getter,"error",error(ex));pending.countDown();}
        Function1<Exception,Unit> capFailure=ex->{sdkResult(getter,"capabilityError",error(ex));pending.countDown();return Unit.INSTANCE;};
        Function1<Object,Unit> cap=value->{sdkResult(getter,"capability",String.valueOf(value));pending.countDown();return Unit.INSTANCE;};
        try {
            if(getter.equals("getEvChargeCurrentDrawLimit")) {
                Function2<Object,Object,Unit> extended=(config,values)->{sdkResult(getter,"capability",String.valueOf(config));pending.countDown();return Unit.INSTANCE;};
                api.getMethod(capability,Function2.class,Function1.class).invoke(domain,extended,capFailure);
            } else api.getMethod(capability,Function1.class,Function1.class).invoke(domain,cap,capFailure);
        } catch(Exception ex) {sdkResult(getter,"capabilityError",error(ex));pending.countDown();}
    }
    private JSONObject aaos(CarPropertyManager manager) throws Exception {
        JSONObject rows=new JSONObject();
        for(String name:new String[]{"EV_CHARGE_STATE","EV_CHARGE_SWITCH","EV_CHARGE_PORT_CONNECTED","EV_CHARGE_PORT_OPEN",
                "EV_CHARGE_CURRENT_DRAW_LIMIT","EV_CHARGE_PERCENT_LIMIT","EV_CHARGE_TIME_REMAINING","EV_BATTERY_INSTANTANEOUS_CHARGE_RATE",
                "EV_BATTERY_LEVEL","EV_CURRENT_BATTERY_CAPACITY","INFO_EV_BATTERY_CAPACITY","INSTANTANEOUS_EV_EFFICIENCY",
                "FUEL_LEVEL","INFO_FUEL_CAPACITY","INSTANTANEOUS_FUEL_ECONOMY","INFO_FUEL_TYPE","GEAR_SELECTION","PERF_ODOMETER"}) {
            JSONObject row=new JSONObject();rows.put(name,row);
            int id;
            try {id=VehiclePropertyIds.class.getField(name).getInt(null);}
            catch(NoSuchFieldException absent) {row.put("definition","ABSENT_ON_PLATFORM");continue;}
            try {
                var config=manager.getCarPropertyConfig(id);
                if(config==null) row.put("config","NOT_EXPOSED");
                else row.put("access",config.getAccess()).put("changeMode",config.getChangeMode()).put("areas",new JSONArray(config.getAreaIds()));
            } catch(Exception ex) {row.put("configError",error(ex));}
            try {
                var value=manager.getProperty(id,0);
                if(value==null) row.put("value",JSONObject.NULL);
                else {
                    row.put("status",value.getStatus()).put("timestampNanos",value.getTimestamp());
                    Object raw=value.getValue();
                    row.put("value",raw!=null&&raw.getClass().isArray()?new JSONArray(raw):raw==null?JSONObject.NULL:raw);
                }
            } catch(Exception ex) {row.put("error",error(ex));}
        }
        return rows;
    }
    @Test public void actualPleosAndAaosEnergyReadings() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        JSONObject report=new JSONObject().put("measuredAt",java.time.Instant.now().toString()).put("androidApi",android.os.Build.VERSION.SDK_INT)
                .put("userId",android.os.Process.myUid()/100000).put("sdkVersion","2.3.2");
        Vehicle[] vehicle={null};
        instrumentation.runOnMainSync(()->{vehicle[0]=new Vehicle(context);vehicle[0].initialize();});
        // Wait for a real SDK response rather than treating initialize() as service readiness.
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        boolean ready=false;
        while(!ready && android.os.SystemClock.elapsedRealtime()<deadline) {
            CountDownLatch sample=new CountDownLatch(1);boolean[] ok={false};
            instrumentation.runOnMainSync(()->vehicle[0].getOdometer().getCurrentDistanceDriven(value->{ok[0]=value!=null;sample.countDown();return Unit.INSTANCE;},ex->{sample.countDown();return Unit.INSTANCE;}));
            sample.await(500,TimeUnit.MILLISECONDS);ready=ok[0];
            if(!ready) android.os.SystemClock.sleep(200);
        }
        report.put("sdkReadinessOdometer",ready);
        String[] ev={"CumulativeEvEfficiency","CurrentEvEfficiency","EvBatteryStateOfCharge","InstantaneousEvEfficiency","SinceChargeEvEfficiency","EvChargeCurrentDrawLimit","EvChargeSwitchState"};
        String[] fuel={"CumulativeFuelEconomy","FuelPercent","CurrentFuelEconomy","InstantaneousFuelEconomy","SinceChargeFuelEconomy"};
        CountDownLatch sdkDone=new CountDownLatch((ev.length+fuel.length)*2);
        instrumentation.runOnMainSync(()->{
            for(String name:ev) invoke(vehicle[0].getEvBattery(),EvBattery.class,"get"+name,"check"+name.replace("SwitchState","Switch")+"Capabilities",sdkDone);
            for(String name:fuel) invoke(vehicle[0].getFuel(),Fuel.class,"get"+name,"check"+name+"Capabilities",sdkDone);
        });
        report.put("allSdkCallbacks",sdkDone.await(20,TimeUnit.SECONDS)).put("pleos",sdk);
        Car[] car={null};CarPropertyManager[] manager={null};CountDownLatch connected=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->car[0]=Car.createCar(context,new Handler(Looper.getMainLooper()),Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,(c,available)->{
            if(available) manager[0]=(CarPropertyManager)c.getCarManager(Car.PROPERTY_SERVICE);connected.countDown();
        }));
        try {
            assertTrue("Car service connection",connected.await(10,TimeUnit.SECONDS));assertNotNull(manager[0]);
            JSONObject permissions=new JSONObject();
            for(String permission:new String[]{"android.car.permission.CAR_ENERGY","android.car.permission.CAR_ENERGY_PORTS","pleos.car.permission.CAR_ENERGY","pleos.car.permission.CONTROL_CAR_ENERGY"}) {
                JSONObject p=new JSONObject().put("granted",context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED);
                try {p.put("protection",context.getPackageManager().getPermissionInfo(permission,0).getProtection());}catch(Exception ex) {p.put("definition", "ABSENT");}
                permissions.put(permission,p);
            }
            report.put("permissionsBefore",permissions).put("aaosBefore",aaos(manager[0]));
            if(context.checkSelfPermission("android.car.permission.CAR_ENERGY")!=PackageManager.PERMISSION_GRANTED) {
                String command="pm grant --user "+(android.os.Process.myUid()/100000)+" "+context.getPackageName()+" android.car.permission.CAR_ENERGY";
                try(var fd=instrumentation.getUiAutomation().executeShellCommand(command);var in=new java.io.FileInputStream(fd.getFileDescriptor())) {report.put("permissionGrantOutput",new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            }
            report.put("energyPermissionAfter",context.checkSelfPermission("android.car.permission.CAR_ENERGY")==PackageManager.PERMISSION_GRANTED)
                    .put("aaosAfter",aaos(manager[0]));
            Files.write(new File(context.getCacheDir(),"energy-availability.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // UTP may uninstall the target after the probe; retain read-only energy evidence in test artifacts.
            for(String section:new String[]{"pleos","aaosBefore","aaosAfter","permissionsBefore"}) {
                JSONObject values=report.getJSONObject(section);
                var keys=values.keys();while(keys.hasNext()) {
                    String key=keys.next();
                    android.util.Log.i("FuelogEnergyProbe",new JSONObject().put("section",section).put("name",key).put("reading",values.get(key)).toString());
                }
            }
            android.util.Log.i("FuelogEnergyProbe",new JSONObject().put("section","meta").put("measuredAt",report.getString("measuredAt"))
                    .put("userId",report.getInt("userId")).put("sdkReady",report.getBoolean("sdkReadinessOdometer"))
                    .put("energyPermissionAfter",report.getBoolean("energyPermissionAfter")).toString());
        } finally {instrumentation.runOnMainSync(()->car[0].disconnect());}
    }
}

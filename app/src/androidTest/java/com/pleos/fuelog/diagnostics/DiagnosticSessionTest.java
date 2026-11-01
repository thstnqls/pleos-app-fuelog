package com.pleos.fuelog.diagnostics;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiagnosticSessionTest {
    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private void drain() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }

    @Test public void replacedRequestCannotOverwriteNewResult() {
        AtomicReference<String> last = new AtomicReference<>();
        DiagnosticSession[] session = new DiagnosticSession[1];
        main(() -> {
            session[0] = new DiagnosticSession((key, source, state, value, time) -> last.set(value));
            DiagnosticSession.Request old = session[0].begin("fuel", "old", "%", false);
            DiagnosticSession.Request current = session[0].begin("fuel", "new", "%", false);
            current.value(60);
            old.value(10);
        });
        drain();
        assertEquals("60 %", last.get());
        main(() -> session[0].close());
    }

    @Test public void closeAndStopDiscardLateCallbacks() {
        AtomicInteger count = new AtomicInteger();
        DiagnosticSession.Request[] request = new DiagnosticSession.Request[1];
        main(() -> {
            DiagnosticSession session = new DiagnosticSession((a, b, c, d, e) -> count.incrementAndGet());
            request[0] = session.begin("stream", "sdk", "%", true);
            session.stop("stream");
            request[0].value(80);
            session.close();
        });
        drain();
        int before = count.get();
        request[0].value(90);
        drain();
        assertEquals(before, count.get());
        assertEquals(2, count.get()); // waiting and stopped only
    }

    @Test public void nullIsNoDataAndInvalidPercentIsNotAReading() {
        AtomicReference<String> state = new AtomicReference<>();
        DiagnosticSession[] session = new DiagnosticSession[1];
        main(() -> {
            session[0] = new DiagnosticSession((a, b, status, d, e) -> state.set(status));
            session[0].begin("fuel", "sdk", "%", false).value(null);
        });
        drain();
        assertEquals("값 없음", state.get());
        main(() -> session[0].begin("fuel", "sdk", "%", false).value(101));
        drain();
        assertEquals("값 오류", state.get());
        main(() -> session[0].close());
    }

    @Test public void unreadableCapabilityNeverInvokesGetter() {
        AtomicInteger getterCalls = new AtomicInteger();
        AtomicReference<String> state = new AtomicReference<>();
        DiagnosticSession[] session = new DiagnosticSession[1];
        main(() -> {
            session[0] = new DiagnosticSession((a, b, status, d, e) -> state.set(status));
            PleosReadSupport support = new PleosReadSupport(session[0]) {};
            support.<Integer>read("fuel", "%", (success, failure) ->
                    success.invoke(new CapabilityConfig(AccessType.NONE)), (success, failure) -> {
                        getterCalls.incrementAndGet();
                        success.invoke(0);
                    });
        });
        drain();
        assertEquals(0, getterCalls.get());
        assertEquals("읽기 불가", state.get());
        main(() -> session[0].close());
    }
}

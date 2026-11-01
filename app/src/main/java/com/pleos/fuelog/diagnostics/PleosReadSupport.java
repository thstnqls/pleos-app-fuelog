package com.pleos.fuelog.diagnostics;

import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

abstract class PleosReadSupport {
    interface CapabilityCall {
        void call(Function1<CapabilityConfig, Unit> success, Function1<Exception, Unit> failure);
    }
    interface ValueCall<T> {
        void call(Function1<T, Unit> success, Function1<Exception, Unit> failure);
    }
    final DiagnosticSession session;
    PleosReadSupport(DiagnosticSession session) { this.session = session; }

    <T> void read(String key, String unit, CapabilityCall capability, ValueCall<T> getter) {
        read(key, unit, false, capability, getter);
    }

    <T> void read(String key, String unit, boolean allowNegative, CapabilityCall capability, ValueCall<T> getter) {
        DiagnosticSession.Request request = session.begin(key, "Pleos Vehicle 2.3.2", unit, false, allowNegative);
        check(request, capability, () -> getter.call(value -> {
            request.value(value);
            return Unit.INSTANCE;
        }, error -> {
            request.fail(error);
            return Unit.INSTANCE;
        }));
    }

    void check(DiagnosticSession.Request request, CapabilityCall capability, Runnable onReadable) {
        try {
            capability.call(config -> {
                request.dispatch(() -> {
                    if (config == null) {
                        request.status("값 없음", "capability가 null");
                        return;
                    }
                    AccessType access = config.getAccessType();
                    request.access(access.name());
                    if (access == AccessType.READ || access == AccessType.READ_WRITE) {
                        onReadable.run();
                    } else {
                        request.status("읽기 불가", "capability " + access + " · 미지원/접근 제한");
                    }
                });
                return Unit.INSTANCE;
            }, error -> {
                request.fail(error);
                return Unit.INSTANCE;
            });
        } catch (RuntimeException error) { request.fail(error); }
    }
}

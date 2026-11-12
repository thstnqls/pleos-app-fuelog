package com.pleos.fuelog.connection;
import org.junit.Test;
import static org.junit.Assert.*;
public class VehicleVinTest {
    @Test public void reportsUnsupportedLengthSeparatelyFromMissingVin() {
        assertTrue(com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.invalidVinStatus("12345678").contains("8자리 수신"));
        assertTrue(com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.invalidVinStatus("").contains("값 없음"));
        assertTrue(com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.invalidVinStatus("1HGCM82633A00435I").contains("허용되지 않는 문자"));
    }
    @Test public void normalizesValidVinAndRejectsUnavailableOrMalformedValues() {
        assertEquals("SDK-VIN01", com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.normalizeVin(" sdk-vin01 "));
        assertEquals("123456789", com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.normalizeVin("123456789"));
        assertEquals("1HGCM82633A004352", com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.normalizeVin(" 1hgcm82633a004352 "));
        for (String value : new String[]{null, "", "UNKNOWN", "invalid", "invaild", "<invalid>", "[invalid]", "invaild!!", "undefined", "000", "12345678", "1234567890", "SDK VIN01", "1HGCM82633A00435I", "1HGCM82633A00435O"})
            assertEquals("", com.pleos.fuelog.diagnostics.CarInfoDiagnosticService.normalizeVin(value));
    }
}

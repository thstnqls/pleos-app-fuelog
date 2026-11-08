package com.pleos.fuelog.home;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class MaintenancePlanEditorTest {
    @Test public void ownerFormBuildsSharedCycleAndOptionalServiceEvidence() throws Exception {
        JSONObject body = MaintenancePlanEditor.body(" 타이어 ", "10000", "12", "2026-01-01", "45000", "edit-1");
        assertTrue(body.getJSONObject("plan").getBoolean("shared"));
        assertEquals("USER", body.getJSONObject("plan").getString("basis"));
        assertEquals("타이어", body.getJSONObject("plan").getString("name"));
        assertEquals("2026-01-01", body.getString("lastServiceDate"));
        assertEquals("edit-1", body.getString("operationId"));
        assertThrows(Exception.class, () -> MaintenancePlanEditor.body("", "1000", "", "", "", "edit"));
        assertThrows(Exception.class, () -> MaintenancePlanEditor.body("타이어", "", "", "", "", "edit"));
        assertThrows(Exception.class, () -> MaintenancePlanEditor.body("타이어", "0", "", "", "", "edit"));
        assertThrows(Exception.class, () -> MaintenancePlanEditor.body("타이어", "1000", "1.5", "", "", "edit"));
        assertThrows(Exception.class, () -> MaintenancePlanEditor.body("타이어", "1000", "12", "2026-02-30", "", "edit"));
    }
}

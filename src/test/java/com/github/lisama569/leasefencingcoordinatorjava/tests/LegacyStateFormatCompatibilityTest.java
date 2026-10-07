package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.core.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.model.LeaseView;
import com.github.lisama569.leasefencingcoordinatorjava.store.FileLockStateStore;
import com.github.lisama569.leasefencingcoordinatorjava.time.SystemClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyStateFormatCompatibilityTest {
    @Test
    void oldFourFieldJsonLoadsAsUnreleased(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("state.json");
        Files.writeString(f, """
                {
                  "old-lock" : {
                    "currentHolder" : "legacy-A",
                    "fencingToken" : 7,
                    "acquiredAtMillis" : 1000,
                    "expireAtMillis" : 9999999999999
                  }
                }
                """);
        LeaseCoordinator c = new LeaseCoordinator(new SystemClock(), new FileLockStateStore(f));
        LeaseView v = c.inspect("old-lock");
        assertTrue(v.held());
        assertFalse(v.released(), "旧版本无 releasedAtMillis 字段 => 默认 0 => 未让出");
        assertEquals("legacy-A", v.holderId());
        assertEquals(7L, v.fencingToken());
        System.out.println("[TEST] 旧格式4字段JSON兼容加载 => held=true released=false holder=legacy-A token=7");
    }
}

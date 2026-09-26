package com.github.lisama569.leasefencingcoordinatorjava.tests;

import com.github.lisama569.leasefencingcoordinatorjava.lease.LeaseCoordinator;
import com.github.lisama569.leasefencingcoordinatorjava.support.FakeClock;
import com.github.lisama569.leasefencingcoordinatorjava.support.LeaseTestSupport;
import com.github.lisama569.leasefencingcoordinatorjava.web.LeaseApiExceptionHandler;
import com.github.lisama569.leasefencingcoordinatorjava.web.LeaseController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level contract: status codes and machine-readable reason codes. */
class LeaseControllerTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseControllerTest.class);

    @TempDir
    Path storeDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FakeClock clock = new FakeClock();
        LeaseCoordinator coordinator = LeaseTestSupport.newCoordinator(storeDir, clock);
        mockMvc = MockMvcBuilders.standaloneSetup(new LeaseController(coordinator))
                .setControllerAdvice(new LeaseApiExceptionHandler())
                .build();
    }

    @Test
    void acquireThenContendThenInvalidYieldDistinctHttpResults() throws Exception {
        String winner = mockMvc.perform(post("/api/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockName\":\"api-lock\",\"holderId\":\"A\",\"ttlMillis\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fencingToken").value(1))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP acquire winner response: {}", winner);

        String conflict = mockMvc.perform(post("/api/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockName\":\"api-lock\",\"holderId\":\"B\",\"ttlMillis\":5000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("LOCK_HELD_BY_OTHER"))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP acquire conflict response: {}", conflict);

        String badRequest = mockMvc.perform(post("/api/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockName\":\"api-lock\",\"holderId\":\"A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_PARAMETER"))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP acquire invalid response: {}", badRequest);

        String validate = mockMvc.perform(post("/api/leases/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockName\":\"api-lock\",\"holderId\":\"A\",\"fencingToken\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP validate response: {}", validate);
    }
}

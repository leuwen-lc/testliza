package com.ticketing.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketing.domain.DomainException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new ThrowawayController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void domainExceptionWith404StatusYieldsHttp404ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void domainExceptionWith422StatusYieldsHttp422ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/unprocessable"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void domainExceptionWith409StatusYieldsHttp409ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void dataAccessExceptionYieldsHttp503ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/data-access"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @RestController
    static class ThrowawayController {

        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundFixtureException();
        }

        @GetMapping("/test/unprocessable")
        void unprocessable() {
            throw new UnprocessableFixtureException();
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictFixtureException();
        }

        @GetMapping("/test/data-access")
        void dataAccess() {
            throw new DataAccessResourceFailureException("db down");
        }
    }

    static class NotFoundFixtureException extends DomainException {
        NotFoundFixtureException() {
            super(HttpStatus.NOT_FOUND, "not found");
        }
    }

    static class UnprocessableFixtureException extends DomainException {
        UnprocessableFixtureException() {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable");
        }
    }

    static class ConflictFixtureException extends DomainException {
        ConflictFixtureException() {
            super(HttpStatus.CONFLICT, "conflict");
        }
    }
}

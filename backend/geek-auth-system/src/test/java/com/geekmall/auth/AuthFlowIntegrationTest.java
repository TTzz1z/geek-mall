package com.geekmall.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void loginRefreshMenuAndLogoutRoundTrip() throws Exception {
        JsonNode tokens = login("admin");
        String accessToken = tokens.path("data").path("accessToken").asText();
        String refreshToken = tokens.path("data").path("refreshToken").asText();

        mockMvc.perform(get("/api/v1/admin/menus").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.path == '/system/users')]").exists());

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login/password")
                        .header("X-Device-Id", "integration-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Geek@123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}

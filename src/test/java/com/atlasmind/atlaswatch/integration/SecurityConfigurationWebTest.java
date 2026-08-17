package com.atlasmind.atlaswatch.integration;

import com.atlasmind.atlaswatch.config.JwtAuthFilter;
import com.atlasmind.atlaswatch.config.SecurityConfiguration;
import com.atlasmind.atlaswatch.controller.AuthController;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.service.AuthService;
import com.atlasmind.atlaswatch.service.JwtService;
import com.atlasmind.atlaswatch.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, JwtAuthFilter.class})
class SecurityConfigurationWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private RefreshTokenService refreshTokenService;
    @MockitoBean
    private AuthenticationProvider authenticationProvider;
    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void csrfEndpointIssuesTokenAndHeaderContract() throws Exception {
        mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"));
    }

    @Test
    void mutationWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","username":"alice","password":"StrongPass1!"}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authService);
    }

    @Test
    void validMutationWithCsrfTokenReachesController() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setEmail("alice@example.com");
        user.setUsername("alice");
        when(authService.signUp(any())).thenReturn(user);

        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","username":"alice","password":"StrongPass1!"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void invalidMutationReturnsBadRequestBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","username":"x","password":"short"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }
}

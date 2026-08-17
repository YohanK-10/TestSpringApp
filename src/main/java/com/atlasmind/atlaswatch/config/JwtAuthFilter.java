package com.atlasmind.atlaswatch.config;

import com.atlasmind.atlaswatch.service.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component // The class itself becomes a bean
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
          // Cross site requests don't include headers, you control when to send tokens. You have to manually add the headers,
        // so the attacker will have to have access to this info as the browser won't send the info by default.
        // Attacker does not need access to the cookies, browser sends it for them.
        // But with cookies, browser sends them automatically with every request, including malicious ones. So CSRF tokens are necessary.
       // Cookie: jwt=abc.def.ghi; theme=dark; session_id=xyz (name=value)
        Cookie[] cookies = request.getCookies();
        String jwtToken = null;
        final String theUserName;
        if (cookies != null) {
            for (Cookie cooks : cookies) {
                if (cooks.getName().equals("jwt")) {
                    jwtToken = cooks.getValue();
                    break;
                }
            }
        }
        if (jwtToken == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            theUserName = jwtService.extractUsername(jwtToken);
            if (theUserName != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userInfo = this.userDetailsService.loadUserByUsername(theUserName);
                if (jwtService.isTokenValid(jwtToken, userInfo)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userInfo, null, userInfo.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request)); // This is important!!
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
            filterChain.doFilter(request, response);
        } catch (NoSuchAlgorithmException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid encryption Algorithm");
        } catch (InvalidKeySpecException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid key specification");
        } catch (ExpiredJwtException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Token expired");
        } catch (JwtException | IllegalArgumentException ex) {
            // other JWT errors: malformed, unsupported, etc.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid token");
        }
    }
}


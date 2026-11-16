package dev.scriptforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scriptforge.domain.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiSecurity extends OncePerRequestFilter {
  private final ForgeProperties config;
  private final ObjectMapper mapper;

  public ApiSecurity(
      ForgeProperties config,
      ObjectMapper mapper,
      @Value("${server.address:127.0.0.1}") String address) {
    this.config = config;
    this.mapper = mapper;
    if ("MODEL".equals(config.mode())
        && !Set.of("127.0.0.1", "localhost", "::1").contains(address)
        && config.adminToken().length() < 24)
      throw new IllegalArgumentException(
          "Non-loopback MODEL servers require ADMIN_TOKEN of at least 24 characters");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "same-origin");
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
    if (!request.getRequestURI().startsWith("/api/")) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    // A same-origin check alone does not stop DNS rebinding against an unauthenticated local
    // service.
    if (config.adminToken().isBlank()
        && !Set.of("127.0.0.1", "localhost", "::1", "[::1]")
            .contains(request.getServerName().toLowerCase(java.util.Locale.ROOT))) {
      error(response, 403, "INVALID_HOST", "Tokenless workspaces only accept localhost hostnames");
      return;
    }
    if (request.getContentLengthLong() > 1_000_000) {
      error(response, 413, "BODY_TOO_LARGE", "Request body exceeds 1 MB");
      return;
    }
    if (!"/api/config".equals(request.getRequestURI()) && !config.adminToken().isBlank()) {
      String actual = request.getHeader("Authorization"),
          expected = "Bearer " + config.adminToken();
      if (actual == null
          || !MessageDigest.isEqual(
              actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
        error(response, 401, "UNAUTHORIZED", "Provide the configured admin bearer token");
        return;
      }
    }
    if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
      if (config.readOnly()) {
        error(response, 403, "READ_ONLY", "This server is read-only");
        return;
      }
      String origin = request.getHeader("Origin");
      if (origin != null) {
        try {
          URI uri = URI.create(origin);
          int originPort =
              uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
          if (!request.getScheme().equals(uri.getScheme())
              || !request.getServerName().equalsIgnoreCase(uri.getHost())
              || request.getServerPort() != originPort) {
            error(response, 403, "CROSS_ORIGIN", "Cross-origin writes are disabled");
            return;
          }
        } catch (IllegalArgumentException ex) {
          error(response, 403, "CROSS_ORIGIN", "Invalid origin");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }

  private void error(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    mapper.writeValue(response.getWriter(), Json.object("error", code, "message", message));
  }
}

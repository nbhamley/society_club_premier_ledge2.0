package com.scpl.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * v1.0 lived under /admin; it is now /society-admin (§7). GETs get a 301; other methods get a 308 so a POST
 * keeps its method and body. The remainder of the path is whitelisted, so the Location header can never
 * contain line breaks or point off-site.
 */
@Controller
public class LegacyRedirectController {

    private static final Pattern SAFE_REST = Pattern.compile("^[A-Za-z0-9/_.-]*$");

    @RequestMapping("/admin/{*rest}")
    public ResponseEntity<Void> redirect(@PathVariable String rest, HttpServletRequest request) {
        String target = request.getContextPath() + "/society-admin" + (SAFE_REST.matcher(rest).matches() ? rest : "");
        String query = request.getQueryString();
        URI location;
        try {
            location = URI.create(query == null ? target : target + "?" + query);
        } catch (IllegalArgumentException e) {
            location = URI.create(target); // a malformed query string is dropped, not trusted
        }
        boolean safe = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
        return ResponseEntity.status(safe ? HttpStatus.MOVED_PERMANENTLY : HttpStatus.PERMANENT_REDIRECT)
                .location(location).build();
    }
}
package com.scpl.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Error pages (§7). Implementing ErrorController replaces Spring Boot's own /error handling, which is what
 * we want since the whitelabel page is off. Mappings accept every HTTP method: a denied POST is forwarded
 * to /error/403 with its original method (see SecurityConfig.accessDeniedHandler).
 */
@Controller
public class ErrorPagesController implements ErrorController {

    @RequestMapping("/error")
    public String error(HttpServletRequest request, HttpServletResponse response, Model model) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return render(code instanceof Integer i ? i : 500, response, model);
    }

    @RequestMapping("/error/403")
    public String forbidden(HttpServletResponse response, Model model) {
        return render(403, response, model);
    }

    @RequestMapping("/error/404")
    public String notFound(HttpServletResponse response, Model model) {
        return render(404, response, model);
    }

    @RequestMapping("/error/500")
    public String serverError(HttpServletResponse response, Model model) {
        return render(500, response, model);
    }

    private static String render(int status, HttpServletResponse response, Model model) {
        response.setStatus(status);
        model.addAttribute("status", status);
        return switch (status) {
            case 403 -> {
                model.addAttribute("message", "You do not have permission to view this page.");
                yield "forbidden";
            }
            case 404 -> {
                model.addAttribute("message", "We could not find that page.");
                yield "not-found";
            }
            default -> {
                model.addAttribute("message", "Something went wrong on our side. Please try again.");
                yield "server-error";
            }
        };
    }
}
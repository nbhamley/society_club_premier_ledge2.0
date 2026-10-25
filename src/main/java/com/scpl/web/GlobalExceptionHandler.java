package com.scpl.web;

import com.scpl.dto.AccountViews.NavView;
import com.scpl.exception.ApprovalRequiredException;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.exception.QuotaExceededException;
import com.scpl.exception.ReadOnlyModeException;
import com.scpl.exception.TenantAccessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.validation.BindException;

import java.util.Map;

/**
 * Maps the exception types from the services to HTTP responses.
 *
 * <p>403 TenantAccessException, ReadOnlyModeException. 404 NotFoundException (also used for wrong-tenant ids).
 * Business-rule and quota errors on a POST become a flash message and a redirect back; on a GET they render the
 * page directly (a redirect back could loop). Anything unexpected is logged and shown as a generic 500: messages
 * from unknown exceptions are never shown to users.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final WebSupport web;
    private final GlobalModelAdvice navAdvice;

    @ExceptionHandler({TenantAccessException.class, ReadOnlyModeException.class})
    public Object forbidden(RuntimeException e, HttpServletRequest req) {
        return respond(req, HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public Object notFound(NotFoundException e, HttpServletRequest req) {
        return respond(req, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Object noResource(NoResourceFoundException e, HttpServletRequest req) {
        return respond(req, HttpStatus.NOT_FOUND, "That page does not exist");
    }

    @ExceptionHandler({BusinessRuleException.class, QuotaExceededException.class, ApprovalRequiredException.class})
    public Object businessRule(RuntimeException e, HttpServletRequest req) {
        if (WebSupport.wantsJson(req)) {
            return json(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        if (isSafeMethod(req)) {
            // A plan limit on a page the user cannot use is a 403; any other rule on a GET is a bad request.
            return page(req, e instanceof QuotaExceededException ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST,
                    e.getMessage());
        }
        return redirectBack(req, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object tooLarge(MaxUploadSizeExceededException e, HttpServletRequest req) {
        String message = "That file is too large.";
        if (WebSupport.wantsJson(req)) {
            return json(HttpStatus.PAYLOAD_TOO_LARGE, message);
        }
        return isSafeMethod(req) ? page(req, HttpStatus.PAYLOAD_TOO_LARGE, message) : redirectBack(req, message);
    }

    @ExceptionHandler(BindException.class)
    public Object invalidInput(BindException e, HttpServletRequest req) {
        String message = WebSupport.firstError(e.getBindingResult());
        if (WebSupport.wantsJson(req)) {
            return json(HttpStatus.BAD_REQUEST, message);
        }
        return isSafeMethod(req) ? page(req, HttpStatus.BAD_REQUEST, message) : redirectBack(req, message);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    public Object malformed(Exception e, HttpServletRequest req) {
        return respond(req, HttpStatus.BAD_REQUEST, "The request was not understood");
    }

    /**
     * Last resort. Spring's own web exceptions (405, 415, ...) implement ErrorResponse, so they keep their status;
     * security exceptions are rethrown to the security filter chain, which owns them.
     */
    @ExceptionHandler(Exception.class)
    public Object fallback(Exception e, HttpServletRequest req) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;
        }
        HttpStatusCode code = e instanceof ErrorResponse er ? er.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR;
        if (code.is5xxServerError()) {
            log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        }
        String message = code.is5xxServerError() ? "Something went wrong on our side. Please try again."
                : "That request could not be completed";
        return respond(req, HttpStatus.valueOf(code.value()), message);
    }

    // ------------------------------------------------------------------ rendering

    private Object respond(HttpServletRequest req, HttpStatus status, String message) {
        return WebSupport.wantsJson(req) ? json(status, message) : page(req, status, message);
    }

    private static ResponseEntity<Map<String, String>> json(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }

    private ModelAndView page(HttpServletRequest req, HttpStatus status, String message) {
        String view = switch (status) {
            case FORBIDDEN -> "forbidden";
            case NOT_FOUND -> "not-found";
            default -> "server-error";
        };
        ModelAndView mav = new ModelAndView(view);
        mav.setStatus(status);
        mav.addObject("status", status.value());
        mav.addObject("message", message);
        NavView nav = navAdvice.nav();
        mav.addObject("nav", nav);
        return mav;
    }

    private ModelAndView redirectBack(HttpServletRequest req, String message) {
        FlashMap out = RequestContextUtils.getOutputFlashMap(req);
        out.put(WebSupport.FLASH_ERROR, message);
        String fallback = req.getUserPrincipal() != null ? "/dashboard" : "/";
        // contextRelative=true, exposeModelAttributes=false: nothing from the model leaks into the URL.
        return new ModelAndView(new RedirectView(web.backTo(req, fallback), true, true, false));
    }

    private static boolean isSafeMethod(HttpServletRequest req) {
        String m = req.getMethod();
        return HttpMethod.GET.matches(m) || HttpMethod.HEAD.matches(m);
    }
}
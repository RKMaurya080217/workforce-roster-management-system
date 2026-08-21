package com.weeklyroster.controller;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class CustomErrorController implements ErrorController {

    private final ErrorAttributes errorAttributes;

    public CustomErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping(value = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handleError(HttpServletRequest request) {
        ServletWebRequest webRequest = new ServletWebRequest(request);
        Map<String, Object> attrs = errorAttributes.getErrorAttributes(webRequest,
                ErrorAttributeOptions.defaults().including(ErrorAttributeOptions.Include.MESSAGE, ErrorAttributeOptions.Include.EXCEPTION));

        int statusCode = 500;
        Object statusObj = attrs.get("status");
        if (statusObj instanceof Integer statusInt) {
            statusCode = statusInt;
        }

        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        String message = (String) attrs.getOrDefault("message", status.getReasonPhrase());
        if (message == null || message.isBlank() || "No message available".equalsIgnoreCase(message)) {
            message = status.getReasonPhrase();
        }
        String path = (String) attrs.getOrDefault("path", request.getRequestURI());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", path);

        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}

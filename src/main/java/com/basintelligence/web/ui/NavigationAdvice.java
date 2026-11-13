package com.basintelligence.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import jakarta.servlet.http.HttpServletRequest;

/** Exposes the current path so the navigation bar can highlight the active screen. */
@ControllerAdvice(annotations = Controller.class)
public class NavigationAdvice {

    @ModelAttribute("currentPath")
    String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}

package com.monstergym.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.auth.EmployeeRepository;
import com.monstergym.common.ApiErrorBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Si un empleado fue desactivado mientras tenía sesión abierta, su siguiente petición cierra la sesión (401). */
@Component
public class ActiveEmployeeInterceptor implements HandlerInterceptor {

    private final EmployeeRepository employees;
    private final ObjectMapper mapper;

    public ActiveEmployeeInterceptor(EmployeeRepository employees, ObjectMapper mapper) {
        this.employees = employees;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof EmployeePrincipal p)) {
            return true;
        }
        boolean active = employees.findById(p.id()).map(e -> e.active()).orElse(false);
        if (active) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiErrorBody.NO_AUTENTICADO);
        return false;
    }
}

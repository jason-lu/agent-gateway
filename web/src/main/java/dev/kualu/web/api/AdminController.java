package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.AdminUserResponse;
import dev.kualu.web.config.SecurityProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final SecurityProperties securityProperties;

    public AdminController(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminUserResponse> users() {
        return securityProperties.users().stream()
                .map(user -> new AdminUserResponse(user.username(), user.roles()))
                .toList();
    }
}

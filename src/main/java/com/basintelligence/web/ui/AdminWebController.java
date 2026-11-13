package com.basintelligence.web.ui;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.machine.MachineService;
import com.basintelligence.telemetry.EdgeGatewayService;
import com.basintelligence.users.UserAdminService;
import com.basintelligence.users.UserRole;
import com.basintelligence.web.api.AdminApiController;

import jakarta.validation.Validator;

/** Administration screen: users, factories, edge gateways and technician assignments. */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminWebController {

    private final UserAdminService users;
    private final FactoryService factories;
    private final EdgeGatewayService gateways;
    private final MachineService machines;
    private final Validator validator;

    public AdminWebController(UserAdminService users, FactoryService factories, EdgeGatewayService gateways,
            MachineService machines, Validator validator) {
        this.users = users;
        this.factories = factories;
        this.gateways = gateways;
        this.machines = machines;
        this.validator = validator;
    }

    @GetMapping
    public String admin(Model model) {
        var all = users.list();
        model.addAttribute("users", all);
        model.addAttribute("roles", UserRole.values());
        model.addAttribute("factories", factories.list());
        model.addAttribute("gateways", gateways.list());
        model.addAttribute("assignments", machines.assignments());
        model.addAttribute("machines", machines.list());
        model.addAttribute("technicianNames", all.stream().filter(u -> u.enabled() && u.role() == UserRole.TECHNICIAN)
                .map(UserAdminService.UserSummary::username).sorted(Comparator.naturalOrder()).toList());
        model.addAttribute("minPassword", UserAdminService.MIN_PASSWORD_LENGTH);
        return "admin";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam String username, @RequestParam String password, @RequestParam UserRole role,
            @RequestParam(required = false) String factoryCode, Authentication auth, RedirectAttributes redirect) {
        var request = new UserAdminService.NewUser(username.strip(), password, role, factoryCode);
        return run(redirect, "#users", () -> {
            validate(request);
            return "Created " + users.create(request, auth.getName()).username() + ".";
        });
    }

    @PostMapping("/users/{username}")
    public String updateUser(@PathVariable String username, @RequestParam UserRole role,
            @RequestParam(required = false) String factoryCode, @RequestParam(defaultValue = "false") boolean enabled,
            Authentication auth, RedirectAttributes redirect) {
        return run(redirect, "#users", () -> {
            users.update(username, new UserAdminService.UpdateUser(role, factoryCode, enabled), auth.getName());
            return "Updated " + username + ". Their open sessions were ended.";
        });
    }

    @PostMapping("/users/{username}/password")
    public String resetPassword(@PathVariable String username, @RequestParam String password, Authentication auth,
            RedirectAttributes redirect) {
        return run(redirect, "#users", () -> {
            validate(new UserAdminService.PasswordReset(password));
            users.resetPassword(username, password, auth.getName());
            return "Password changed for " + username + ". Their open sessions were ended.";
        });
    }

    @PostMapping("/factories")
    public String createFactory(@RequestParam String code, @RequestParam String name,
            @RequestParam(required = false) String location, Authentication auth, RedirectAttributes redirect) {
        var request = new AdminApiController.NewFactory(code.strip(), name, location);
        return run(redirect, "#factories", () -> {
            validate(request);
            return "Created factory " + factories.create(request.code(), request.name(), request.location(), auth.getName()).code() + ".";
        });
    }

    @PostMapping("/gateways")
    public String createGateway(@RequestParam String code, @RequestParam String factoryCode,
            @RequestParam(required = false) String description, Authentication auth, RedirectAttributes redirect) {
        var request = new AdminApiController.NewGateway(code.strip(), factoryCode, description);
        return run(redirect, "#gateways", () -> {
            validate(request);
            var issued = gateways.create(request.code(), request.factoryCode(), request.description(), auth.getName());
            redirect.addFlashAttribute("issuedKey", issued);
            return "Registered gateway " + issued.gateway().code() + ".";
        });
    }

    @PostMapping("/gateways/{code}/rotate")
    public String rotateGateway(@PathVariable String code, Authentication auth, RedirectAttributes redirect) {
        return run(redirect, "#gateways", () -> {
            var issued = gateways.rotate(code, auth.getName());
            redirect.addFlashAttribute("issuedKey", issued);
            return "Issued a new key for " + code + "; the previous key no longer works.";
        });
    }

    @PostMapping("/gateways/{code}/revoke")
    public String revokeGateway(@PathVariable String code, Authentication auth, RedirectAttributes redirect) {
        return run(redirect, "#gateways", () -> {
            gateways.revoke(code, auth.getName());
            return "Revoked gateway " + code + ".";
        });
    }

    @PostMapping("/assignments")
    public String assign(@RequestParam String machineCode, @RequestParam String username, Authentication auth,
            RedirectAttributes redirect) {
        return run(redirect, "#assignments", () -> {
            machines.assignTechnician(machineCode, username.strip(), auth.getName());
            return "Assigned " + username + " to " + machineCode + ".";
        });
    }

    @PostMapping("/assignments/delete")
    public String unassign(@RequestParam String machineCode, @RequestParam String username, Authentication auth,
            RedirectAttributes redirect) {
        return run(redirect, "#assignments", () -> {
            machines.unassignTechnician(machineCode, username, auth.getName());
            return "Removed " + username + " from " + machineCode + ".";
        });
    }

    /** Runs an admin action; rule violations come back to the same screen as an error message. */
    private static String run(RedirectAttributes redirect, String anchor, Supplier<String> action) {
        try {
            redirect.addFlashAttribute("flash", action.get());
        } catch (IllegalArgumentException | InvalidStateException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin" + anchor;
    }

    private <T> void validate(T request) {
        List<String> errors = validator.validate(request).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }
}

package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/v1/tenants/{tenant}/person-directory")
@ModuleEndpoint("finance")
public class FinancePersonDirectoryController {
    final FinancePersonDirectory directory;

    public FinancePersonDirectoryController(FinancePersonDirectory directory) {
        this.directory = directory;
    }

    Object read(
            Authentication a,
            String tenant,
            String resource,
            String id,
            MultiValueMap<String, String> q) {
        return FinanceHttp.respond(
                200,
                () ->
                        directory.read(
                                a.getName(),
                                FinancePersonDirectory.uuid(tenant),
                                resource,
                                id == null ? null : FinancePersonDirectory.uuid(id),
                                q));
    }

    @GetMapping("/configuration")
    public Object configuration(
            Authentication a,
            @PathVariable String tenant,
            @RequestParam MultiValueMap<String, String> q) {
        return read(a, tenant, "configuration", null, q);
    }

    @GetMapping("/persons")
    public Object persons(
            Authentication a,
            @PathVariable String tenant,
            @RequestParam MultiValueMap<String, String> q) {
        return read(a, tenant, "persons", null, q);
    }

    @GetMapping("/persons/{id}")
    public Object person(
            Authentication a,
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam MultiValueMap<String, String> q) {
        return read(a, tenant, "persons", id, q);
    }

    @GetMapping("/proposals")
    public Object proposals(
            Authentication a,
            @PathVariable String tenant,
            @RequestParam MultiValueMap<String, String> q) {
        return read(a, tenant, "proposals", null, q);
    }

    @GetMapping("/proposals/{id}")
    public Object proposal(
            Authentication a,
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestParam MultiValueMap<String, String> q) {
        return read(a, tenant, "proposals", id, q);
    }

    @GetMapping("/readiness")
    public Object readiness(
            Authentication a,
            @PathVariable String tenant,
            @RequestParam MultiValueMap<String, String> q) {
        return read(a, tenant, "readiness", null, q);
    }

    @PostMapping("/proposals")
    public Object create(
            Authentication a,
            @PathVariable String tenant,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    java.util.Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                201,
                () ->
                        directory.write(
                                a.getName(),
                                FinancePersonDirectory.uuid(tenant),
                                "create",
                                null,
                                body,
                                request));
    }

    @PostMapping("/proposals/{id}/confirm")
    public Object confirm(
            Authentication a,
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    java.util.Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () ->
                        directory.write(
                                a.getName(),
                                FinancePersonDirectory.uuid(tenant),
                                "confirm",
                                FinancePersonDirectory.uuid(id),
                                body,
                                request));
    }

    @PostMapping("/proposals/{id}/cancel")
    public Object cancel(
            Authentication a,
            @PathVariable String tenant,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    java.util.Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () ->
                        directory.write(
                                a.getName(),
                                FinancePersonDirectory.uuid(tenant),
                                "cancel",
                                FinancePersonDirectory.uuid(id),
                                body,
                                request));
    }
}

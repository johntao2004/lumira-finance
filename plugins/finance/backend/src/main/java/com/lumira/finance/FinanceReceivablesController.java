package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/receivables")
@ModuleEndpoint("finance")
public final class FinanceReceivablesController {
    private final FinanceReceivables service;
    public FinanceReceivablesController(FinanceReceivables service) { this.service = service; }
    private UUID id(String s) { return FinancePersonDirectory.uuid(s); }

    @GetMapping
    public Object list(Authentication actor, @PathVariable String book, @PathVariable String year,
            HttpServletRequest request) {
        return FinanceHttp.respond(200, () -> {
            query(request, Set.of("after", "limit", "asOf"));
            int limit = 20;
            try { if (request.getParameter("limit") != null) limit = Integer.parseInt(request.getParameter("limit")); }
            catch (NumberFormatException e) { throw FinanceException.invalid("分页大小无效"); }
            return service.read(actor.getName(), id(book), id(year), null, request.getParameter("after"), limit, asOf(request));
        });
    }
    @GetMapping("/{target}")
    public Object detail(Authentication actor, @PathVariable String book, @PathVariable String year,
            @PathVariable String target, HttpServletRequest request) {
        return FinanceHttp.respond(200, () -> {
            query(request, Set.of("asOf"));
            return service.read(actor.getName(), id(book), id(year), id(target), null, 20, asOf(request));
        });
    }
    @PostMapping
    public Object create(Authentication actor, @PathVariable String book, @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes=32768,errorCode="INVALID_INPUT") Map<String,Object> body,
            HttpServletRequest request) { return write(actor,book,year,null,"create",body,request); }

    @PostMapping("/{target}/submit")
    public Object submit(Authentication actor, @PathVariable String book, @PathVariable String year,
            @PathVariable String target,
            @RequestBody @BoundedBody(maxBytes=8192,errorCode="INVALID_INPUT") Map<String,Object> body,
            HttpServletRequest request) { return action(actor,book,year,target,"submit",body,request); }

    @PostMapping("/{target}/approve")
    public Object approve(Authentication actor, @PathVariable String book, @PathVariable String year,
            @PathVariable String target,
            @RequestBody @BoundedBody(maxBytes=8192,errorCode="INVALID_INPUT") Map<String,Object> body,
            HttpServletRequest request) { return action(actor,book,year,target,"approve",body,request); }

    @PostMapping("/{target}/post")
    public Object post(Authentication actor, @PathVariable String book, @PathVariable String year,
            @PathVariable String target,
            @RequestBody @BoundedBody(maxBytes=8192,errorCode="INVALID_INPUT") Map<String,Object> body,
            HttpServletRequest request) { return action(actor,book,year,target,"post",body,request); }

    @PostMapping("/{target}/withdraw")
    public Object withdraw(Authentication actor, @PathVariable String book, @PathVariable String year,
            @PathVariable String target,
            @RequestBody @BoundedBody(maxBytes=8192,errorCode="INVALID_INPUT") Map<String,Object> body,
            HttpServletRequest request) { return action(actor,book,year,target,"withdraw",body,request); }

    @PostMapping("/{target}/reject")
    public Object reject(Authentication actor, @PathVariable String book, @PathVariable String year,
            @PathVariable String target,
            @RequestBody @BoundedBody(maxBytes=8192,errorCode="INVALID_INPUT") Map<String,Object> body,
            HttpServletRequest request) { return action(actor,book,year,target,"reject",body,request); }

    @PostMapping("/{target}/cancel")
    public Object cancel(Authentication actor, @PathVariable String book, @PathVariable String year,
            @PathVariable String target,
            @RequestBody @BoundedBody(maxBytes=8192,errorCode="INVALID_INPUT") Map<String,Object> body,
            HttpServletRequest request) { return action(actor,book,year,target,"cancel",body,request); }

    public Object action(Authentication actor, @PathVariable String book, @PathVariable String year,
            @PathVariable String target, @PathVariable String action,
            @RequestBody @BoundedBody(maxBytes=8192,errorCode="INVALID_INPUT") Map<String,Object> body,
            HttpServletRequest request) { return write(actor,book,year,target,action,body,request); }

    private Object write(Authentication actor,String book,String year,String target,String action,
            Map<String,Object> body,HttpServletRequest request) {
        return FinanceHttp.respond(action.equals("create")?201:200, () -> {
            query(request, Set.of());
            return service.write(actor.getName(),id(book),id(year),action,target==null?null:id(target),body,request);
        });
    }
    private LocalDate asOf(HttpServletRequest request) {
        try { return FinanceReceivablesRules.date(request.getParameter("asOf")); }
        catch (IllegalArgumentException e) { throw FinanceException.invalid("必须提供明确的账龄观察日期"); }
    }
    private void query(HttpServletRequest request,Set<String> allowed) {
        request.getParameterMap().forEach((key,value) -> {
            if(!allowed.contains(key)||value==null||value.length!=1) throw FinanceException.invalid("查询字段无效或重复");
        });
    }
}

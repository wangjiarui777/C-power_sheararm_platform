package com.ruoyi.sensor.web;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.security.WebSocketTicketService;

@RestController
@RequestMapping("/sensor/ws-ticket")
public class WebSocketTicketController extends BaseController
{
    private final WebSocketTicketService ticketService;

    public WebSocketTicketController(WebSocketTicketService ticketService)
    {
        this.ticketService = ticketService;
    }

    @PreAuthorize("@ss.hasAnyPermi('sensor:monitoring:view,sensor:diagnosis:view,phm:alarm:list')")
    @PostMapping
    public AjaxResult issue()
    {
        String ticket = ticketService.issue(getLoginUser());
        return success(Map.of("ticket", ticket, "expiresIn", ticketService.getTicketTtlSeconds()));
    }
}

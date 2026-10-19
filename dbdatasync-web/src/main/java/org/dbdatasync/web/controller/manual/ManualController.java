/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.web.controller.manual;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * 操作手册：面向最终用户的详细使用说明（离线内置，无外链依赖）。
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-10-18
 */
@Controller
public class ManualController {

    /**
     * 操作手册页面。
     */
    @GetMapping("/manual")
    public ModelAndView index() {
        return new ModelAndView("manual/index");
    }
}

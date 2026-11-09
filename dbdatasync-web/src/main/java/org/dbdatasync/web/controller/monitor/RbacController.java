package org.dbdatasync.web.controller.monitor;

import org.dbdatasync.biz.vo.RestResult;
import org.dbdatasync.web.config.RbacPolicy;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;

/**
 * Wave24：RBAC 权限矩阵查询端点。
 *
 * @author DBDataSync
 * @version 2.1.5
 */
@Controller
@RequestMapping("/rbac")
public class RbacController {

    @Resource
    private RbacPolicy rbacPolicy;

    /** 当前严格模式状态、角色清单与 URL 权限规则 */
    @PostMapping("/policy")
    @ResponseBody
    public RestResult policy() {
        return RestResult.restSuccess(rbacPolicy.policy());
    }
}

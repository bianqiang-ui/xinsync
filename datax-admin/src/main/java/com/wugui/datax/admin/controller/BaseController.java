package com.wugui.datax.admin.controller;


import com.baomidou.mybatisplus.extension.api.ApiController;
import com.wugui.datax.admin.util.JwtTokenUtils;

import javax.servlet.http.HttpServletRequest;

import static com.wugui.datatx.core.util.Constants.STRING_BLANK;

/**
 * base controller
 */
public class BaseController extends ApiController {

    /**
     * 当前登录用户的 id；取不到就返回 null，由 {@code AccessControl} 按最小权限处理。
     *
     * 原先这里直接 {@code auth.nextElement()}：请求头没有 Authorization 时抛
     * NoSuchElementException —— 归属判定接线之后，"判据从哪来"这一步比判定本身更容易 500。
     */
    public Integer getCurrentUserId(HttpServletRequest request) {
        String tokenHeader = request.getHeader(JwtTokenUtils.TOKEN_HEADER);
        if (tokenHeader == null || !tokenHeader.startsWith(JwtTokenUtils.TOKEN_PREFIX)) {
            return null;
        }
        return JwtTokenUtils.getUserId(tokenHeader.replace(JwtTokenUtils.TOKEN_PREFIX, STRING_BLANK));
    }
}
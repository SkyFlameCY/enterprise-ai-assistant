package cn.coder.sanwei.enterpriseaiassistant.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SecurityController
 *
 * @author caiyang
 * @date 2026/10/2
 */
@RestController
@RequestMapping("/security")
public class SecurityController {

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken csrfToken) {
        return csrfToken;
    }
}

package org.leon.aimodule.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @GetMapping("/testGateway")
    public String testGateway(){
        return "AI gateway is ok ";
    }


}

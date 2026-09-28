package org.leon.practicemodule.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/practice")
public class PracticeController {


    @GetMapping("/tetsgateway")
    public String Testgateway(){
        return "gateway is ok.";
    }
}

package org.leon.usermodule.controller;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.pojo.Users;

import org.leon.usermodule.service.UsersService;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {
    @Autowired
    private UsersService usersService;

    @GetMapping("selectAll")
    public List<Users> selectAll(){
        return usersService.selectAll();
    }
}

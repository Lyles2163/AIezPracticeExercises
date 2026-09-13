package org.leon.usermodule.controller;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.annotation.LogOperation;
import org.leon.usermodule.pojo.Users;

import org.leon.usermodule.service.UsersService;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {
    @Autowired
    private UsersService usersService;

    @LogOperation
    @GetMapping("/test")
    public String test(){
        return "test success";
    }

    @DeleteMapping
    public void delete(Integer id){
        usersService.deleteById(id);
    }

    @GetMapping("selectAll")
    public List<Users> selectAll(){
        return usersService.selectAll();
    }
}

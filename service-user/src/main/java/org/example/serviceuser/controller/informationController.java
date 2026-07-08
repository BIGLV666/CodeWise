package org.example.serviceuser.controller;

import org.example.serviceapi.dto.Result;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.service.FileUploadService;
import org.example.serviceuser.service.InformationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/user")
public class informationController {
    @Autowired
    private InformationService informationService;
    @Autowired
    private FileUploadService fileUploadService;

    @PostMapping("/avatar")
    public Result<String> avatar(@RequestParam("file") MultipartFile file) {
        String url = fileUploadService.uploadFile(file, "avatar");
        try {
            String oldUrl = informationService.updateAvatar(url);
            fileUploadService.deleteFile(oldUrl);
        } catch (RuntimeException e) {
            fileUploadService.deleteFile(url);
            throw e;
        }
        return Result.success(url);
    }

    /**
     * 修改当前用户昵称
     */
    @PutMapping("/nickname")
    public Result<UserDto> updateNickName(@RequestParam String nickName) {
        return Result.success(informationService.updateNickName(nickName));
    }

    /**
     * 修改当前用户个人简介
     */
    @PutMapping("/bio")
    public Result<UserDto> updateBio(@RequestParam String bio) {
        return Result.success(informationService.updateBio(bio));
    }

    /**
     * 修改当前用户生日，格式：yyyy-MM-dd
     */
    @PutMapping("/birthday")
    public Result<UserDto> updateBirthday(@RequestParam String birthday) {
        return Result.success(informationService.updateBirthday(birthday));
    }

}

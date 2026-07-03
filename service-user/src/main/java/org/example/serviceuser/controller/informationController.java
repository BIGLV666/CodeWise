package org.example.serviceuser.controller;

import org.example.serviceapi.dto.Result;
import org.example.serviceuser.service.FileUploadService;
import org.example.serviceuser.service.InformationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
        String url= fileUploadService.uploadFile(file,"avatar");
        String OldUrl= informationService.updateAvatar(url);
        fileUploadService.deleteFile(OldUrl);
        return Result.success("success");
    }

}

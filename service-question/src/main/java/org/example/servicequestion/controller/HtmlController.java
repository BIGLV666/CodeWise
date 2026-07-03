package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.service.HtmlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/question")
public class HtmlController {
    @Autowired
    private HtmlService htmlService;
    @PostMapping("/html")
    public Result<Question> html(@RequestParam("file") MultipartFile file) throws Exception {

            // 1. 获取文件内容
            String htmlContent = new String(file.getBytes());

            // 2. 使用解析器解析HTML
            Question question=htmlService.Luogu(htmlContent);
            return Result.success(question);
    }
}

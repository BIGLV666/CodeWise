package org.example.serviceuser.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@Service
public class FileUploadService {
    @Value("${file.upload.root}")
    private String uploadRoot;

    /**
     * 上传文件
     * @param file 文件
     * @param businessDir 业务目录（avatar / question / contest）
     * @return 访问 URL
     */
    public String uploadFile(MultipartFile file, String businessDir) {
        // 1. 校验文件是否为空
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("文件不能为空");
        }

        // 2. 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new RuntimeException("只允许上传图片");
        }

        // 3. 校验文件大小（限制 5MB）
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new RuntimeException("文件大小不能超过 5MB");
        }

        // 4. 生成存储路径
        String originalFilename = file.getOriginalFilename();
        String relativePath = org.example.servicecommon.until.FilePathUtil.generatePath(businessDir, originalFilename);

        // 5. 构建目标文件
        File dest = new File(uploadRoot + relativePath);

        // 6. 自动创建父目录
        if (!dest.getParentFile().exists()) {
            dest.getParentFile().mkdirs();
        }

        // 7. 保存文件
        try {
            file.transferTo(dest);
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage());
        }

        // 8. 返回访问 URL（前端直接访问这个路径）
        return "/uploads/" + relativePath;
    }

    /**
     * 删除文件
     * @param filePath 文件相对路径
     */
    public boolean deleteFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        // 去掉 /uploads/ 前缀，得到相对路径
        String relativePath = filePath.replace("/uploads/", "");
        File file = new File(uploadRoot + relativePath);
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }
}

package org.example.serviceuser.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

@Service
public class FileUploadService {
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif");

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
        if (businessDir == null || businessDir.isBlank() || businessDir.contains("..") || businessDir.contains("/") || businessDir.contains("\\")) {
            throw new RuntimeException("非法业务目录");
        }

        // 2. 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new RuntimeException("只允许上传图片");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new RuntimeException("只允许上传 jpg、jpeg、png、gif 图片");
        }

        try {
            if (ImageIO.read(file.getInputStream()) == null) {
                throw new RuntimeException("文件内容不是有效图片");
            }
        } catch (IOException e) {
            throw new RuntimeException("图片文件校验失败");
        }

        // 3. 校验文件大小（限制 5MB）
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new RuntimeException("文件大小不能超过 5MB");
        }

        // 4. 生成存储路径
        String relativePath = org.example.servicecommon.until.FilePathUtil.generatePath(businessDir, originalFilename);

        // 5. 构建目标文件
        Path rootPath = Paths.get(uploadRoot).toAbsolutePath().normalize();
        Path destPath = rootPath.resolve(relativePath).normalize();
        if (!destPath.startsWith(rootPath)) {
            throw new RuntimeException("非法文件路径");
        }
        File dest = destPath.toFile();

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
        if (!filePath.startsWith("/uploads/")) {
            return false;
        }
        // 去掉 /uploads/ 前缀，得到相对路径
        String relativePath = filePath.substring("/uploads/".length());
        try {
            Path rootPath = Paths.get(uploadRoot).toAbsolutePath().normalize();
            Path targetPath = rootPath.resolve(relativePath).normalize();
            if (!targetPath.startsWith(rootPath) || !Files.isRegularFile(targetPath)) {
                return false;
            }
            return Files.deleteIfExists(targetPath);
        } catch (IOException e) {
            return false;
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase(Locale.ROOT);
    }
}

package org.example.servicecommon.until;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class FilePathUtil {
    /**
     * 生成存储路径
     * @param businessDir 业务目录，如 "avatar"
     * @param originalFilename 原始文件名
     * @return 相对路径，如 "avatar/2026/06/27/uuid.jpg"
     */
    public static String generatePath(String businessDir, String originalFilename) {
        // 1. 按日期分目录
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        // 2. 生成唯一文件名
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + suffix;

        // 3. 组合路径
        return businessDir + "/" + datePath + "/" + filename;
    }
}

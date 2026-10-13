package org.example.serviceuser.Config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传目录静态资源位置的回归护栏。
 *
 * <p>该位置曾写死开发机 Windows 路径 {@code file:E:/learncard/CodeWise/service-user/static/uploads/}，
 * 而写入端 {@code FileUploadService} 用的是 {@code file.upload.root}（容器内 {@code /app/data/uploads}）：
 * 头像/题目配图上传成功后访问一律 404，且 404 只出现在线上，本地开发看不出来。</p>
 */
class WebConfigUploadLocationTest {

    @Test
    void 资源位置应跟随配置的上传根目录() {
        assertEquals("file:/app/data/uploads/", WebConfig.uploadLocation("/app/data/uploads"));
        assertEquals("file:/srv/codewise/uploads/", WebConfig.uploadLocation("/srv/codewise/uploads"));
    }

    @Test
    void 资源位置不应再写死开发机路径() {
        String location = WebConfig.uploadLocation("/app/data/uploads");

        assertTrue(location.startsWith("file:/"), location);
        assertFalse(location.contains("learncard"), "不得再出现开发机路径: " + location);
        assertFalse(location.contains("E:"), "不得再出现 Windows 盘符: " + location);
    }

    @Test
    void 相对路径按工作目录解析为绝对路径() {
        String location = WebConfig.uploadLocation("data/uploads");

        assertTrue(location.startsWith("file:/"), location);
        assertTrue(location.endsWith("/data/uploads/"), location);
    }
}

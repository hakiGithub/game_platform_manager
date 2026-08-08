package com.gameplatform.util;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件工具类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class FileUtils {

    private FileUtils() {
    }

    /**
     * 确保目录存在,不存在则创建
     *
     * @param dirPath 目录路径
     * @return 目录文件
     */
    public static File ensureDir(String dirPath) {
        if (StrUtil.isBlank(dirPath)) {
            throw new IllegalArgumentException("目录路径不能为空");
        }
        File dir = new File(dirPath);
        if (!dir.exists()) {
            FileUtil.mkdir(dir);
        }
        return dir;
    }

    /**
     * 确保文件所在目录存在
     *
     * @param filePath 文件路径
     */
    public static void ensureParentDir(String filePath) {
        if (StrUtil.isBlank(filePath)) {
            return;
        }
        Path path = Paths.get(filePath);
        File parent = path.getParent().toFile();
        if (!parent.exists()) {
            FileUtil.mkdir(parent);
        }
    }

    /**
     * 获取文件扩展名
     *
     * @param filename 文件名
     * @return 扩展名(不含点)
     */
    public static String getExtension(String filename) {
        if (StrUtil.isBlank(filename)) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1);
    }

    /**
     * 获取不带扩展名的文件名
     *
     * @param filename 文件名
     * @return 不带扩展名的文件名
     */
    public static String getBaseName(String filename) {
        if (StrUtil.isBlank(filename)) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) {
            return filename;
        }
        return filename.substring(0, lastDot);
    }

    /**
     * 判断文件是否存在
     *
     * @param filePath 文件路径
     * @return 是否存在
     */
    public static boolean exists(String filePath) {
        if (StrUtil.isBlank(filePath)) {
            return false;
        }
        return new File(filePath).exists();
    }

    /**
     * 删除文件或目录
     *
     * @param path 路径
     * @return 是否成功
     */
    public static boolean delete(String path) {
        if (StrUtil.isBlank(path)) {
            return false;
        }
        return FileUtil.del(path);
    }

    /**
     * 复制文件
     *
     * @param srcPath  源文件路径
     * @param destPath 目标文件路径
     * @return 是否成功
     */
    public static boolean copy(String srcPath, String destPath) {
        if (StrUtil.isBlank(srcPath) || StrUtil.isBlank(destPath)) {
            return false;
        }
        try {
            FileUtil.copy(srcPath, destPath, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 移动文件
     *
     * @param srcPath  源文件路径
     * @param destPath 目标文件路径
     * @return 是否成功
     */
    public static boolean move(String srcPath, String destPath) {
        if (StrUtil.isBlank(srcPath) || StrUtil.isBlank(destPath)) {
            return false;
        }
        try {
            FileUtil.move(new File(srcPath), new File(destPath), true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}

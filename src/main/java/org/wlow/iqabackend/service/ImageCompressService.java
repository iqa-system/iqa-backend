package org.wlow.iqabackend.service;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * 图片压缩与Base64编码服务.
 * 使用 Thumbnailator 库将图片压缩到目标大小以内, 再转为Base64字符串落库.
 */
@Slf4j
@Service
public class ImageCompressService {
    /**
     * 单条历史记录中图片字节上限: 15KB.
     */
    private static final int MAX_BYTES = 15 * 1024;

    /**
     * 将上传图片压缩到15KB以内并编码为Base64字符串.
     */
    public String compressToBase64(MultipartFile file) {
        try {
            byte[] imageBytes = file.getBytes();
            return compressToBase64(imageBytes);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取图片失败");
        }
    }

    /**
     * 将图片字节压缩到15KB以内并编码为Base64字符串.
     */
    public String compressToBase64(byte[] imageBytes) {
        try {
            byte[] compressedBytes = compressToLimitFast(imageBytes);
            return Base64.getEncoder().encodeToString(compressedBytes);
        } catch (IOException ex) {
            throw new IllegalArgumentException("图片压缩异常", ex);
        }
    }

    /**
     * 高效压缩算法：采用一次或最多两次定档处理，不使用双层循环。
     */
    private byte[] compressToLimitFast(byte[] sourceBytes) throws IOException {
        if (sourceBytes.length <= MAX_BYTES) {
            return sourceBytes;
        }

        ByteArrayOutputStream os = new ByteArrayOutputStream();

        // 第一次尝试：15KB 的极限图片一般支持在 300x300 分辨率和 0.6 左右画质
        Thumbnails.of(new ByteArrayInputStream(sourceBytes))
                .size(300, 300)
                .outputQuality(0.6f)
                .outputFormat("jpg")
                .toOutputStream(os);

        byte[] result = os.toByteArray();

        // 如果超出 15KB，再进行一次极限兜底压缩
        if (result.length > MAX_BYTES) {
            os.reset();
            Thumbnails.of(new ByteArrayInputStream(sourceBytes))
                    .size(150, 150)
                    .outputQuality(0.3f)
                    .outputFormat("jpg")
                    .toOutputStream(os);
            result = os.toByteArray();
        }

        if (result.length > MAX_BYTES) {
            log.warn("图片无法进一步压缩到15KB以内, 使用兜底结果, 大小={}字节", result.length);
        }

        return result;
    }
}

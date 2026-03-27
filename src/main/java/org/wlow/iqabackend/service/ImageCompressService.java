package org.wlow.iqabackend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/**
 * 图片压缩与Base64编码服务.
 * 目标是将上传图片压缩到15KB以内, 再转为Base64字符串落库.
 */
@Slf4j
@Service
public class ImageCompressService {
    /**
     * 单条历史记录中图片字节上限, 15KB.
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
            byte[] compressedBytes = compressToLimit(imageBytes);
            return Base64.getEncoder().encodeToString(compressedBytes);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取图片失败");
        }
    }

    /**
     * 核心压缩流程.
     * 策略是先按不同缩放比例降分辨率, 在每个比例下再逐步降低JPEG质量.
     * 只要命中15KB阈值即返回, 否则返回本轮最小结果并记录告警日志.
     */
    private byte[] compressToLimit(byte[] sourceBytes) throws IOException {
        BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(sourceBytes));
        if (sourceImage == null) {
            throw new IllegalArgumentException("仅支持图片文件");
        }

        byte[] bestBytes = null;
        int bestSize = Integer.MAX_VALUE;

        for (double scale = 1.0; scale >= 0.2; scale -= 0.1) {
            BufferedImage scaledImage = scaleImage(sourceImage, scale);
            for (float quality = 0.9f; quality >= 0.2f; quality -= 0.1f) {
                byte[] candidate = encodeJpeg(scaledImage, quality);
                int candidateSize = candidate.length;
                if (candidateSize < bestSize) {
                    bestSize = candidateSize;
                    bestBytes = candidate;
                }
                if (candidateSize <= MAX_BYTES) {
                    return candidate;
                }
            }
        }

        if (bestBytes == null) {
            throw new IllegalArgumentException("图片压缩失败");
        }
        log.warn("图片无法压缩到15KB以内, 将使用最小可得结果, 大小={}字节", bestSize);
        return bestBytes;
    }

    /**
     * 按比例缩放图片.
     * 目标宽高至少为1像素, 防止极小图在多轮压缩时出现非法尺寸.
     */
    private BufferedImage scaleImage(BufferedImage sourceImage, double scale) {
        int targetWidth = Math.max(1, (int) Math.round(sourceImage.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(sourceImage.getHeight() * scale));

        // 统一转为RGB并用白色背景兜底, 避免带透明通道图片在JPEG编码时出现黑底.
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, targetWidth, targetHeight);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return scaled;
    }

    /**
     * 以指定质量编码JPEG.
     */
    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("当前运行环境不支持JPEG编码");
        }
        ImageWriter writer = writers.next();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(imageOutputStream);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), writeParam);
            writer.dispose();
            return outputStream.toByteArray();
        }
    }
}


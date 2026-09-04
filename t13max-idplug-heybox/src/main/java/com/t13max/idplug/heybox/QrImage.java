package com.t13max.idplug.heybox;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/** 有界解码来自官方网页的 PNG，不接受外部图片地址或保存文件。 */
public final class QrImage {
    /** 禁止创建工具实例。 */
    private QrImage() { }

    /** 在分配完整图像之前校验格式与尺寸。 */
    public static BufferedImage decode(String value) {
        if (value == null || !value.startsWith("data:image/png;base64,") || value.length() > 400000) throw new IllegalArgumentException("Invalid QR image");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value.substring(22))))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("Invalid PNG");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!"png".equalsIgnoreCase(reader.getFormatName()) || width < 64 || height < 64 || width > 1024 || height > 1024 || Math.abs(width - height) > 8) throw new IllegalArgumentException("Invalid QR dimensions");
                return reader.read(0);
            } finally { reader.dispose(); }
        } catch (Exception error) { throw new IllegalArgumentException("Unable to display QR image"); }
    }
}

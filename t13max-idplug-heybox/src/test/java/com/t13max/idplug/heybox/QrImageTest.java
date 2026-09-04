package com.t13max.idplug.heybox;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

/** 验证二维码图片的输入限制，不使用真实登录二维码。 */
final class QrImageTest {
    /** 生成无账号信息的 PNG 样本。 */
    private String sample(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    /** 有效正方形图像可以在内存解码。 */
    @Test
    void acceptsPng() throws Exception { assertEquals(128, QrImage.decode(sample(128, 128)).getWidth()); }

    /** 拒绝外部地址、无效内容和过大消息。 */
    @Test
    void rejectsInvalidData() {
        for (String value : new String[]{"https://example.com/qr.png", "data:image/png;base64,INVALID", "data:image/png;base64," + "A".repeat(400000)}) assertThrows(IllegalArgumentException.class, () -> QrImage.decode(value));
    }

    /** 在解码像素前拒绝非二维码尺寸。 */
    @Test
    void rejectsInvalidDimensions() throws Exception {
        String wide = sample(512, 64);
        String large = sample(1025, 1025);
        assertThrows(IllegalArgumentException.class, () -> QrImage.decode(wide));
        assertThrows(IllegalArgumentException.class, () -> QrImage.decode(large));
    }
}
